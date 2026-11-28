package lg.core.games.mixup

import lg.core.Complexity
import lg.core.Game
import lg.core.GameMeta
import lg.core.GameStatus
import lg.core.Orientation
import lg.core.Tier
import lg.core.input.Gesture
import lg.core.input.GestureKind
import lg.core.input.PointerPhase
import lg.core.scene.Align
import lg.core.scene.ArcadeIcons
import lg.core.scene.Palette
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey
import lg.core.util.KvCodec
import lg.core.util.Rng

/**
 * Mix-Up ("Méli-mélo"): swap two neighbouring shapes to line up three or more. Thirty levels, each with a limited
 * number of moves and one goal (see [MixUpLevels]). Winning a level opens the next one; the choice screen comes up
 * whenever a game starts. Turn-based: frames run only while the board animates, and input waits for it to settle.
 */
public class MixUpGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    /** Score of the current level. */
    override var score: Int = 0
        private set

    private enum class Phase { IDLE, SWAP, REFUSE, CLEAR, FALL, SHUFFLE }

    private val board = MixUpBoard()
    private var rng = Rng(0)
    private var choosing = true
    private var phase = Phase.IDLE
    private var phaseMs = 0
    private var fallMs = 0

    internal var level = 1
        private set
    internal var movesLeft = 0
        private set

    /** Highest level that can be chosen; one past the last level once everything is won. */
    private var furthestLevel = 1
    private val remaining = IntArray(MixUpBoard.SHAPES)
    private var cascade = 0
    private var swapA = -1
    private var swapB = -1
    private var selected = -1

    private var downCell = -1
    private var downPointer = -1
    private var downX = 0f
    private var downY = 0f

    /** Sounds of the current update ([SoundId.bit] values), and those waiting for the next one. */
    private var sounds = 0
    private var pendingSounds = 0

    private var viewWidth = 360f
    private var viewHeight = 560f
    private var cell = 50f
    private var boardX = 5f

    override val progress: Int get() = furthestLevel

    override fun restoreProgress(value: Int) {
        furthestLevel = maxOf(furthestLevel, value.coerceIn(1, LEVEL_COUNT + 1))
    }

    override val wantsFrames: Boolean
        get() = status == GameStatus.Running && !choosing && phase != Phase.IDLE

    override val canResume: Boolean
        get() = status == GameStatus.Running && !choosing && movesLeft < current.moves && !goalMet()

    override val overActionKey: StringKey?
        get() = if (wonWithNextLevel()) StringKey.NEXT_LEVEL else null

    private val current: Level get() = MixUpLevels.all[level - 1]

    private fun wonWithNextLevel(): Boolean {
        val s = status
        return s is GameStatus.Over && s.winner == GameStatus.SOLO_WIN && level < LEVEL_COUNT
    }

    /** After a win, the next level; after a loss, the same one; otherwise the choice of levels. */
    override fun reset(seed: Long, players: Int) {
        val s = status
        val next = when {
            wonWithNextLevel() -> level + 1
            s is GameStatus.Over && s.winner == GameStatus.SOLO_LOSS -> level
            else -> 0
        }
        rng = Rng(seed)
        status = GameStatus.Running
        if (next == 0) {
            choosing = true
            level = 1
            score = 0
            clearTransient()
        } else {
            startLevel(next)
        }
    }

    internal fun startLevel(number: Int) {
        level = number
        choosing = false
        status = GameStatus.Running
        score = 0
        movesLeft = current.moves
        board.colors = current.colors
        for (i in 0 until MixUpBoard.CELLS) board.ice[i] = current.iceAt(i)
        board.generate(rng)
        remaining.fill(0)
        val wanted = current.collect
        for (k in wanted.indices step 2) remaining[wanted[k]] = wanted[k + 1]
        clearTransient()
    }

    private fun clearTransient() {
        phase = Phase.IDLE
        phaseMs = 0
        cascade = 0
        swapA = -1
        swapB = -1
        selected = -1
        downCell = -1
        downPointer = -1
        sounds = 0
        pendingSounds = 0
        board.marked.fill(false)
        board.fallRows.fill(0)
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        val byWidth = (width - 2 * SIDE_PT) / MixUpBoard.COLS
        val byHeight = (height - BOARD_Y_PT - SIDE_PT) / MixUpBoard.ROWS
        cell = minOf(byWidth, byHeight).coerceAtLeast(1f)
        boardX = (width - cell * MixUpBoard.COLS) / 2f
    }

    private val acceptsMoves: Boolean
        get() = status == GameStatus.Running && !choosing && phase == Phase.IDLE

    private fun cellAt(x: Float, y: Float): Int {
        if (x < boardX || y < BOARD_Y_PT) return -1
        val cx = ((x - boardX) / cell).toInt()
        val cy = ((y - BOARD_Y_PT) / cell).toInt()
        if (cx >= MixUpBoard.COLS || cy >= MixUpBoard.ROWS) return -1
        return cy * MixUpBoard.COLS + cx
    }

    // A drag swaps as soon as the finger has gone far enough; a SWIPE gesture would only give the release point.
    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        when (phase) {
            PointerPhase.DOWN -> {
                // Always replaces what was tracked: the UP of an earlier touch may have gone to a pause screen.
                downCell = if (acceptsMoves) cellAt(x, y) else -1
                downPointer = id
                downX = x
                downY = y
            }
            PointerPhase.MOVE -> {
                if (id != downPointer || downCell < 0 || !acceptsMoves) return
                val dx = x - downX
                val dy = y - downY
                val threshold = cell * DRAG_SHARE
                if (dx * dx + dy * dy < threshold * threshold) return
                val horizontal = dx * dx >= dy * dy
                val col = downCell % MixUpBoard.COLS + if (horizontal) (if (dx > 0) 1 else -1) else 0
                val row = downCell / MixUpBoard.COLS + if (horizontal) 0 else (if (dy > 0) 1 else -1)
                val from = downCell
                downCell = -1
                if (col in 0 until MixUpBoard.COLS && row in 0 until MixUpBoard.ROWS) {
                    trySwap(from, row * MixUpBoard.COLS + col)
                }
            }
            PointerPhase.UP -> {
                if (id != downPointer || downCell < 0) return
                val tapped = downCell
                downCell = -1
                if (!acceptsMoves) return
                when {
                    selected == tapped -> selected = -1
                    selected >= 0 && MixUpBoard.adjacent(selected, tapped) -> trySwap(selected, tapped)
                    else -> selected = tapped
                }
            }
            PointerPhase.CANCEL -> if (id == downPointer) downCell = -1
        }
    }

    /** Starts the swap of two neighbours; one that would line nothing up goes back and costs no move. */
    internal fun trySwap(a: Int, b: Int) {
        if (!acceptsMoves || !MixUpBoard.adjacent(a, b)) return
        selected = -1
        swapA = a
        swapB = b
        phaseMs = 0
        if (board.isLegalSwap(a, b)) {
            movesLeft--
            phase = Phase.SWAP
            pendingSounds = SoundId.TICK.bit
        } else {
            phase = Phase.REFUSE
            pendingSounds = SoundId.HIT.bit
        }
    }

    override fun onGesture(gesture: Gesture) {
        // The board is played through onPointer; gestures only pick a level.
        if (!choosing || status != GameStatus.Running || gesture.kind != GestureKind.TAP) return
        val n = levelAt(gesture.x, gesture.y)
        if (n in 1..minOf(furthestLevel, LEVEL_COUNT)) startLevel(n)
    }

    private fun levelAt(x: Float, y: Float): Int {
        if (x < choiceX() || y < CHOICE_Y_PT) return 0
        val step = CHOICE_TILE_PT + CHOICE_GAP_PT
        val col = ((x - choiceX()) / step).toInt()
        val row = ((y - CHOICE_Y_PT) / step).toInt()
        if (col >= CHOICE_COLS || row >= CHOICE_ROWS) return 0
        if (x - choiceX() - col * step > CHOICE_TILE_PT || y - CHOICE_Y_PT - row * step > CHOICE_TILE_PT) return 0
        return row * CHOICE_COLS + col + 1
    }

    private fun choiceX(): Float = (viewWidth - CHOICE_COLS * CHOICE_TILE_PT - (CHOICE_COLS - 1) * CHOICE_GAP_PT) / 2f

    override fun update(dtMs: Int): Boolean {
        sounds = 0
        if (!wantsFrames) return false
        sounds = pendingSounds
        pendingSounds = 0
        phaseMs += dtMs
        when (phase) {
            Phase.SWAP -> if (phaseMs >= SWAP_MS) finishSwap()
            Phase.REFUSE -> if (phaseMs >= REFUSE_MS) enter(Phase.IDLE)
            Phase.CLEAR -> if (phaseMs >= CLEAR_MS) finishClear()
            Phase.FALL -> if (phaseMs >= fallMs) finishFall()
            Phase.SHUFFLE -> if (phaseMs >= SHUFFLE_MS) enter(Phase.IDLE)
            Phase.IDLE -> Unit
        }
        return true
    }

    private fun enter(next: Phase) {
        phase = next
        phaseMs = 0
    }

    private fun finishSwap() {
        val bomb = board.special[swapA] == MixUpBoard.BOMB || board.special[swapB] == MixUpBoard.BOMB
        board.swap(swapA, swapB)
        if (bomb) board.computeBombSwap(swapA, swapB) else board.computeClear(swapA, swapB)
        cascade = 1
        startClear()
    }

    private fun startClear() {
        var bonus = false
        for (i in 0 until MixUpBoard.CELLS) if (board.marked[i] && board.special[i] != MixUpBoard.NONE) bonus = true
        sounds = sounds or (if (bonus) SoundId.BONUS.bit else SoundId.EAT.bit)
        enter(Phase.CLEAR)
    }

    private fun finishClear() {
        collectClear(board)
        fallMs = board.fall(rng) * FALL_MS_PER_ROW
        // The level counts as won as soon as its goal is met, even if the player leaves during the cascade.
        if (goalMet()) furthestLevel = maxOf(furthestLevel, level + 1)
        enter(Phase.FALL)
    }

    /** Applies the marked clear of [from] to the score and the goal. */
    private fun collectClear(from: MixUpBoard) {
        score += from.applyClear() * POINTS_PER_CELL * cascade
        for (s in 0 until MixUpBoard.SHAPES) remaining[s] = (remaining[s] - from.cleared[s]).coerceAtLeast(0)
    }

    private fun goalMet(): Boolean = when (current.goal) {
        Goal.SCORE -> score >= current.scoreTarget
        Goal.COLLECT -> remaining.all { it == 0 }
        Goal.ICE -> board.iceLeft == 0
    }

    private fun finishFall() {
        board.fallRows.fill(0)
        if (board.computeClear()) {
            cascade++
            startClear()
            return
        }
        swapA = -1
        swapB = -1
        when {
            goalMet() -> {
                status = GameStatus.Over(GameStatus.SOLO_WIN)
                sounds = sounds or SoundId.WIN.bit
                enter(Phase.IDLE)
            }
            movesLeft <= 0 -> {
                status = GameStatus.Over(GameStatus.SOLO_LOSS)
                sounds = sounds or SoundId.LOSE.bit
                enter(Phase.IDLE)
            }
            !board.hasMove() -> {
                board.shuffle(rng)
                enter(Phase.SHUFFLE)
            }
            else -> enter(Phase.IDLE)
        }
    }

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)
        if (choosing) {
            renderLevelChoice(scene)
            return
        }
        renderBoard(scene)
        // Drawn after the board: shapes falling in from above pass under the strip.
        renderStrip(scene)
        if (sounds != 0) {
            for (id in SOUNDS) if (sounds and id.bit != 0) scene.sound(id)
            if (sounds and SoundId.BONUS.bit != 0) scene.haptic()
        }
    }

    private fun renderBoard(scene: Scene) {
        for (i in 0 until MixUpBoard.CELLS) {
            val x = boardX + i % MixUpBoard.COLS * cell
            val y = BOARD_Y_PT + i / MixUpBoard.COLS * cell
            scene.rect(x, y, cell, cell, if (cellShade(i)) Palette.BEZEL_EDGE else Palette.BEZEL)
        }
        if (selected >= 0) {
            val x = boardX + selected % MixUpBoard.COLS * cell
            val y = BOARD_Y_PT + selected / MixUpBoard.COLS * cell
            scene.rect(x, y, cell, cell, Palette.NEON_WHITE, radius = 8f, alpha = 0.35f)
        }
        val t = progressOfPhase()
        for (i in 0 until MixUpBoard.CELLS) {
            if (board.shape[i] == MixUpBoard.EMPTY) continue
            var cx = boardX + (i % MixUpBoard.COLS + 0.5f) * cell
            var cy = BOARD_Y_PT + (i / MixUpBoard.COLS + 0.5f) * cell
            var size = cell * SHAPE_RADIUS
            if ((phase == Phase.SWAP || phase == Phase.REFUSE) && (i == swapA || i == swapB)) {
                val other = if (i == swapA) swapB else swapA
                // A refused swap goes half way and comes back.
                val k = if (phase == Phase.SWAP) t else (if (t < 0.5f) t else 1f - t)
                cx += (other % MixUpBoard.COLS - i % MixUpBoard.COLS) * cell * k
                cy += (other / MixUpBoard.COLS - i / MixUpBoard.COLS) * cell * k
            }
            if (phase == Phase.CLEAR && board.marked[i]) size *= 1f - 0.8f * t
            if (phase == Phase.SHUFFLE) size *= 0.4f + 0.6f * t
            if (phase == Phase.FALL) {
                val left = board.fallRows[i] - phaseMs / FALL_MS_PER_ROW.toFloat()
                if (left > 0f) cy -= left * cell
                if (cy < BOARD_Y_PT - cell * 0.4f) continue
            }
            renderShape(scene, board.shape[i], board.special[i], cx, cy, size)
        }
        for (i in 0 until MixUpBoard.CELLS) {
            if (board.ice[i] == 0) continue
            val x = boardX + i % MixUpBoard.COLS * cell
            val y = BOARD_Y_PT + i / MixUpBoard.COLS * cell
            renderIce(scene, x, y, cell, board.ice[i])
        }
    }

    private fun cellShade(i: Int): Boolean = (i % MixUpBoard.COLS + i / MixUpBoard.COLS) % 2 == 0

    private fun progressOfPhase(): Float {
        val total = when (phase) {
            Phase.SWAP -> SWAP_MS
            Phase.REFUSE -> REFUSE_MS
            Phase.CLEAR -> CLEAR_MS
            Phase.SHUFFLE -> SHUFFLE_MS
            Phase.FALL, Phase.IDLE -> return 0f
        }
        return (phaseMs / total.toFloat()).coerceIn(0f, 1f)
    }

    /** Draws one piece of "radius" [r] centred at ([cx], [cy]): each kind has its own outline and color. */
    private fun renderShape(scene: Scene, shape: Int, special: Int, cx: Float, cy: Float, r: Float) {
        when (shape) {
            0 -> {
                scene.circle(cx, cy, r, Palette.NEON_CYAN)
                scene.circle(cx - r * 0.35f, cy - r * 0.35f, r * 0.2f, Palette.NEON_WHITE)
            }
            1 -> {
                scene.rect(cx - r * 0.85f, cy - r * 0.85f, r * 1.7f, r * 1.7f, Palette.NEON_GREEN, radius = r * 0.25f)
                scene.rect(cx - r * 0.55f, cy - r * 0.55f, r * 0.4f, r * 0.4f, Palette.NEON_WHITE)
            }
            2 -> for (k in 0 until STEPS) {
                // A stepped, pixel-art triangle.
                val w = r * 2.2f * (k + 1) / STEPS
                scene.rect(cx - w / 2f, cy - r + k * r * 2f / STEPS, w, r * 2f / STEPS + 0.5f, Palette.NEON_YELLOW)
            }
            3 -> for (k in 0 until DIAMOND_ROWS) {
                // Widths grow evenly to the middle row and shrink back: a pointed diamond, unlike the plus.
                val w = r * 2.3f * (1 + 2 * minOf(k, DIAMOND_ROWS - 1 - k)) / DIAMOND_ROWS
                val h = r * 2.4f / DIAMOND_ROWS
                scene.rect(cx - w / 2f, cy - r * 1.2f + k * h, w, h + 0.5f, Palette.NEON_PINK)
            }
            4 -> {
                scene.rect(cx - r, cy - r * 0.36f, r * 2f, r * 0.72f, Palette.NEON_ORANGE, radius = r * 0.12f)
                scene.rect(cx - r * 0.36f, cy - r, r * 0.72f, r * 2f, Palette.NEON_ORANGE, radius = r * 0.12f)
            }
            5 -> {
                scene.circle(cx, cy, r, Palette.NEON_PURPLE)
                scene.circle(cx, cy, r * 0.48f, Palette.BEZEL)
            }
            MixUpBoard.BOMB_SHAPE -> {
                scene.circle(cx, cy, r * 1.05f, Palette.NEON_WHITE)
                for (k in 0 until MixUpBoard.SHAPES) {
                    scene.circle(cx + DOT_X[k] * r * 0.6f, cy + DOT_Y[k] * r * 0.6f, r * 0.22f, SHAPE_COLORS[k])
                }
            }
        }
        if (special == MixUpBoard.ROW_CLEARER || special == MixUpBoard.COLUMN_CLEARER) {
            for (k in -1..1) {
                val d = k * r * 0.42f
                if (special == MixUpBoard.ROW_CLEARER) {
                    scene.line(cx - r, cy + d, cx + r, cy + d, Palette.NEON_WHITE, width = r * 0.16f)
                } else {
                    scene.line(cx + d, cy - r, cx + d, cy + r, Palette.NEON_WHITE, width = r * 0.16f)
                }
            }
        }
    }

    private fun renderIce(scene: Scene, x: Float, y: Float, size: Float, layers: Int) {
        val alpha = if (layers >= 2) 0.5f else 0.28f
        scene.rect(x + 1f, y + 1f, size - 2f, size - 2f, Palette.NEON_WHITE, radius = size * 0.12f, alpha = alpha)
        scene.line(x + size * 0.15f, y + size * 0.4f, x + size * 0.4f, y + size * 0.15f, Palette.NEON_WHITE, width = 2f)
        if (layers >= 2) {
            scene.line(x + size * 0.6f, y + size * 0.85f, x + size * 0.85f, y + size * 0.6f, Palette.NEON_WHITE, width = 2f)
        }
    }

    private fun renderStrip(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), BOARD_Y_PT - 2f, Palette.BG)
        scene.text(LEVEL_X_PT, HUD_Y_PT, StringKey.LEVEL, Palette.FG_DIM, LABEL_PT)
        scene.number(LEVEL_X_PT + LEVEL_NUMBER_OFFSET_PT, HUD_Y_PT, level, Palette.FG, LABEL_PT)

        scene.text(MOVES_X_PT, STRIP_LABEL_Y_PT, StringKey.MOVES, Palette.FG_DIM, LABEL_PT, Align.CENTER)
        val color = if (movesLeft <= 3) Palette.DANGER else Palette.FG
        scene.number(MOVES_X_PT, STRIP_VALUE_Y_PT, movesLeft, color, BIG_PT, Align.CENTER)

        val goalX = viewWidth * 0.64f
        scene.text(goalX, STRIP_LABEL_Y_PT, StringKey.GOAL, Palette.FG_DIM, LABEL_PT, Align.CENTER)
        when (current.goal) {
            Goal.SCORE -> {
                scene.number(goalX - 6f, STRIP_VALUE_Y_PT, score, Palette.FG, VALUE_PT, Align.RIGHT)
                scene.label(goalX, STRIP_VALUE_Y_PT, "/", Palette.FG_DIM, VALUE_PT, Align.CENTER)
                scene.number(goalX + 6f, STRIP_VALUE_Y_PT, current.scoreTarget, Palette.NEON_YELLOW, VALUE_PT)
            }
            Goal.COLLECT -> {
                val wanted = current.collect
                val count = wanted.size / 2
                for (k in 0 until count) {
                    val x = goalX + (k - (count - 1) / 2f) * GOAL_STEP_PT
                    val shape = wanted[2 * k]
                    // The icon sits on a dark patch, as on the board.
                    scene.rect(x - 28f, STRIP_VALUE_Y_PT - 13f, 26f, 26f, Palette.BEZEL, radius = 5f)
                    renderShape(scene, shape, MixUpBoard.NONE, x - 15f, STRIP_VALUE_Y_PT, 9f)
                    renderCount(scene, x + 2f, remaining[shape])
                }
            }
            Goal.ICE -> {
                scene.rect(goalX - 28f, STRIP_VALUE_Y_PT - 13f, 26f, 26f, Palette.BEZEL, radius = 5f)
                renderIce(scene, goalX - 28f, STRIP_VALUE_Y_PT - 13f, 26f, 2)
                renderCount(scene, goalX + 2f, board.iceLeft)
            }
        }
    }

    /** What is still wanted, or a tick once there is nothing left. */
    private fun renderCount(scene: Scene, x: Float, left: Int) {
        if (left > 0) {
            scene.number(x, STRIP_VALUE_Y_PT, left, Palette.FG, VALUE_PT)
        } else {
            scene.line(x, STRIP_VALUE_Y_PT, x + 6f, STRIP_VALUE_Y_PT + 6f, Palette.OK, width = 4f)
            scene.line(x + 6f, STRIP_VALUE_Y_PT + 6f, x + 18f, STRIP_VALUE_Y_PT - 8f, Palette.OK, width = 4f)
        }
    }

    private fun renderLevelChoice(scene: Scene) {
        scene.text(viewWidth / 2f, CHOICE_TITLE_Y_PT, StringKey.LEVEL, Palette.FG, BIG_PT, Align.CENTER)
        val reachable = minOf(furthestLevel, LEVEL_COUNT)
        for (n in 1..LEVEL_COUNT) {
            val x = choiceX() + (n - 1) % CHOICE_COLS * (CHOICE_TILE_PT + CHOICE_GAP_PT)
            val y = CHOICE_Y_PT + (n - 1) / CHOICE_COLS * (CHOICE_TILE_PT + CHOICE_GAP_PT)
            val color = when {
                n > reachable -> Palette.GRID
                n < furthestLevel -> Palette.OK
                else -> Palette.ACCENT
            }
            scene.rect(x, y, CHOICE_TILE_PT, CHOICE_TILE_PT, color, radius = 8f)
            if (n > reachable) {
                scene.number(x + CHOICE_TILE_PT / 2f, y + 13f, n, Palette.FG_DIM, LABEL_PT, Align.CENTER)
                // A small padlock under the number.
                val cx = x + CHOICE_TILE_PT / 2f
                val top = y + 34f
                scene.arc(cx, top, 7.5f, 180f, 180f, Palette.FG_DIM)
                scene.arc(cx, top, 4.5f, 180f, 180f, color)
                scene.rect(cx - 10f, top, 20f, 14f, Palette.FG_DIM, radius = 3f)
                scene.circle(cx, top + 6f, 1.8f, color)
            } else {
                scene.number(x + CHOICE_TILE_PT / 2f, y + CHOICE_TILE_PT / 2f, n, Palette.BG, VALUE_PT, Align.CENTER)
            }
        }
    }

    // --- Saving ----------------------------------------------------------------

    /**
     * The level as it will be once the board has settled: an animation under way is played out on a copy, so that a
     * save never holds a half-finished step. `null` if the level is over by then.
     */
    override fun snapshot(): String? {
        if (choosing || status != GameStatus.Running) return null
        val settled = MixUpGame()
        settled.copyFrom(this)
        var guard = 0
        while (settled.wantsFrames && guard++ < MAX_SETTLE_STEPS) settled.update(SETTLE_STEP_MS)
        if (settled.status != GameStatus.Running) return null
        return KvCodec.encode(settled.encode())
    }

    private fun copyFrom(other: MixUpGame) {
        board.copyFrom(other.board)
        rng = Rng.fromState(other.rng.state)
        choosing = other.choosing
        status = other.status
        level = other.level
        movesLeft = other.movesLeft
        score = other.score
        other.remaining.copyInto(remaining)
        phase = other.phase
        phaseMs = other.phaseMs
        fallMs = other.fallMs
        cascade = other.cascade
        swapA = other.swapA
        swapB = other.swapB
    }

    private fun encode(): Map<String, String> {
        val map = LinkedHashMap<String, String>(8)
        map["level"] = level.toString()
        map["moves"] = movesLeft.toString()
        map["score"] = score.toString()
        map["shapes"] = digits(board.shape)
        map["special"] = digits(board.special)
        map["ice"] = digits(board.ice)
        map["wanted"] = remaining.joinToString(",")
        map["rng"] = rng.state.toString()
        return map
    }

    private fun digits(values: IntArray): String = CharArray(values.size) { '0' + values[it] }.concatToString()

    override fun restore(data: String): Boolean {
        val map = KvCodec.decode(data)
        val newLevel = map["level"]?.toIntOrNull() ?: return false
        if (newLevel !in 1..LEVEL_COUNT) return false
        val rules = MixUpLevels.all[newLevel - 1]
        val newMoves = map["moves"]?.toIntOrNull() ?: return false
        if (newMoves !in 1..rules.moves) return false
        val newScore = map["score"]?.toIntOrNull() ?: return false
        if (newScore < 0) return false
        val shapes = map["shapes"] ?: return false
        val specials = map["special"] ?: return false
        val ices = map["ice"] ?: return false
        if (shapes.length != MixUpBoard.CELLS || specials.length != MixUpBoard.CELLS || ices.length != MixUpBoard.CELLS) {
            return false
        }
        for (i in 0 until MixUpBoard.CELLS) {
            val s = shapes[i] - '0'
            val p = specials[i] - '0'
            if (p !in MixUpBoard.NONE..MixUpBoard.BOMB) return false
            if (if (p == MixUpBoard.BOMB) s != MixUpBoard.BOMB_SHAPE else s !in 0 until rules.colors) return false
            if (ices[i] - '0' !in 0..2) return false
        }
        val wanted = map["wanted"]?.split(",")?.map { it.toIntOrNull() ?: return false } ?: return false
        if (wanted.size != MixUpBoard.SHAPES || wanted.any { it < 0 }) return false
        val rngState = map["rng"]?.toLongOrNull() ?: return false

        level = newLevel
        movesLeft = newMoves
        score = newScore
        board.colors = rules.colors
        for (i in 0 until MixUpBoard.CELLS) {
            board.shape[i] = shapes[i] - '0'
            board.special[i] = specials[i] - '0'
            board.ice[i] = ices[i] - '0'
        }
        for (s in 0 until MixUpBoard.SHAPES) remaining[s] = wanted[s]
        rng = Rng.fromState(rngState)
        choosing = false
        status = GameStatus.Running
        furthestLevel = maxOf(furthestLevel, newLevel)
        clearTransient()
        return true
    }

    // --- Test access ------------------------------------------------------------

    internal val testBoard: MixUpBoard get() = board
    internal val isSettled: Boolean get() = phase == Phase.IDLE
    internal val isChoosing: Boolean get() = choosing

    public companion object {
        internal const val LEVEL_COUNT = 30
        private const val POINTS_PER_CELL = 10

        private const val SWAP_MS = 140
        private const val REFUSE_MS = 220
        private const val CLEAR_MS = 180
        private const val FALL_MS_PER_ROW = 55
        private const val SHUFFLE_MS = 450
        private const val SETTLE_STEP_MS = 1000
        private const val MAX_SETTLE_STEPS = 500
        private const val DRAG_SHARE = 0.4f

        private const val SIDE_PT = 5f
        private const val BOARD_Y_PT = 100f
        private const val SHAPE_RADIUS = 0.34f
        private const val STEPS = 5
        private const val DIAMOND_ROWS = 9

        private const val HUD_Y_PT = 8f
        private const val LEVEL_X_PT = 132f
        private const val LEVEL_NUMBER_OFFSET_PT = 62f
        private const val STRIP_LABEL_Y_PT = 40f
        private const val STRIP_VALUE_Y_PT = 70f
        private const val MOVES_X_PT = 62f
        private const val GOAL_STEP_PT = 74f
        private const val LABEL_PT = 16f
        private const val VALUE_PT = 22f
        private const val BIG_PT = 28f

        private const val CHOICE_COLS = 5
        private const val CHOICE_ROWS = 6
        private const val CHOICE_TILE_PT = 56f
        private const val CHOICE_GAP_PT = 12f
        private const val CHOICE_TITLE_Y_PT = 70f
        private const val CHOICE_Y_PT = 120f

        private val SOUNDS = SoundId.entries.toTypedArray()
        private val DOT_X = floatArrayOf(1f, 0.5f, -0.5f, -1f, -0.5f, 0.5f)
        private val DOT_Y = floatArrayOf(0f, 0.87f, 0.87f, 0f, -0.87f, -0.87f)
        private val SHAPE_COLORS = intArrayOf(
            Palette.NEON_CYAN, Palette.NEON_GREEN, Palette.NEON_YELLOW,
            Palette.NEON_PINK, Palette.NEON_ORANGE, Palette.NEON_PURPLE,
        )

        public val META: GameMeta = GameMeta(
            id = "mixup",
            titleKey = StringKey.TITLE_MIXUP,
            tier = Tier.UNLOCKED,
            players = 1..1,
            orientation = Orientation.PORTRAIT,
            complexity = Complexity.M,
            resumable = true,
            factory = { MixUpGame() },
            icon = { scene, size -> ArcadeIcons.mixUp(scene, size) },
        )
    }
}
