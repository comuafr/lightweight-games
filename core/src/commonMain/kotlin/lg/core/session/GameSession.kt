package lg.core.session

import lg.core.Game
import lg.core.GameMeta
import lg.core.GameStatus
import lg.core.Orientation
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

    /**
     * Called when a finished game earned place `rank` (0 = grand champion) in its high-score table: the platform asks
     * the player for three initials and answers with [submitInitials]. Without a handler the score is entered at once
     * under the last initials used (or `---`).
     */
    public var onEnterInitials: ((score: Int, rank: Int) -> Unit)? = null

    /** True while the platform is asked for the initials of a new high score. */
    public val isAwaitingInitials: Boolean
        get() = overlay is Overlay.NewHighScore

    // Full view size (screen dp). The game and the overlays always work in a fixed
    // logical play zone of `width` x `height` ([LOGICAL_SHORT] x [LOGICAL_LONG], swapped for a landscape game) so that
    // every player gets the same game whatever the screen; the session scales that
    // zone uniformly by `scale` and draws it at `originX`/`originY` in the view.
    private var viewW: Float = 0f
    private var viewH: Float = 0f
    private var safeTop: Float = 0f
    private val landscape: Boolean = meta.orientation == Orientation.LANDSCAPE
    private var width: Float = if (landscape) LOGICAL_LONG else LOGICAL_SHORT
    private var height: Float = if (landscape) LOGICAL_SHORT else LOGICAL_LONG
    private var scale: Float = 1f
    private var zoneW: Float = 0f
    private var zoneH: Float = 0f
    private var originX: Float = 0f
    private var originY: Float = 0f
    private var frameX: Float = 0f
    private var frameY: Float = 0f
    private var frameW: Float = 0f
    private var frameH: Float = 0f
    /** Where the controls start: top edge of the bottom bar (portrait) or left edge of the side column (landscape). */
    private var controlsEdge: Float = 0f
    private var helpX: Float = 0f
    private var helpY: Float = 0f
    private var helpW: Float = 0f
    private var quitX: Float = 0f
    private var quitY: Float = 0f
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

    private var savedProgress: Int = scores.progress(meta.id)

    // The high-score table of a game that has one (see [GameMeta.defaultScores]): loaded into fixed arrays at the
    // start and after each insertion, so drawing it never allocates.
    private val defaultScores: IntArray? = meta.defaultScores
    private val tableNames = Array(Scores.TABLE_SIZE) { Scores.NO_NAME }
    private val tableScores = IntArray(Scores.TABLE_SIZE)
    private val tableIsDefault = BooleanArray(Scores.TABLE_SIZE)
    private var tableLines = 0

    /** Line of the table that was just earned by the player, or -1. */
    private var highlightRank = -1

    init {
        if (defaultScores != null) {
            scores.migrateLegacy(meta.id)
            loadTable()
        }
        game.restoreProgress(savedProgress)
        // The game's play zone has a fixed size, known from the start.
        game.resize(width, height)
        layoutButtons()
    }

    private fun loadTable() {
        val defaults = defaultScores ?: return
        val lines = scores.table(meta.id, defaults)
        tableLines = lines.size
        for (i in lines.indices) {
            tableNames[i] = lines[i].name
            tableScores[i] = lines[i].score
            tableIsDefault[i] = lines[i].isDefault
        }
    }

    /**
     * Gives the initials of the player who just earned a place in the high-score table ([onEnterInitials]); [name]
     * is cleaned to three letters A-Z, and `null` or blank means the last initials used (or `---`). Enters the score
     * and shows the game over screen with the table. No-op when no initials are awaited.
     */
    public fun submitInitials(name: String?) {
        val current = overlay as? Overlay.NewHighScore ?: return
        val defaults = defaultScores ?: return
        val chosen = if (name.isNullOrBlank()) scores.lastInitials().ifEmpty { Scores.NO_NAME } else name
        val cleaned = Scores.sanitizeName(chosen)
        highlightRank = scores.insert(meta.id, defaults, cleaned, current.score)
        if (cleaned != Scores.NO_NAME) scores.saveLastInitials(cleaned)
        loadTable()
        overlay = Overlay.Over(0)
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
        highlightRank = -1
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
        if (landscape) layoutLandscape() else layoutPortrait()
        scale = minOf(availW / width, availH / height).coerceAtLeast(MIN_SCALE)
        zoneW = width * scale
        zoneH = height * scale
        originX = availX + (availW - zoneW) / 2f
        originY = availTop + (availH - zoneH) / 2f
        frameX = originX - FRAME_BORDER_PT
        frameY = originY - FRAME_BORDER_PT
        frameW = zoneW + 2 * FRAME_BORDER_PT
        frameH = zoneH + 2 * FRAME_BORDER_PT
    }

    // Room available for the play zone, set by the two layouts below.
    private var availX: Float = 0f
    private var availW: Float = 0f
    private var availTop: Float = 0f
    private var availH: Float = 0f

    /** Portrait: the zone above a bar holding Help and Quit side by side. */
    private fun layoutPortrait() {
        controlsEdge = (viewH - BAR_HEIGHT_PT - BAR_MARGIN_PT).coerceAtLeast(0f)
        availX = FRAME_MARGIN_PT + FRAME_BORDER_PT
        availW = (viewW - 2 * availX).coerceAtLeast(0f)
        availTop = maxOf(safeTop, 0f) + TOP_MARGIN_PT + FRAME_BORDER_PT
        availH = (controlsEdge - FRAME_MARGIN_PT - FRAME_BORDER_PT - availTop).coerceAtLeast(0f)
        val barW = (viewW - 2 * FRAME_MARGIN_PT).coerceIn(0f, BAR_MAX_WIDTH_PT)
        helpW = barW * HELP_SHARE
        quitW = barW - helpW - BAR_GAP_PT
        helpX = (viewW - barW) / 2f
        quitX = helpX + helpW + BAR_GAP_PT
        helpY = controlsEdge
        quitY = controlsEdge
    }

    /** Landscape: the zone on the left, Help and Quit stacked in a column on the right. */
    private fun layoutLandscape() {
        controlsEdge = (viewW - LANDSCAPE_MARGIN_PT - SIDE_COLUMN_WIDTH_PT).coerceAtLeast(0f)
        availX = LANDSCAPE_MARGIN_PT + FRAME_BORDER_PT
        availW = (controlsEdge - FRAME_MARGIN_PT - FRAME_BORDER_PT - availX).coerceAtLeast(0f)
        availTop = maxOf(safeTop, 0f) + FRAME_MARGIN_PT + FRAME_BORDER_PT
        availH = (viewH - availTop - FRAME_MARGIN_PT - FRAME_BORDER_PT).coerceAtLeast(0f)
        helpW = SIDE_COLUMN_WIDTH_PT
        quitW = SIDE_COLUMN_WIDTH_PT
        helpX = controlsEdge
        quitX = controlsEdge
        val stackH = 2 * BAR_HEIGHT_PT + BAR_GAP_PT
        helpY = ((viewH - stackH) / 2f).coerceAtLeast(0f)
        quitY = helpY + BAR_HEIGHT_PT + BAR_GAP_PT
    }

    /** True when a touch at ([x], [y]) is on the Help/Quit controls rather than on the game. */
    private fun inControls(x: Float, y: Float): Boolean = if (landscape) x >= controlsEdge else y >= controlsEdge

    /** Routes a raw pointer event to the overlay (via gesture detection) or the game. */
    public fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        // A touch that starts on the Quit bar never reaches the game.
        if (phase == PointerPhase.DOWN && inControls(x, y)) ignoredPointer = id
        if (id != ignoredPointer && overlay == Overlay.None) {
            game.onPointer(id, phase, (x - originX) / scale, (y - originY) / scale)
            checkOver()
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
        if (checkOver()) return true
        return changed
    }

    /**
     * Notices a game that has just ended and shows the end overlay. Called after every update and after
     * every input handed to the game: a last move made by a tap ends a turn-based game without any
     * frame loop running, so waiting for the next [update] would never show the result.
     * @return true if the game over overlay was just raised.
     */
    private fun checkOver(): Boolean {
        saveProgressIfAny()
        val status = game.status
        if (status is GameStatus.Over && overlay == Overlay.None) {
            val defaults = defaultScores
            val finalScore = game.score
            // Ask before submit(): the table's own bookkeeping must not see the new best as an old one.
            val earned = defaults != null && scores.qualifies(meta.id, defaults, finalScore)
            scores.submit(meta.id, finalScore)
            eventsArmed = true
            if (earned) {
                val rank = scores.table(meta.id, defaults).count { it.score >= finalScore }
                overlay = Overlay.NewHighScore(rank, finalScore)
                layoutButtons()
                val handler = onEnterInitials
                if (handler != null) handler(finalScore, rank) else submitInitials(null)
            } else {
                overlay = Overlay.Over(status.winner)
                layoutButtons()
            }
            return true
        }
        return false
    }

    private fun saveProgressIfAny() {
        val p = game.progress
        if (p > savedProgress) {
            savedProgress = p
            scores.saveProgress(meta.id, p)
        }
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
        if (inControls(gesture.x, gesture.y)) {
            if (gesture.kind == GestureKind.TAP) {
                if (hits(gesture.x, gesture.y, quitX, quitY, quitW)) {
                    onQuit?.invoke()
                } else if (hits(gesture.x, gesture.y, helpX, helpY, helpW)) {
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
            checkOver()
        }
        gesture.x = gx
        gesture.y = gy
    }

    private fun hits(x: Float, y: Float, bx: Float, by: Float, bw: Float): Boolean =
        x >= bx && x <= bx + bw && y >= by && y <= by + BAR_HEIGHT_PT

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
            is Overlay.NewHighScore, Overlay.None -> Unit
        }

        val btnH = BUTTON_HEIGHT_PT
        val btnW = (width * 0.9f).coerceAtLeast(btnH * 2f)
        val gap = BUTTON_GAP_PT
        val totalH = buttonCount * btnH + (buttonCount - 1).coerceAtLeast(0) * gap
        // With a high-score table on the screen the buttons sit at the bottom, below the table.
        var y = if (showsTable(overlay)) height - BUTTON_BOTTOM_MARGIN_PT - totalH else (height - totalH) / 2f
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
        arcadeButton(scene, helpX, helpY, helpW, StringKey.HELP, Palette.NEON_CYAN)
        arcadeButton(scene, quitX, quitY, quitW, StringKey.QUIT, Palette.NEON_YELLOW)
    }

    private fun arcadeButton(scene: Scene, x: Float, y: Float, w: Float, label: StringKey, neon: Int) {
        scene.rect(x, y, w, BAR_HEIGHT_PT, neon, radius = BUTTON_RADIUS_PT)
        scene.rect(x + 2f, y + 2f, w - 4f, BAR_HEIGHT_PT - 4f, Palette.BEZEL_EDGE, radius = BUTTON_RADIUS_PT - 2f)
        scene.text(x + w / 2f, y + BAR_HEIGHT_PT / 2f, label, neon, BUTTON_TEXT_SIZE_PT, Align.CENTER)
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
        if (showsTable(currentOverlay)) {
            renderTableOverlay(scene, currentOverlay)
            return
        }
        when (currentOverlay) {
            Overlay.Start -> {
                scene.text(width / 2f, height * 0.32f, meta.titleKey, Palette.FG, TITLE_SIZE_PT, Align.CENTER)
            }
            Overlay.Paused -> {
                scene.text(width / 2f, height * 0.32f, StringKey.PAUSED, Palette.FG, TITLE_SIZE_PT, Align.CENTER)
            }
            is Overlay.Over -> {
                if (currentOverlay.winner == GameStatus.DRAW) {
                    scene.text(width / 2f, height * 0.28f, StringKey.DRAW, Palette.FG, TITLE_SIZE_PT, Align.CENTER)
                } else {
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
            }
            is Overlay.NewHighScore, Overlay.None -> Unit
        }
        renderButtons(scene)
    }

    private fun renderButtons(scene: Scene) {
        for (i in 0 until buttonCount) {
            val r = buttonRect[i]
            scene.rect(r.x, r.y, r.w, r.h, Palette.ACCENT, radius = BUTTON_RADIUS_PT)
            val labelKey = buttonLabel[i] ?: continue
            scene.text(r.x + r.w / 2f, r.y + r.h / 2f, labelKey, Palette.BG, BUTTON_TEXT_SIZE_PT, Align.CENTER)
        }
    }

    /** Whether [overlay] is drawn together with the game's high-score table (start and end screens of such a game). */
    private fun showsTable(overlay: Overlay): Boolean =
        defaultScores != null && (overlay === Overlay.Start || overlay is Overlay.Over || overlay is Overlay.NewHighScore)

    /** Start or end screen of a game with a high-score table: a title, then the table, then the buttons. */
    private fun renderTableOverlay(scene: Scene, overlay: Overlay) {
        val cx = width / 2f
        if (overlay === Overlay.Start) {
            scene.text(cx, TABLE_TITLE_Y_PT, meta.titleKey, Palette.FG, TITLE_SIZE_PT, Align.CENTER)
        } else {
            scene.text(cx, TABLE_TITLE_Y_PT, StringKey.GAME_OVER, Palette.FG, TITLE_SIZE_PT, Align.CENTER)
            if (overlay is Overlay.NewHighScore || highlightRank >= 0) {
                scene.text(cx, TABLE_SUBTITLE_Y_PT, StringKey.NEW_HIGH_SCORE, Palette.NEON_YELLOW, SUBTITLE_SIZE_PT, Align.CENTER)
            }
        }

        scene.text(cx, TABLE_HEADER_Y_PT, StringKey.HIGH_SCORES, Palette.NEON_CYAN, TABLE_HEADER_SIZE_PT, Align.CENTER)
        val left = TABLE_SIDE_MARGIN_PT
        val right = width - TABLE_SIDE_MARGIN_PT
        for (i in 0 until tableLines) {
            val color = lineColor(i)
            if (i == 0) {
                scene.text(cx, TABLE_CHAMPION_LABEL_Y_PT, StringKey.GRAND_CHAMPION, Palette.NEON_PINK, TABLE_LABEL_SIZE_PT, Align.CENTER)
                scene.label(left, TABLE_CHAMPION_Y_PT, tableNames[0], color, TABLE_CHAMPION_SIZE_PT, Align.LEFT)
                scene.number(right, TABLE_CHAMPION_Y_PT, tableScores[0], color, TABLE_CHAMPION_SIZE_PT, Align.RIGHT)
            } else {
                val y = TABLE_ROWS_Y_PT + (i - 1) * TABLE_ROW_STEP_PT
                // The grand champion stands apart; the four runners-up are numbered 1 to 4, flipper style.
                scene.number(left + TABLE_RANK_WIDTH_PT, y, i, color, TABLE_ROW_SIZE_PT, Align.RIGHT)
                scene.label(left + TABLE_RANK_WIDTH_PT + TABLE_NAME_GAP_PT, y, tableNames[i], color, TABLE_ROW_SIZE_PT, Align.LEFT)
                scene.number(right, y, tableScores[i], color, TABLE_ROW_SIZE_PT, Align.RIGHT)
            }
        }
        renderButtons(scene)
    }

    /** Yellow for the line just earned, green for the player's other scores, dim for the built-in ones. */
    private fun lineColor(line: Int): Int = when {
        line == highlightRank -> Palette.NEON_YELLOW
        tableIsDefault[line] -> Palette.FG_DIM
        else -> Palette.NEON_GREEN
    }

    private companion object {
        const val MAX_DT_MS = 50
        const val MAX_BUTTONS = 3
        const val NO_POINTER = -1

        /**
         * The play zone every game sees, in points, whatever the screen: 360 x 560 in portrait,
         * 560 x 360 for a landscape game ([lg.core.Orientation.LANDSCAPE]).
         */
        const val LOGICAL_SHORT = 360f
        const val LOGICAL_LONG = 560f
        const val SIDE_COLUMN_WIDTH_PT = 104f
        const val LANDSCAPE_MARGIN_PT = 36f
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

        // High-score table layout, in zone points.
        const val BUTTON_BOTTOM_MARGIN_PT = 24f
        const val TABLE_TITLE_Y_PT = 64f
        const val TABLE_SUBTITLE_Y_PT = 98f
        const val TABLE_HEADER_Y_PT = 152f
        const val TABLE_HEADER_SIZE_PT = 34f
        const val TABLE_CHAMPION_LABEL_Y_PT = 206f
        const val TABLE_CHAMPION_Y_PT = 246f
        const val TABLE_ROWS_Y_PT = 304f
        const val TABLE_ROW_STEP_PT = 40f
        const val TABLE_SIDE_MARGIN_PT = 56f
        const val TABLE_RANK_WIDTH_PT = 22f
        const val TABLE_NAME_GAP_PT = 18f
        const val TABLE_LABEL_SIZE_PT = 14f
        const val TABLE_CHAMPION_SIZE_PT = 40f
        const val TABLE_ROW_SIZE_PT = 24f

        const val TITLE_SIZE_PT = 28f
        const val SUBTITLE_SIZE_PT = 18f
        const val OVERLAY_SCRIM_ALPHA = 0.9f
    }
}
