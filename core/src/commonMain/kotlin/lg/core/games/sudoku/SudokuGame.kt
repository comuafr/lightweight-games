package lg.core.games.sudoku

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
import lg.core.util.Layout
import lg.core.util.Rect
import lg.core.util.Rng

/**
 * 9x9 Sudoku with a difficulty choice drawn by the game itself.
 *
 * ### Difficulty as an in-game state, not an overlay
 *
 * `GameSession` only offers its generic Start/Paused/Over overlays and calls
 * [reset] once, on start (PLAN.md §9 M4). So [reset] does *not* generate a
 * puzzle: it puts the game in [choosing] mode (`wantsFrames` false), where
 * [render] draws three big EASY/MEDIUM/HARD buttons instead of a board, and
 * [onGesture] hit-tests them. Tapping one calls [SudokuGenerator.generate]
 * (bounded to a few hundred ms worst case; see its KDoc) and switches to the
 * normal board+keypad screen.
 *
 * ### Data layout
 *
 * [given] (0 = blank) and [solution] (always fully solved) are fixed once
 * generated; [current] is the live grid (givens plus whatever the player has
 * entered so far, 0 = blank). A tap on a non-given board cell selects it
 * ([selected]); a tap on the number pad writes into the selected cell (or
 * clears it, for the erase key) if it isn't a given. Wrong entries
 * (`current[i] != 0 && current[i] != solution[i]`) are drawn in
 * [Palette.DANGER] for immediate feedback. The game is won once `current`
 * equals `solution` everywhere.
 */
public class SudokuGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    override var score: Int = 0
        private set

    override val wantsFrames: Boolean = false

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private val boardRect = Rect()

    private var rng: Rng = Rng(0L)
    private var choosing: Boolean = true
    private var difficulty: SudokuGenerator.Difficulty = SudokuGenerator.Difficulty.EASY

    private val given = IntArray(CELL_COUNT)
    private val solution = IntArray(CELL_COUNT)
    private val current = IntArray(CELL_COUNT)
    private var selected: Int = -1

    // Events from the most recent action, read (not mutated) by render().
    private var enteredCorrectThisMove: Boolean = false
    private var enteredWrongThisMove: Boolean = false
    private var wonThisMove: Boolean = false

    override fun reset(seed: Long, players: Int) {
        rng = Rng(seed)
        choosing = true
        given.fill(0)
        solution.fill(0)
        current.fill(0)
        selected = -1
        score = 0
        enteredCorrectThisMove = false
        enteredWrongThisMove = false
        wonThisMove = false
        status = GameStatus.Running
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        val available = (viewHeight - HUD_TOP_PT - NUMPAD_H - NUMPAD_GAP_PT).coerceAtLeast(1f)
        Layout.fitSquare(viewWidth, available + HUD_TOP_PT, HUD_TOP_PT, boardRect)
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        // Sudoku is tap-driven only (via onGesture); raw pointer events are ignored.
    }

    override fun onGesture(gesture: Gesture) {
        enteredCorrectThisMove = false
        enteredWrongThisMove = false
        wonThisMove = false
        if (status != GameStatus.Running) return
        if (gesture.kind != GestureKind.TAP) return

        if (choosing) {
            val idx = difficultyButtonAt(gesture.x, gesture.y)
            if (idx >= 0) startWithDifficulty(SudokuGenerator.Difficulty.entries[idx])
            return
        }

        val cellIdx = cellAt(gesture.x, gesture.y)
        if (cellIdx != null) {
            selected = cellIdx
            return
        }
        val key = numpadKeyAt(gesture.x, gesture.y)
        if (key != null) applyNumpadKey(key)
    }

    override fun update(dtMs: Int): Boolean {
        enteredCorrectThisMove = false
        enteredWrongThisMove = false
        wonThisMove = false
        return false
    }

    private fun startWithDifficulty(d: SudokuGenerator.Difficulty) {
        difficulty = d
        val puzzle = SudokuGenerator.generate(rng, d)
        for (i in 0 until CELL_COUNT) {
            given[i] = puzzle.given[i]
            solution[i] = puzzle.solution[i]
            current[i] = puzzle.given[i]
        }
        selected = -1
        choosing = false
        score = computeScore()
    }

    private fun applyNumpadKey(value: Int) {
        val sel = selected
        if (sel < 0) return
        if (given[sel] != 0) return
        current[sel] = value
        if (value != 0) {
            if (value == solution[sel]) enteredCorrectThisMove = true else enteredWrongThisMove = true
        }
        score = computeScore()
        if (isSolved()) {
            status = GameStatus.Over(0)
            wonThisMove = true
        }
    }

    private fun computeScore(): Int {
        var n = 0
        for (i in 0 until CELL_COUNT) if (current[i] != 0 && current[i] == solution[i]) n++
        return n
    }

    private fun isSolved(): Boolean {
        for (i in 0 until CELL_COUNT) if (current[i] != solution[i]) return false
        return true
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
        if (boardRect.w <= 0f) return null
        if (x < boardRect.x || x > boardRect.x + boardRect.w) return null
        if (y < boardRect.y || y > boardRect.y + boardRect.h) return null
        val cell = boardRect.w / SIZE
        val col = ((x - boardRect.x) / cell).toInt().coerceIn(0, SIZE - 1)
        val row = ((y - boardRect.y) / cell).toInt().coerceIn(0, SIZE - 1)
        return row * SIZE + col
    }

    private fun numpadY(): Float = boardRect.y + boardRect.h + NUMPAD_GAP_PT
    private fun numpadKeyW(): Float = ((viewWidth - (NUMPAD_COLS + 1) * NUMPAD_KEY_GAP_PT) / NUMPAD_COLS).coerceAtLeast(1f)

    private fun numpadKeyAt(x: Float, y: Float): Int? {
        val areaY = numpadY()
        val keyW = numpadKeyW()
        val areaX = NUMPAD_KEY_GAP_PT
        if (x < areaX || x > areaX + NUMPAD_COLS * keyW) return null
        if (y < areaY || y > areaY + NUMPAD_ROWS * NUMPAD_KEY_H) return null
        val col = ((x - areaX) / keyW).toInt().coerceIn(0, NUMPAD_COLS - 1)
        val row = ((y - areaY) / NUMPAD_KEY_H).toInt().coerceIn(0, NUMPAD_ROWS - 1)
        val idx = row * NUMPAD_COLS + col
        if (idx > 9) return null
        return if (idx == 9) 0 else idx + 1 // slot 9 is erase (value 0); slots 0..8 are digits 1..9
    }

    // --- Test-only accessors (internal: exercised by SudokuRulesTest) ----

    internal fun debugChoosing(): Boolean = choosing
    internal fun debugStartWithDifficulty(d: SudokuGenerator.Difficulty): Unit = startWithDifficulty(d)
    internal fun debugGiven(idx: Int): Int = given[idx]
    internal fun debugSolution(idx: Int): Int = solution[idx]
    internal fun debugCurrent(idx: Int): Int = current[idx]
    internal fun debugSetSelected(idx: Int) {
        selected = idx
    }
    internal fun debugSelected(): Int = selected
    internal fun debugApplyNumpadKey(value: Int): Unit = applyNumpadKey(value)
    internal fun debugRngState(): Long = rng.state
    internal fun debugDifficulty(): SudokuGenerator.Difficulty = difficulty

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)

        if (choosing) {
            renderChooser(scene)
        } else {
            renderBoard(scene)
            renderNumpad(scene)
        }

        if (enteredCorrectThisMove) scene.sound(SoundId.EAT)
        if (enteredWrongThisMove) scene.sound(SoundId.HIT)
        if (wonThisMove) {
            scene.sound(SoundId.WIN)
            scene.haptic()
        }
    }

    private fun renderChooser(scene: Scene) {
        scene.text(viewWidth / 2f, buttonY(0) - 40f, StringKey.TITLE_SUDOKU, Palette.FG, 24f, Align.CENTER)
        val labels = DIFFICULTY_LABELS
        for (i in 0 until 3) {
            val by = buttonY(i)
            scene.rect(buttonX(), by, buttonW(), BUTTON_HEIGHT_PT, Palette.ACCENT, radius = 12f)
            scene.text(viewWidth / 2f, by + BUTTON_HEIGHT_PT / 2f, labels[i], Palette.BG, 20f, Align.CENTER)
        }
    }

    private fun renderBoard(scene: Scene) {
        scene.rect(boardRect.x, boardRect.y, boardRect.w, boardRect.h, Palette.SURFACE, radius = boardRect.w * 0.01f)
        val cell = boardRect.w / SIZE

        if (selected >= 0) {
            val row = selected / SIZE
            val col = selected % SIZE
            scene.rect(boardRect.x + col * cell, boardRect.y + row * cell, cell, cell, Palette.ACCENT, alpha = 0.3f)
        }

        for (i in 0 until CELL_COUNT) {
            val row = i / SIZE
            val col = i % SIZE
            val cx = boardRect.x + col * cell + cell / 2f
            val cy = boardRect.y + row * cell + cell / 2f
            val v = current[i]
            if (v == 0) continue
            val color = when {
                given[i] != 0 -> Palette.FG
                v == solution[i] -> Palette.ACCENT
                else -> Palette.DANGER
            }
            scene.number(cx, cy, v, color, cell * 0.5f, Align.CENTER)
        }

        for (i in 0..SIZE) {
            val w = if (i % 3 == 0) 3f else 1f
            val gx = boardRect.x + i * cell
            scene.line(gx, boardRect.y, gx, boardRect.y + boardRect.h, Palette.GRID, width = w)
            val gy = boardRect.y + i * cell
            scene.line(boardRect.x, gy, boardRect.x + boardRect.w, gy, Palette.GRID, width = w)
        }
    }

    private fun renderNumpad(scene: Scene) {
        val areaY = numpadY()
        val keyW = numpadKeyW()
        val areaX = NUMPAD_KEY_GAP_PT
        for (idx in 0..9) {
            val row = idx / NUMPAD_COLS
            val col = idx % NUMPAD_COLS
            val x = areaX + col * keyW
            val y = areaY + row * NUMPAD_KEY_H
            val pad = keyW * 0.06f
            scene.rect(x + pad, y + pad, keyW - 2 * pad, NUMPAD_KEY_H - 2 * pad, Palette.SURFACE, radius = pad)
            if (idx == 9) {
                val cx = x + keyW / 2f
                val cy = y + NUMPAD_KEY_H / 2f
                val r = keyW * 0.15f
                scene.line(cx - r, cy - r, cx + r, cy + r, Palette.FG_DIM, width = 3f)
                scene.line(cx - r, cy + r, cx + r, cy - r, Palette.FG_DIM, width = 3f)
            } else {
                scene.number(x + keyW / 2f, y + NUMPAD_KEY_H / 2f, idx + 1, Palette.FG, NUMPAD_KEY_H * 0.45f, Align.CENTER)
            }
        }
    }

    // --- Save / restore -----------------------------------------------------
    // Only called from GameSession.persist() on pause, never per-frame, so the
    // map/string allocation here is fine (see docs/ARCHITECTURE.md).

    override fun snapshot(): String? {
        val map = LinkedHashMap<String, String>(8)
        map["choosing"] = if (choosing) "1" else "0"
        map["difficulty"] = difficulty.ordinal.toString()
        map["given"] = digitsToString(given)
        map["solution"] = digitsToString(solution)
        map["current"] = digitsToString(current)
        map["selected"] = selected.toString()
        map["score"] = score.toString()
        map["rng"] = rng.state.toString()
        return KvCodec.encode(map)
    }

    override fun restore(data: String): Boolean {
        val map = KvCodec.decode(data)
        val choosingStr = map["choosing"]
        if (choosingStr != "0" && choosingStr != "1") return false
        val difficultyOrdinal = map["difficulty"]?.toIntOrNull() ?: return false
        val difficulties = SudokuGenerator.Difficulty.entries
        if (difficultyOrdinal !in difficulties.indices) return false
        val givenStr = map["given"] ?: return false
        val solutionStr = map["solution"] ?: return false
        val currentStr = map["current"] ?: return false
        if (givenStr.length != CELL_COUNT || solutionStr.length != CELL_COUNT || currentStr.length != CELL_COUNT) return false
        if (!isDigitString(givenStr) || !isDigitString(solutionStr) || !isDigitString(currentStr)) return false
        val selectedValue = map["selected"]?.toIntOrNull() ?: return false
        if (selectedValue < -1 || selectedValue >= CELL_COUNT) return false
        val newScore = map["score"]?.toIntOrNull() ?: return false
        if (newScore < 0) return false
        val rngState = map["rng"]?.toLongOrNull() ?: return false

        for (i in 0 until CELL_COUNT) {
            given[i] = givenStr[i] - '0'
            solution[i] = solutionStr[i] - '0'
            current[i] = currentStr[i] - '0'
        }
        choosing = choosingStr == "1"
        difficulty = difficulties[difficultyOrdinal]
        selected = selectedValue
        score = newScore
        rng = Rng.fromState(rngState)
        status = GameStatus.Running
        enteredCorrectThisMove = false
        enteredWrongThisMove = false
        wonThisMove = false
        return true
    }

    private fun digitsToString(digits: IntArray): String {
        val sb = StringBuilder(digits.size)
        for (d in digits) sb.append(('0' + d))
        return sb.toString()
    }

    private fun isDigitString(s: String): Boolean {
        for (ch in s) if (ch < '0' || ch > '9') return false
        return true
    }

    public companion object {
        /** Chooser button labels, preallocated so the chooser render allocates nothing. */
        private val DIFFICULTY_LABELS: Array<StringKey> = arrayOf(StringKey.EASY, StringKey.MEDIUM, StringKey.HARD)
        public const val SIZE: Int = SudokuGenerator.SIZE
        public const val CELL_COUNT: Int = SudokuGenerator.CELL_COUNT

        private const val NUMPAD_COLS: Int = 5
        private const val NUMPAD_ROWS: Int = 2
        private const val NUMPAD_KEY_H: Float = 48f
        private const val NUMPAD_KEY_GAP_PT: Float = 6f
        private const val NUMPAD_GAP_PT: Float = 8f
        private const val NUMPAD_H: Float = NUMPAD_ROWS * NUMPAD_KEY_H + (NUMPAD_ROWS + 1) * NUMPAD_KEY_GAP_PT

        private const val BUTTON_HEIGHT_PT: Float = 64f
        private const val BUTTON_GAP_PT: Float = 16f

        /** Vertical strip reserved for the session HUD (score / best). */
        private const val HUD_TOP_PT: Float = 32f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "sudoku",
            titleKey = StringKey.TITLE_SUDOKU,
            tier = Tier.UNLOCKED,
            players = 1..1,
            orientation = Orientation.PORTRAIT,
            complexity = Complexity.C,
            resumable = true,
            factory = { SudokuGame() },
            icon = { scene, size -> drawIcon(scene, size) },
        )

        private fun drawIcon(scene: Scene, size: Float) {
            scene.rect(size * 0.08f, size * 0.08f, size * 0.84f, size * 0.84f, Palette.SURFACE, radius = size * 0.04f)
            for (i in 1..2) {
                val p = size * 0.08f + i * (size * 0.84f / 3f)
                scene.line(p, size * 0.08f, p, size * 0.92f, Palette.GRID, width = size * 0.02f)
                scene.line(size * 0.08f, p, size * 0.92f, p, Palette.GRID, width = size * 0.02f)
            }
            scene.number(size * 0.28f, size * 0.28f, 5, Palette.ACCENT, size * 0.2f, Align.CENTER)
            scene.number(size * 0.72f, size * 0.72f, 9, Palette.FG, size * 0.2f, Align.CENTER)
        }
    }
}
