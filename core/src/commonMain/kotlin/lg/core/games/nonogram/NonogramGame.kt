package lg.core.games.nonogram

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
import lg.core.scene.Palette
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey
import lg.core.util.KvCodec
import lg.core.util.Rng

/**
 * Nonogram (picture logic puzzle): fill cells matching hidden row/column
 * clues. Sizes are 5x5 (EASY), 10x10 (MEDIUM) or 15x15 (HARD), chosen via
 * the same in-game "choosing" screen pattern as `SudokuGame` (see its KDoc)
 * since `GameSession` only calls [reset] once, on start.
 *
 * ### Data layout
 *
 * Every array is preallocated at [NonogramGenerator.MAX_SIZE] (15) capacity
 * regardless of the chosen difficulty, so choosing a difficulty never
 * allocates a differently-sized array — only the first `size`/`size*size`
 * entries are ever read once a puzzle is generated. [cellState] holds
 * [STATE_BLANK]/[STATE_FILLED]/[STATE_CROSSED] per cell.
 *
 * ### Input
 *
 * Fill-toggling (including "paint the same state while dragging across
 * cells") is handled entirely via raw [onPointer]: `DOWN` on a cell decides
 * the value for this whole drag (the opposite of that cell's current filled
 * state, exactly like a plain tap when there's no movement), and every
 * `MOVE` into a *new* cell paints that same value. [onGesture] is only used
 * for `LONG_PRESS`, which force-sets a cell to [STATE_CROSSED] (or clears
 * it back to blank if already crossed) — this can override whatever the
 * initial `DOWN` painted for that cell, which is fine: a long press is a
 * deliberate, distinct action.
 *
 * Winning ignores crosses: `current == picture` cell-by-cell using only
 * "is it filled".
 */
public class NonogramGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    override var score: Int = 0
        private set

    override val wantsFrames: Boolean = false

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private var gridOriginX: Float = 0f
    private var gridOriginY: Float = 0f
    private var cellSize: Float = 0f
    private var clueMargin: Float = 0f

    private var rng: Rng = Rng(0L)
    private var choosing: Boolean = true
    private var difficulty: NonogramGenerator.Difficulty = NonogramGenerator.Difficulty.EASY
    private var size: Int = 0

    private val picture = BooleanArray(MAX_CELLS)
    private val cellState = IntArray(MAX_CELLS)
    private val rowClueCount = IntArray(MAX_SIZE)
    private val rowClues = IntArray(MAX_SIZE * MAX_CLUES)
    private val colClueCount = IntArray(MAX_SIZE)
    private val colClues = IntArray(MAX_SIZE * MAX_CLUES)

    private var paintValue: Int = -1
    private var paintPointerId: Int = NO_POINTER
    private var lastPaintedIdx: Int = -1

    // Events from the most recent action, read (not mutated) by render().
    private var paintedThisMove: Boolean = false
    private var wonThisMove: Boolean = false

    override fun reset(seed: Long, players: Int) {
        rng = Rng(seed)
        choosing = true
        size = 0
        picture.fill(false)
        cellState.fill(STATE_BLANK)
        rowClueCount.fill(0)
        colClueCount.fill(0)
        paintValue = -1
        paintPointerId = NO_POINTER
        lastPaintedIdx = -1
        score = 0
        paintedThisMove = false
        wonThisMove = false
        status = GameStatus.Running
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        layoutGrid()
    }

    private fun layoutGrid() {
        if (size <= 0) return
        val side = minOf(viewWidth, (viewHeight - HUD_TOP_PT).coerceAtLeast(0f))
        clueMargin = side * CLUE_MARGIN_FRACTION
        val gridSide = (side - clueMargin).coerceAtLeast(1f)
        cellSize = gridSide / size
        val originX = (viewWidth - side) / 2f
        gridOriginX = originX + clueMargin
        gridOriginY = HUD_TOP_PT + clueMargin
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        if (status != GameStatus.Running || choosing) return
        val cellIdx = cellAt(x, y)
        when (phase) {
            PointerPhase.DOWN -> {
                if (cellIdx == null) return
                paintPointerId = id
                lastPaintedIdx = -1
                paintValue = if (cellState[cellIdx] == STATE_FILLED) STATE_BLANK else STATE_FILLED
                applyPaint(cellIdx)
            }
            PointerPhase.MOVE -> {
                if (id != paintPointerId || cellIdx == null) return
                applyPaint(cellIdx)
            }
            PointerPhase.UP, PointerPhase.CANCEL -> {
                if (id == paintPointerId) {
                    paintPointerId = NO_POINTER
                    paintValue = -1
                    lastPaintedIdx = -1
                }
            }
        }
    }

    private fun applyPaint(cellIdx: Int) {
        if (cellIdx == lastPaintedIdx) return
        lastPaintedIdx = cellIdx
        if (cellState[cellIdx] == paintValue) return
        cellState[cellIdx] = paintValue
        paintedThisMove = true
        score = computeScore()
        checkWin()
    }

    override fun onGesture(gesture: Gesture) {
        paintedThisMove = false
        wonThisMove = false
        if (status != GameStatus.Running) return

        if (choosing) {
            if (gesture.kind != GestureKind.TAP) return
            val idx = difficultyButtonAt(gesture.x, gesture.y)
            if (idx >= 0) startWithDifficulty(NonogramGenerator.Difficulty.entries[idx])
            return
        }

        if (gesture.kind != GestureKind.LONG_PRESS) return
        val cellIdx = cellAt(gesture.x, gesture.y) ?: return
        toggleCross(cellIdx)
    }

    private fun toggleCross(cellIdx: Int) {
        cellState[cellIdx] = if (cellState[cellIdx] == STATE_CROSSED) STATE_BLANK else STATE_CROSSED
        paintedThisMove = true
        score = computeScore()
        checkWin()
    }

    override fun update(dtMs: Int): Boolean {
        paintedThisMove = false
        wonThisMove = false
        return false
    }

    private fun startWithDifficulty(d: NonogramGenerator.Difficulty) {
        difficulty = d
        size = d.size
        val puzzle = NonogramGenerator.generate(rng, d)
        for (i in 0 until size * size) {
            picture[i] = puzzle.picture[i]
            cellState[i] = STATE_BLANK
        }
        for (i in 0 until size) {
            rowClueCount[i] = puzzle.rowClueCount[i]
            colClueCount[i] = puzzle.colClueCount[i]
            for (k in 0 until MAX_CLUES) {
                rowClues[i * MAX_CLUES + k] = puzzle.rowClues[i * MAX_CLUES + k]
                colClues[i * MAX_CLUES + k] = puzzle.colClues[i * MAX_CLUES + k]
            }
        }
        choosing = false
        score = 0
        layoutGrid()
    }

    private fun computeScore(): Int {
        var n = 0
        for (i in 0 until size * size) if (cellState[i] == STATE_FILLED && picture[i]) n++
        return n
    }

    private fun checkWin() {
        for (i in 0 until size * size) {
            if ((cellState[i] == STATE_FILLED) != picture[i]) return
        }
        status = GameStatus.Over(0)
        wonThisMove = true
    }

    // --- Hit testing --------------------------------------------------------

    private fun difficultyButtonAt(x: Float, y: Float): Int {
        for (i in 0 until 3) {
            val by = buttonY(i)
            if (x >= buttonX() && x <= buttonX() + buttonW() && y >= by && y <= by + BUTTON_HEIGHT_PT) return i
        }
        return -1
    }

    private fun buttonX(): Float = (viewWidth - buttonW()) / 2f
    private fun buttonW(): Float = (viewWidth * 0.7f).coerceAtLeast(BUTTON_HEIGHT_PT * 2f)
    private fun buttonY(i: Int): Float {
        val totalH = 3 * BUTTON_HEIGHT_PT + 2 * BUTTON_GAP_PT
        val top = (viewHeight - totalH) / 2f
        return top + i * (BUTTON_HEIGHT_PT + BUTTON_GAP_PT)
    }

    private fun cellAt(x: Float, y: Float): Int? {
        if (cellSize <= 0f || size <= 0) return null
        if (x < gridOriginX || x > gridOriginX + size * cellSize) return null
        if (y < gridOriginY || y > gridOriginY + size * cellSize) return null
        val col = ((x - gridOriginX) / cellSize).toInt().coerceIn(0, size - 1)
        val row = ((y - gridOriginY) / cellSize).toInt().coerceIn(0, size - 1)
        return row * size + col
    }

    // --- Test-only accessors (internal: exercised by NonogramRulesTest) ----

    internal fun debugChoosing(): Boolean = choosing
    internal fun debugSize(): Int = size
    internal fun debugStartWithDifficulty(d: NonogramGenerator.Difficulty): Unit = startWithDifficulty(d)
    internal fun debugPicture(idx: Int): Boolean = picture[idx]
    internal fun debugCellState(idx: Int): Int = cellState[idx]
    internal fun debugRowClueCount(row: Int): Int = rowClueCount[row]
    internal fun debugRowClue(row: Int, k: Int): Int = rowClues[row * MAX_CLUES + k]
    internal fun debugColClueCount(col: Int): Int = colClueCount[col]
    internal fun debugColClue(col: Int, k: Int): Int = colClues[col * MAX_CLUES + k]
    internal fun debugRngState(): Long = rng.state
    internal fun debugToggleCross(idx: Int): Unit = toggleCross(idx)
    internal fun debugPaintDown(idx: Int) {
        paintPointerId = 0
        lastPaintedIdx = -1
        paintValue = if (cellState[idx] == STATE_FILLED) STATE_BLANK else STATE_FILLED
        applyPaint(idx)
    }
    internal fun debugPaintMove(idx: Int) {
        if (paintPointerId == 0) applyPaint(idx)
    }
    internal fun debugPaintUp() {
        paintPointerId = NO_POINTER
        paintValue = -1
        lastPaintedIdx = -1
    }

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)

        if (choosing) {
            renderChooser(scene)
        } else {
            renderPuzzle(scene)
        }

        if (paintedThisMove) scene.sound(SoundId.TICK)
        if (wonThisMove) {
            scene.sound(SoundId.WIN)
            scene.haptic()
        }
    }

    private fun renderChooser(scene: Scene) {
        scene.text(viewWidth / 2f, buttonY(0) - 40f, StringKey.TITLE_NONOGRAM, Palette.FG, 24f, Align.CENTER)
        val labels = DIFFICULTY_LABELS
        for (i in 0 until 3) {
            val by = buttonY(i)
            scene.rect(buttonX(), by, buttonW(), BUTTON_HEIGHT_PT, Palette.ACCENT, radius = 12f)
            scene.text(viewWidth / 2f, by + BUTTON_HEIGHT_PT / 2f, labels[i], Palette.BG, 20f, Align.CENTER)
        }
    }

    private fun renderPuzzle(scene: Scene) {
        if (cellSize <= 0f) return
        val gridSide = size * cellSize
        scene.rect(gridOriginX, gridOriginY, gridSide, gridSide, Palette.SURFACE)

        for (row in 0 until size) {
            for (col in 0 until size) {
                val idx = row * size + col
                val cx = gridOriginX + col * cellSize
                val cy = gridOriginY + row * cellSize
                when (cellState[idx]) {
                    STATE_FILLED -> scene.rect(cx + 1f, cy + 1f, cellSize - 2f, cellSize - 2f, Palette.ACCENT)
                    STATE_CROSSED -> {
                        val pad = cellSize * 0.28f
                        scene.line(cx + pad, cy + pad, cx + cellSize - pad, cy + cellSize - pad, Palette.FG_DIM, width = 2f)
                        scene.line(cx + pad, cy + cellSize - pad, cx + cellSize - pad, cy + pad, Palette.FG_DIM, width = 2f)
                    }
                }
            }
        }

        for (i in 0..size) {
            val w = if (size >= 10 && i % 5 == 0) 2f else 1f
            val gx = gridOriginX + i * cellSize
            scene.line(gx, gridOriginY, gx, gridOriginY + gridSide, Palette.GRID, width = w)
            val gy = gridOriginY + i * cellSize
            scene.line(gridOriginX, gy, gridOriginX + gridSide, gy, Palette.GRID, width = w)
        }

        for (row in 0 until size) drawRowClue(scene, row)
        for (col in 0 until size) drawColClue(scene, col)
    }

    private fun drawRowClue(scene: Scene, row: Int) {
        val count = rowClueCount[row]
        val y = gridOriginY + row * cellSize + cellSize / 2f
        val pitch = (clueMargin / (count.coerceAtLeast(1))).coerceAtMost(cellSize).coerceAtLeast(1f)
        for (k in 0 until count) {
            val x = gridOriginX - clueMargin + k * pitch + pitch / 2f
            scene.number(x, y, rowClues[row * MAX_CLUES + k], Palette.FG, cellSize * 0.42f, Align.CENTER)
        }
    }

    private fun drawColClue(scene: Scene, col: Int) {
        val count = colClueCount[col]
        val x = gridOriginX + col * cellSize + cellSize / 2f
        val pitch = (clueMargin / (count.coerceAtLeast(1))).coerceAtMost(cellSize).coerceAtLeast(1f)
        for (k in 0 until count) {
            val y = gridOriginY - clueMargin + k * pitch + pitch / 2f
            scene.number(x, y, colClues[col * MAX_CLUES + k], Palette.FG, cellSize * 0.42f, Align.CENTER)
        }
    }

    // --- Save / restore -----------------------------------------------------
    // Only called from GameSession.persist() on pause, never per-frame, so the
    // map/string allocation here is fine (see docs/ARCHITECTURE.md).

    override fun snapshot(): String? {
        val map = LinkedHashMap<String, String>(8)
        map["choosing"] = if (choosing) "1" else "0"
        map["difficulty"] = difficulty.ordinal.toString()
        map["size"] = size.toString()
        val cellCount = if (size > 0) size * size else 0
        map["picture"] = bitsToString(picture, cellCount)
        map["state"] = digitsToString(cellState, cellCount)
        map["score"] = score.toString()
        map["rng"] = rng.state.toString()
        return KvCodec.encode(map)
    }

    override fun restore(data: String): Boolean {
        val map = KvCodec.decode(data)
        val choosingStr = map["choosing"]
        if (choosingStr != "0" && choosingStr != "1") return false
        val difficultyOrdinal = map["difficulty"]?.toIntOrNull() ?: return false
        val difficulties = NonogramGenerator.Difficulty.entries
        if (difficultyOrdinal !in difficulties.indices) return false
        val newSize = map["size"]?.toIntOrNull() ?: return false
        if (newSize !in 0..MAX_SIZE) return false
        val cellCount = newSize * newSize
        val pictureStr = map["picture"] ?: return false
        val stateStr = map["state"] ?: return false
        if (pictureStr.length != cellCount || stateStr.length != cellCount) return false
        if (!isBitString(pictureStr) || !isStateString(stateStr)) return false
        val newScore = map["score"]?.toIntOrNull() ?: return false
        if (newScore < 0) return false
        val rngState = map["rng"]?.toLongOrNull() ?: return false

        choosing = choosingStr == "1"
        difficulty = difficulties[difficultyOrdinal]
        size = newSize
        picture.fill(false)
        cellState.fill(STATE_BLANK)
        for (i in 0 until cellCount) {
            picture[i] = pictureStr[i] == '1'
            cellState[i] = stateStr[i] - '0'
        }
        if (newSize > 0) {
            NonogramGenerator.computeClues(newSize, picture, rowClueCount, rowClues, colClueCount, colClues)
        } else {
            rowClueCount.fill(0)
            colClueCount.fill(0)
        }
        score = newScore
        rng = Rng.fromState(rngState)
        status = GameStatus.Running
        paintPointerId = NO_POINTER
        paintValue = -1
        lastPaintedIdx = -1
        paintedThisMove = false
        wonThisMove = false
        layoutGrid()
        return true
    }

    private fun bitsToString(bits: BooleanArray, count: Int): String {
        val sb = StringBuilder(count)
        for (i in 0 until count) sb.append(if (bits[i]) '1' else '0')
        return sb.toString()
    }

    private fun digitsToString(digits: IntArray, count: Int): String {
        val sb = StringBuilder(count)
        for (i in 0 until count) sb.append(('0' + digits[i]))
        return sb.toString()
    }

    private fun isBitString(s: String): Boolean {
        for (ch in s) if (ch != '0' && ch != '1') return false
        return true
    }

    private fun isStateString(s: String): Boolean {
        for (ch in s) if (ch != '0' && ch != '1' && ch != '2') return false
        return true
    }

    public companion object {
        /** Chooser button labels, preallocated so the chooser render allocates nothing. */
        private val DIFFICULTY_LABELS: Array<StringKey> = arrayOf(StringKey.EASY, StringKey.MEDIUM, StringKey.HARD)
        public const val STATE_BLANK: Int = 0
        public const val STATE_FILLED: Int = 1
        public const val STATE_CROSSED: Int = 2

        private const val MAX_SIZE: Int = NonogramGenerator.MAX_SIZE
        private const val MAX_CLUES: Int = NonogramGenerator.MAX_CLUES
        private const val MAX_CELLS: Int = MAX_SIZE * MAX_SIZE

        private const val NO_POINTER: Int = -1
        private const val CLUE_MARGIN_FRACTION: Float = 0.24f

        private const val BUTTON_HEIGHT_PT: Float = 64f
        private const val BUTTON_GAP_PT: Float = 16f

        /** Vertical strip reserved for the session HUD (score / best). */
        private const val HUD_TOP_PT: Float = 32f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "nonogram",
            titleKey = StringKey.TITLE_NONOGRAM,
            tier = Tier.UNLOCKED,
            players = 1..1,
            orientation = Orientation.ANY,
            complexity = Complexity.C,
            resumable = true,
            factory = { NonogramGame() },
            icon = { scene, size -> drawIcon(scene, size) },
        )

        private fun drawIcon(scene: Scene, size: Float) {
            scene.rect(size * 0.1f, size * 0.1f, size * 0.8f, size * 0.8f, Palette.SURFACE, radius = size * 0.04f)
            val cell = size * 0.8f / 4f
            val pattern = booleanArrayOf(
                true, false, true, false,
                false, true, true, false,
                false, true, true, false,
                true, false, false, true,
            )
            for (r in 0 until 4) {
                for (c in 0 until 4) {
                    if (pattern[r * 4 + c]) {
                        scene.rect(size * 0.1f + c * cell, size * 0.1f + r * cell, cell, cell, Palette.ACCENT)
                    }
                }
            }
        }
    }
}
