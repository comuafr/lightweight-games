package lg.core.session

import lg.core.Game
import lg.core.GameMeta
import lg.core.GameStatus
import lg.core.input.Gesture
import lg.core.input.GestureDetector
import lg.core.input.GestureKind
import lg.core.input.PointerPhase
import lg.core.scene.Align
import lg.core.scene.Palette
import lg.core.scene.Scene
import lg.core.scene.StringKey
import lg.core.store.KeyValueStore
import lg.core.store.Scores
import lg.core.store.Settings
import lg.core.util.Clock
import lg.core.util.Rect

/**
 * Owns one [Game] instance end-to-end: routing raw input and gestures either
 * to the overlay or to the game, driving [update]/[render], drawing the HUD
 * and overlays (start / paused / game over) on top of the game's own scene,
 * and persisting scores and (for resumable games) save state.
 *
 * This is the only place overlays exist; individual games never draw their
 * own menus (PLAN.md §2).
 */
public class GameSession(
    public val meta: GameMeta,
    private val scores: Scores,
    private val settings: Settings,
    private val clock: Clock,
) {
    private val game: Game = meta.factory()

    public var overlay: Overlay = Overlay.Start
        private set

    /** Current score of the underlying game; mirrors what the HUD draws. */
    public val score: Int
        get() = game.score

    /** Called when the player taps the permanent "Quit" button; the platform leaves the game screen. */
    public var onQuit: (() -> Unit)? = null

    /** Called when the player taps the permanent "Help" button; the platform shows the rules and controls. */
    public var onHelp: (() -> Unit)? = null

    // Full view size (screen dp). The game and the overlays always work in a fixed
    // logical play zone of `width` x `height` ([LOGICAL_W] x [LOGICAL_H]) so that
    // every player gets the same game whatever the screen; the session scales that
    // zone uniformly by `scale` and draws it at `originX`/`originY` in the view.
    private var viewW: Float = 0f
    private var viewH: Float = 0f
    private var safeTop: Float = 0f
    private var width: Float = LOGICAL_W
    private var height: Float = LOGICAL_H
    private var scale: Float = 1f
    private var zoneW: Float = 0f
    private var zoneH: Float = 0f
    private var originX: Float = 0f
    private var originY: Float = 0f
    private var frameX: Float = 0f
    private var frameY: Float = 0f
    private var frameW: Float = 0f
    private var frameH: Float = 0f
    private var barY: Float = 0f
    private var helpX: Float = 0f
    private var helpW: Float = 0f
    private var quitX: Float = 0f
    private var quitW: Float = 0f
    private var players: Int = 1
    private var ignoredPointer: Int = NO_POINTER

    /**
     * True when the most recent [game] state change has not yet been rendered.
     * A game's `render` sets `scene.sound(...)`/`scene.haptic()` from flags
     * that describe "what just happened", but those flags stay readable
     * until the game's next state change, not just for one render call. If a
     * redraw is triggered purely by input (a pointer move with no state
     * change), replaying `render` would re-emit the same sound/haptic event
     * on the platform without anything new having happened. `eventsArmed` is
     * set whenever a state change actually occurred ([update] returning
     * true, [start], or the game transitioning to [GameStatus.Over]) and
     * consumed by the very next [render] call, so a sound/haptic event fires
     * at most once per state change.
     */
    private var eventsArmed: Boolean = false

    private val gestureDetector = GestureDetector { gesture -> handleGesture(gesture) }

    // Fixed-capacity overlay button model: at most one row per possible action,
    // recomputed only when the overlay or the layout changes (never per frame).
    private val buttonRect = Array(MAX_BUTTONS) { Rect() }
    private val buttonLabel = arrayOfNulls<StringKey>(MAX_BUTTONS)
    private val buttonAction = IntArray(MAX_BUTTONS)
    private var buttonCount = 0

    init {
        // The game's play zone has a fixed size, known from the start.
        game.resize(width, height)
        layoutButtons()
    }

    /** Screen scale of the fixed play zone (for tests). */
    internal val playScale: Float get() = scale

    /** Screen position of the play zone's top-left corner (for tests). */
    internal val playOriginX: Float get() = originX
    internal val playOriginY: Float get() = originY

    /** Starts (or restarts) the game with [players] participants and a deterministic [seed]. */
    public fun start(players: Int = 1, seed: Long = clock.nowMs()) {
        this.players = players.coerceIn(meta.players.first, meta.players.last)
        game.reset(seed, this.players)
        overlay = Overlay.None
        eventsArmed = true
        layoutButtons()
    }

    /**
     * Notifies the session (and the game, if playing) of a size change; never
     * resets state. [safeTop] is the height of whatever the platform covers at
     * the top of the view (status bar, camera cut-out); the frame starts below it.
     */
    public fun resize(width: Float, height: Float, safeTop: Float = 0f) {
        viewW = width
        viewH = height
        this.safeTop = safeTop
        layoutChrome()
        game.resize(this.width, this.height)
        layoutButtons()
    }

    private fun layoutChrome() {
        barY = (viewH - BAR_HEIGHT_PT - BAR_MARGIN_PT).coerceAtLeast(0f)
        // Room for the play zone: inside the page margins and the frame border, between the
        // top inset and the button bar.
        val availX = FRAME_MARGIN_PT + FRAME_BORDER_PT
        val availW = (viewW - 2 * availX).coerceAtLeast(0f)
        val availTop = maxOf(safeTop, 0f) + TOP_MARGIN_PT + FRAME_BORDER_PT
        val availH = (barY - FRAME_MARGIN_PT - FRAME_BORDER_PT - availTop).coerceAtLeast(0f)
        scale = minOf(availW / LOGICAL_W, availH / LOGICAL_H).coerceAtLeast(MIN_SCALE)
        zoneW = LOGICAL_W * scale
        zoneH = LOGICAL_H * scale
        originX = availX + (availW - zoneW) / 2f
        originY = availTop + (availH - zoneH) / 2f
        frameX = originX - FRAME_BORDER_PT
        frameY = originY - FRAME_BORDER_PT
        frameW = zoneW + 2 * FRAME_BORDER_PT
        frameH = zoneH + 2 * FRAME_BORDER_PT
        val barW = (viewW - 2 * FRAME_MARGIN_PT).coerceIn(0f, BAR_MAX_WIDTH_PT)
        helpW = barW * HELP_SHARE
        quitW = barW - helpW - BAR_GAP_PT
        helpX = (viewW - barW) / 2f
        quitX = helpX + helpW + BAR_GAP_PT
    }

    /** Routes a raw pointer event to the overlay (via gesture detection) or the game. */
    public fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        // A touch that starts on the Quit bar never reaches the game.
        if (phase == PointerPhase.DOWN && y >= barY) ignoredPointer = id
        if (id != ignoredPointer && overlay == Overlay.None) {
            game.onPointer(id, phase, (x - originX) / scale, (y - originY) / scale)
        }
        if (id == ignoredPointer && (phase == PointerPhase.UP || phase == PointerPhase.CANCEL)) {
            ignoredPointer = NO_POINTER
            // Gesture detector still gets the UP below so the tap can be recognised.
        }
        gestureDetector.onPointer(id, phase, x, y, clock.nowMs())
    }

    /** Advances the game one frame. [dtMs] is clamped to [MAX_DT_MS]. @return true if a redraw is needed. */
    public fun update(dtMs: Int): Boolean {
        val clampedDt = dtMs.coerceAtMost(MAX_DT_MS)
        gestureDetector.update(clock.nowMs())
        if (overlay != Overlay.None) return false

        val changed = game.update(clampedDt)
        if (changed) eventsArmed = true
        val status = game.status
        if (status is GameStatus.Over) {
            scores.submit(meta.id, game.score)
            overlay = Overlay.Over(status.winner)
            eventsArmed = true
            layoutButtons()
            return true
        }
        return changed
    }

    /** Draws the game, then the HUD, then any active overlay, into [scene]. */
    public fun render(scene: Scene) {
        scene.clear()
        renderFrame(scene)
        val mark = scene.count
        game.render(scene)
        if (!eventsArmed) {
            // No state change happened since the last render: this redraw was
            // triggered by input alone, so suppress the game's leftover
            // sound/haptic flags (see [eventsArmed]).
            scene.muteSound()
            scene.muteHaptic()
        }
        eventsArmed = false
        if (!settings.soundEnabled) scene.muteSound()
        if (!settings.hapticsEnabled) scene.muteHaptic()
        renderHud(scene)
        renderOverlay(scene)
        scene.transformFrom(mark, scale, originX, originY)
        renderScanlines(scene)
        renderQuitBar(scene)
    }

    /** Pauses a running game, showing the Paused overlay. No-op if an overlay is already shown. */
    public fun pause() {
        if (overlay == Overlay.None) {
            overlay = Overlay.Paused
            layoutButtons()
        }
    }

    /** Dismisses the Paused overlay and hands input/time back to the game. No-op otherwise. */
    public fun resume() {
        if (overlay is Overlay.Paused) {
            overlay = Overlay.None
        }
    }

    /** True while a real-time frame loop is required: playing and the game itself wants frames. */
    public val needsFrameLoop: Boolean
        get() = overlay == Overlay.None && game.wantsFrames

    /** Persists the game's resumable state (no-op if [GameMeta.resumable] is false or nothing to save). */
    public fun persist(store: KeyValueStore) {
        if (!meta.resumable) return
        val snapshot = game.snapshot() ?: return
        store.putString(saveKey(), snapshot)
    }

    /** Attempts to restore previously persisted state. @return true if a save was found and applied. */
    public fun tryRestore(store: KeyValueStore): Boolean {
        if (!meta.resumable) return false
        val data = store.getString(saveKey(), null) ?: return false
        return game.restore(data)
    }

    private fun saveKey(): String = "save.${meta.id}"

    private fun handleGesture(gesture: Gesture) {
        if (gesture.y >= barY) {
            if (gesture.kind == GestureKind.TAP) {
                if (gesture.x >= quitX && gesture.x <= quitX + quitW) {
                    onQuit?.invoke()
                } else if (gesture.x >= helpX && gesture.x <= helpX + helpW) {
                    pause()
                    onHelp?.invoke()
                }
            }
            return
        }
        val gx = gesture.x
        val gy = gesture.y
        gesture.x = (gx - originX) / scale
        gesture.y = (gy - originY) / scale
        if (overlay != Overlay.None) {
            if (gesture.kind == GestureKind.TAP) hitTestOverlayButton(gesture.x, gesture.y)
        } else {
            game.onGesture(gesture)
        }
        gesture.x = gx
        gesture.y = gy
    }

    private fun hitTestOverlayButton(x: Float, y: Float) {
        for (i in 0 until buttonCount) {
            val r = buttonRect[i]
            if (x >= r.x && x <= r.x + r.w && y >= r.y && y <= r.y + r.h) {
                performAction(buttonAction[i])
                return
            }
        }
    }

    private fun performAction(action: Int) {
        when (action) {
            ACTION_START_1P -> start(players = 1)
            ACTION_START_2P -> start(players = 2)
            ACTION_RESUME -> resume()
            ACTION_RESTART -> start(players = players)
        }
    }

    private fun layoutButtons() {
        buttonCount = 0
        when (overlay) {
            Overlay.Start -> {
                if (meta.players.last >= 2) {
                    addButton(StringKey.ONE_PLAYER, ACTION_START_1P)
                    addButton(StringKey.TWO_PLAYERS, ACTION_START_2P)
                } else {
                    addButton(StringKey.TAP_TO_START, ACTION_START_1P)
                }
            }
            Overlay.Paused -> {
                addButton(StringKey.RESUME, ACTION_RESUME)
                addButton(StringKey.RESTART, ACTION_RESTART)
            }
            is Overlay.Over -> {
                addButton(StringKey.RESTART, ACTION_RESTART)
            }
            Overlay.None -> Unit
        }

        val btnH = BUTTON_HEIGHT_PT
        val btnW = (width * 0.9f).coerceAtLeast(btnH * 2f)
        val gap = BUTTON_GAP_PT
        val totalH = buttonCount * btnH + (buttonCount - 1).coerceAtLeast(0) * gap
        var y = (height - totalH) / 2f
        val x = (width - btnW) / 2f
        for (i in 0 until buttonCount) {
            buttonRect[i].x = x
            buttonRect[i].y = y
            buttonRect[i].w = btnW
            buttonRect[i].h = btnH
            y += btnH + gap
        }
    }

    private fun addButton(label: StringKey, action: Int) {
        if (buttonCount >= MAX_BUTTONS) return
        buttonLabel[buttonCount] = label
        buttonAction[buttonCount] = action
        buttonCount++
    }

    /** Dark page and a thin dark bezel right around the play zone (view coordinates). */
    private fun renderFrame(scene: Scene) {
        scene.rect(0f, 0f, viewW, viewH, Palette.BEZEL)
        scene.rect(frameX, frameY, frameW, frameH, Palette.BEZEL_EDGE, radius = FRAME_RADIUS_PT)
    }

    /** Faint horizontal CRT scanlines over the whole play zone (view coordinates, fixed spacing on screen). */
    private fun renderScanlines(scene: Scene) {
        var y = SCANLINE_STEP_PT / 2f
        while (y < zoneH) {
            scene.rect(originX, originY + y, zoneW, 1f, Palette.BEZEL, alpha = SCANLINE_ALPHA)
            y += SCANLINE_STEP_PT
        }
    }

    /** The permanent bottom controls: arcade-style Help and Quit buttons (view coordinates). */
    private fun renderQuitBar(scene: Scene) {
        arcadeButton(scene, helpX, helpW, StringKey.HELP, Palette.NEON_CYAN)
        arcadeButton(scene, quitX, quitW, StringKey.QUIT, Palette.NEON_YELLOW)
    }

    private fun arcadeButton(scene: Scene, x: Float, w: Float, label: StringKey, neon: Int) {
        scene.rect(x, barY, w, BAR_HEIGHT_PT, neon, radius = BUTTON_RADIUS_PT)
        scene.rect(x + 2f, barY + 2f, w - 4f, BAR_HEIGHT_PT - 4f, Palette.BEZEL_EDGE, radius = BUTTON_RADIUS_PT - 2f)
        scene.text(x + w / 2f, barY + BAR_HEIGHT_PT / 2f, label, neon, BUTTON_TEXT_SIZE_PT, Align.CENTER)
    }

    private fun renderHud(scene: Scene) {
        val best = scores.best(meta.id)
        scene.text(HUD_MARGIN_PT, HUD_MARGIN_PT, StringKey.SCORE, Palette.FG_DIM, HUD_TEXT_SIZE_PT)
        scene.number(HUD_MARGIN_PT + HUD_LABEL_WIDTH_PT, HUD_MARGIN_PT, game.score, Palette.FG, HUD_TEXT_SIZE_PT)
        scene.text(
            width - HUD_MARGIN_PT - HUD_LABEL_WIDTH_PT * 2,
            HUD_MARGIN_PT,
            StringKey.BEST,
            Palette.FG_DIM,
            HUD_TEXT_SIZE_PT,
        )
        scene.number(
            width - HUD_MARGIN_PT,
            HUD_MARGIN_PT,
            best,
            Palette.FG,
            HUD_TEXT_SIZE_PT,
            Align.RIGHT,
        )
    }

    private fun renderOverlay(scene: Scene) {
        val currentOverlay = overlay
        if (currentOverlay == Overlay.None) return

        scene.rect(0f, 0f, width, height, Palette.SURFACE, alpha = OVERLAY_SCRIM_ALPHA)
        when (currentOverlay) {
            Overlay.Start -> {
                scene.text(width / 2f, height * 0.32f, meta.titleKey, Palette.FG, TITLE_SIZE_PT, Align.CENTER)
            }
            Overlay.Paused -> {
                scene.text(width / 2f, height * 0.32f, StringKey.PAUSED, Palette.FG, TITLE_SIZE_PT, Align.CENTER)
            }
            is Overlay.Over -> {
                scene.text(width / 2f, height * 0.28f, StringKey.GAME_OVER, Palette.FG, TITLE_SIZE_PT, Align.CENTER)
                val winnerKey = when (currentOverlay.winner) {
                    1 -> StringKey.PLAYER_1_WINS
                    2 -> StringKey.PLAYER_2_WINS
                    else -> null
                }
                if (winnerKey != null) {
                    scene.text(width / 2f, height * 0.38f, winnerKey, Palette.FG_DIM, SUBTITLE_SIZE_PT, Align.CENTER)
                }
            }
            Overlay.None -> Unit
        }

        for (i in 0 until buttonCount) {
            val r = buttonRect[i]
            scene.rect(r.x, r.y, r.w, r.h, Palette.ACCENT, radius = BUTTON_RADIUS_PT)
            val labelKey = buttonLabel[i] ?: continue
            scene.text(r.x + r.w / 2f, r.y + r.h / 2f, labelKey, Palette.BG, BUTTON_TEXT_SIZE_PT, Align.CENTER)
        }
    }

    private companion object {
        const val MAX_DT_MS = 50
        const val MAX_BUTTONS = 3
        const val NO_POINTER = -1

        /** The play zone every game sees, in points, whatever the screen. */
        const val LOGICAL_W = 360f
        const val LOGICAL_H = 560f
        const val MIN_SCALE = 0.01f

        const val FRAME_MARGIN_PT = 10f
        const val TOP_MARGIN_PT = 28f
        const val FRAME_BORDER_PT = 5f
        const val FRAME_RADIUS_PT = 8f
        const val BAR_HEIGHT_PT = 52f
        const val BAR_MARGIN_PT = 16f
        const val BAR_MAX_WIDTH_PT = 380f
        const val BAR_GAP_PT = 12f
        const val HELP_SHARE = 0.38f
        const val SCANLINE_STEP_PT = 4f
        const val SCANLINE_ALPHA = 0.07f

        const val ACTION_START_1P = 1
        const val ACTION_START_2P = 2
        const val ACTION_RESUME = 3
        const val ACTION_RESTART = 4

        const val BUTTON_HEIGHT_PT = 64f
        const val BUTTON_GAP_PT = 16f
        const val BUTTON_RADIUS_PT = 12f
        const val BUTTON_TEXT_SIZE_PT = 20f

        const val HUD_MARGIN_PT = 8f
        const val HUD_LABEL_WIDTH_PT = 64f
        const val HUD_TEXT_SIZE_PT = 16f

        const val TITLE_SIZE_PT = 28f
        const val SUBTITLE_SIZE_PT = 18f
        const val OVERLAY_SCRIM_ALPHA = 0.9f
    }
}
