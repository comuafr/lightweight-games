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

    private var width: Float = 0f
    private var height: Float = 0f
    private var players: Int = 1

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
        layoutButtons()
    }

    /** Starts (or restarts) the game with [players] participants and a deterministic [seed]. */
    public fun start(players: Int = 1, seed: Long = clock.nowMs()) {
        this.players = players.coerceIn(meta.players.first, meta.players.last)
        game.reset(seed, this.players)
        overlay = Overlay.None
        eventsArmed = true
        layoutButtons()
    }

    /** Notifies the session (and the game, if playing) of a size change; never resets state. */
    public fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
        game.resize(width, height)
        layoutButtons()
    }

    /** Routes a raw pointer event to the overlay (via gesture detection) or the game. */
    public fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        if (overlay == Overlay.None) {
            game.onPointer(id, phase, x, y)
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
        if (overlay != Overlay.None) {
            if (gesture.kind == GestureKind.TAP) hitTestOverlayButton(gesture.x, gesture.y)
        } else {
            game.onGesture(gesture)
        }
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
        val btnW = (width * 0.6f).coerceAtLeast(btnH * 2f)
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
