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
import lg.core.scene.ArcadeIcons
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
 * entered so far, 0 = blank). A tap on a board cell selects it ([selected]); a
 * tap on the number pad writes into the selected cell (or clears it, for the
 * erase key) if it isn't a given.
 *
 * With the [pencil] on, digit keys instead add or remove small notes in an empty
 * cell ([notes], one bit per digit). Notes are entirely the player's: nothing
 * removes them but the player; a digit entered in the cell only hides them.
 *
 * Nothing tells a wrong digit from a right one (same color, same sound, and the
 * score counts filled cells). Only a grid that is full and not the solution says
 * that it holds an error, without saying where. The game is won once `current`
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

    /** Notes of each cell: bit `d - 1` set when digit `d` is pencilled in. */
    private val notes = IntArray(CELL_COUNT)
    private var pencil: Boolean = false

    // Events from the most recent action, read (not mutated) by render().
    private var enteredThisMove: Boolean = false
    private var notedThisMove: Boolean = false
    private var wonThisMove: Boolean = false

    override fun reset(seed: Long, players: Int) {
        rng = Rng(seed)
        choosing = true
        given.fill(0)
        solution.fill(0)
        current.fill(0)
        selected = -1
        notes.fill(0)
        pencil = false
        score = 0
        clearEvents()
        status = GameStatus.Running
    }

    private fun clearEvents() {
        enteredThisMove = false
        notedThisMove = false
        wonThisMove = false
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
        clearEvents()
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
        if (key == PENCIL_KEY) {
            pencil = !pencil
            notedThisMove = true
        } else if (key != null) {
            applyNumpadKey(key)
        }
    }

    override fun update(dtMs: Int): Boolean {
        clearEvents()
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
        notes.fill(0)
        pencil = false
        choosing = false
        score = computeScore()
    }

    private fun applyNumpadKey(value: Int) {
        val sel = selected
        if (sel < 0) return
        if (given[sel] != 0) return
        if (value == 0) {
            // Erasing takes the digit first (its notes reappear), then the notes.
            if (current[sel] != 0) current[sel] = 0 else notes[sel] = 0
        } else if (pencil) {
            if (current[sel] != 0) return
            notes[sel] = notes[sel] xor (1 shl (value - 1))
            notedThisMove = true
        } else {
            current[sel] = value
            enteredThisMove = true
        }
        score = computeScore()
        if (isSolved()) {
            status = GameStatus.Over(0)
            wonThisMove = true
        }
    }

    private fun computeScore(): Int {
        var n = 0
        // Filled cells, right or wrong: the score must not give errors away.
        for (i in 0 until CELL_COUNT) if (current[i] != 0) n++
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
        // Two rows of six: 1 to 5 then erase (value 0); 6 to 9 then the pencil, two slots wide.
        return when {
            idx < ERASE_SLOT -> idx + 1
            idx == ERASE_SLOT -> 0
            idx < PENCIL_SLOT -> idx
            else -> PENCIL_KEY
        }
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
    internal fun debugNotes(idx: Int): Int = notes[idx]
    internal fun debugPencil(): Boolean = pencil
    internal fun debugKeyAt(x: Float, y: Float): Int? = numpadKeyAt(x, y)
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
            if (isFullButWrong()) {
                val y = numpadY() + NUMPAD_ROWS * NUMPAD_KEY_H + ERROR_GAP_PT
                scene.text(viewWidth / 2f, y, StringKey.GRID_HAS_ERROR, Palette.DANGER, ERROR_TEXT_PT, Align.CENTER)
            }
        }

        if (enteredThisMove) scene.sound(SoundId.EAT)
        if (notedThisMove) scene.sound(SoundId.TICK)
        if (wonThisMove) {
            scene.sound(SoundId.WIN)
            scene.haptic()
        }
    }

    private fun isFullButWrong(): Boolean {
        for (i in 0 until CELL_COUNT) if (current[i] == 0) return false
        return !isSolved()
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

        // The corner and middle 3 x 3 blocks are tinted, so that each block can be told apart at a glance.
        for (block in 0 until SIZE step 2) {
            scene.rect(
                boardRect.x + block % 3 * 3 * cell,
                boardRect.y + block / 3 * 3 * cell,
                3 * cell,
                3 * cell,
                Palette.NUMBER_1,
                alpha = BLOCK_TINT_ALPHA,
            )
        }

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
            if (v == 0) {
                renderNotes(scene, notes[i], cx, cy, cell)
                continue
            }
            scene.number(cx, cy, v, if (given[i] != 0) Palette.FG else Palette.ACCENT, cell * 0.5f, Align.CENTER)
        }

        // Thin lines first, then the block borders over them.
        for (i in 0..SIZE) if (i % 3 != 0) renderGridLine(scene, i, cell, Palette.GRID, 1f)
        // The usual grid color disappears on the tint: inside the tinted blocks the thin lines take the opposite
        // tone (light on a dark theme, dark on a light one).
        for (block in 0 until SIZE step 2) renderTintedBlockLines(scene, block, cell)
        for (i in 0..SIZE step 3) renderGridLine(scene, i, cell, Palette.FG_DIM, 2.5f)
    }

    /** The vertical and the horizontal grid line number [i]. */
    private fun renderGridLine(scene: Scene, i: Int, cell: Float, color: Int, width: Float) {
        val gx = boardRect.x + i * cell
        scene.line(gx, boardRect.y, gx, boardRect.y + boardRect.h, color, width = width)
        val gy = boardRect.y + i * cell
        scene.line(boardRect.x, gy, boardRect.x + boardRect.w, gy, color, width = width)
    }

    /** The two vertical and two horizontal lines between the cells of 3 x 3 block [block]. */
    private fun renderTintedBlockLines(scene: Scene, block: Int, cell: Float) {
        val left = boardRect.x + block % 3 * 3 * cell
        val top = boardRect.y + block / 3 * 3 * cell
        val side = 3 * cell
        for (k in 1..2) {
            val x = left + k * cell
            val y = top + k * cell
            scene.line(x, top, x, top + side, Palette.FG_DIM, width = 1f, alpha = TINTED_LINE_ALPHA)
            scene.line(left, y, left + side, y, Palette.FG_DIM, width = 1f, alpha = TINTED_LINE_ALPHA)
        }
    }

    /** The pencilled digits of an empty cell, each at its own place in a 3 x 3 pattern. */
    private fun renderNotes(scene: Scene, mask: Int, cx: Float, cy: Float, cell: Float) {
        if (mask == 0) return
        for (d in 0 until SIZE) {
            if (mask and (1 shl d) == 0) continue
            val x = cx + (d % 3 - 1) * cell * NOTE_STEP
            val y = cy + (d / 3 - 1) * cell * NOTE_STEP
            scene.number(x, y, d + 1, Palette.FG_DIM, cell * NOTE_SIZE, Align.CENTER)
        }
    }

    private fun renderNumpad(scene: Scene) {
        val areaY = numpadY()
        val keyW = numpadKeyW()
        val areaX = NUMPAD_KEY_GAP_PT
        val pad = keyW * 0.06f
        for (idx in 0..PENCIL_SLOT) {
            val x = areaX + idx % NUMPAD_COLS * keyW
            val y = areaY + idx / NUMPAD_COLS * NUMPAD_KEY_H
            val width = if (idx == PENCIL_SLOT) 2 * keyW else keyW
            val color = if (idx == PENCIL_SLOT && pencil) Palette.ACCENT else Palette.SURFACE
            scene.rect(x + pad, y + pad, width - 2 * pad, NUMPAD_KEY_H - 2 * pad, color, radius = pad)
            val cx = x + width / 2f
            val cy = y + NUMPAD_KEY_H / 2f
            when {
                idx == ERASE_SLOT -> {
                    val r = keyW * 0.15f
                    scene.line(cx - r, cy - r, cx + r, cy + r, Palette.FG_DIM, width = 3f)
                    scene.line(cx - r, cy + r, cx + r, cy - r, Palette.FG_DIM, width = 3f)
                }
                idx == PENCIL_SLOT -> renderPencil(scene, cx, cy, if (pencil) Palette.BG else Palette.FG_DIM)
                else -> {
                    val digit = if (idx < ERASE_SLOT) idx + 1 else idx
                    scene.number(cx, cy, digit, Palette.FG, NUMPAD_KEY_H * 0.45f, Align.CENTER)
                }
            }
        }
    }

    /** A slanted pencil: its body, its tip and the line it draws. */
    private fun renderPencil(scene: Scene, cx: Float, cy: Float, color: Int) {
        scene.line(cx - 5f, cy + 5f, cx + 10f, cy - 10f, color, width = 8f)
        scene.line(cx - 12f, cy + 12f, cx - 6f, cy + 6f, color, width = 3f)
        scene.line(cx - 12f, cy + 13f, cx + 12f, cy + 13f, color, width = 2f)
    }

    // --- Save / restore -----------------------------------------------------
    // Only called from GameSession.persist() on pause, never per-frame, so the
    // map/string allocation here is fine (see docs/ARCHITECTURE.md).

    override val canResume: Boolean
        get() = status == GameStatus.Running && !choosing && !isSolved()

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
        map["notes"] = notes.joinToString(",")
        map["pencil"] = if (pencil) "1" else "0"
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
        // Saves made before the pencil existed have neither of these two keys.
        val notesStr = map["notes"]
        val newNotes = notesStr?.split(",")?.map { it.toIntOrNull() ?: return false }
        if (newNotes != null && (newNotes.size != CELL_COUNT || newNotes.any { it < 0 || it > ALL_NOTES })) return false
        val pencilStr = map["pencil"] ?: "0"
        if (pencilStr != "0" && pencilStr != "1") return false

        for (i in 0 until CELL_COUNT) {
            notes[i] = newNotes?.get(i) ?: 0
            given[i] = givenStr[i] - '0'
            solution[i] = solutionStr[i] - '0'
            current[i] = currentStr[i] - '0'
        }
        choosing = choosingStr == "1"
        difficulty = difficulties[difficultyOrdinal]
        selected = selectedValue
        pencil = pencilStr == "1"
        score = computeScore()
        rng = Rng.fromState(rngState)
        status = GameStatus.Running
        clearEvents()
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

        private const val NUMPAD_COLS: Int = 6
        private const val NUMPAD_ROWS: Int = 2
        private const val NUMPAD_KEY_H: Float = 48f
        private const val NUMPAD_KEY_GAP_PT: Float = 6f
        private const val NUMPAD_GAP_PT: Float = 8f
        private const val NUMPAD_H: Float = NUMPAD_ROWS * NUMPAD_KEY_H + (NUMPAD_ROWS + 1) * NUMPAD_KEY_GAP_PT

        private const val BUTTON_HEIGHT_PT: Float = 64f
        private const val BUTTON_GAP_PT: Float = 16f

        /** Number pad slots (row by row) of the erase key and of the pencil. */
        private const val ERASE_SLOT: Int = 5
        private const val PENCIL_SLOT: Int = 10
        internal const val PENCIL_KEY: Int = 10
        private const val ALL_NOTES: Int = 511

        private const val NOTE_STEP: Float = 0.3f
        private const val NOTE_SIZE: Float = 0.27f
        private const val ERROR_GAP_PT: Float = 14f
        private const val ERROR_TEXT_PT: Float = 15f
        private const val BLOCK_TINT_ALPHA: Float = 0.16f
        private const val TINTED_LINE_ALPHA: Float = 0.6f

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
            icon = { scene, size -> ArcadeIcons.nineByNine(scene, size) },
        )
    }
}
