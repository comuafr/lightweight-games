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
import lg.core.scene.PixelArt
import lg.core.scene.ArcadeIcons
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey
import lg.core.util.KvCodec
import lg.core.util.Layout
import lg.core.util.Rect
import lg.core.util.Rng
import kotlin.math.abs
import kotlin.math.sin

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
 * Nothing tells a wrong digit from a right one while playing (same color, same
 * sound, and the score counts filled cells). Only a grid that is full and not the
 * solution says that it holds an error, and asks: fix it, or see the solution,
 * which loses the game and marks the wrong cells. The game is won once `current`
 * equals `solution` everywhere. The game draws both end screens itself: the
 * solution stays in full view, a win is celebrated with rainbows and unicorns.
 */
public class SudokuGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    override var score: Int = 0
        private set

    /** Frames run only for the celebration of a win. */
    override val wantsFrames: Boolean
        get() = won && partyMs < PARTY_MS

    override val ownsEndScreen: Boolean get() = true

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

    /** True while the full but wrong grid offers to be fixed or to show the solution. */
    private var asking: Boolean = false

    /** The cells that were wrong when the player asked for the solution. */
    private val mistakes = BooleanArray(CELL_COUNT)

    /** Time into the celebration of a win. */
    private var partyMs: Int = 0
    private var winSoundPending: Boolean = false

    private val won: Boolean get() = (status as? GameStatus.Over)?.winner == GameStatus.SOLO_WIN
    private val lost: Boolean get() = (status as? GameStatus.Over)?.winner == GameStatus.SOLO_LOSS

    // Events from the most recent action, read (not mutated) by render().
    private var enteredThisMove: Boolean = false
    private var notedThisMove: Boolean = false
    private var wonThisMove: Boolean = false
    private var lostThisMove: Boolean = false

    override fun reset(seed: Long, players: Int) {
        rng = Rng(seed)
        choosing = true
        given.fill(0)
        solution.fill(0)
        current.fill(0)
        selected = -1
        notes.fill(0)
        pencil = false
        clearEnd()
        score = 0
        clearEvents()
        status = GameStatus.Running
    }

    private fun clearEnd() {
        asking = false
        mistakes.fill(false)
        partyMs = 0
        winSoundPending = false
    }

    private fun clearEvents() {
        enteredThisMove = false
        notedThisMove = false
        wonThisMove = false
        lostThisMove = false
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

        if (asking) {
            // Only the two answers do anything until one is chosen.
            when (answerAt(gesture.x, gesture.y)) {
                0 -> asking = false
                1 -> giveUp()
            }
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
        if (!wantsFrames) return false
        // The winning sound belongs to a frame of its own, so a redraw of the tap cannot lose or repeat it.
        wonThisMove = winSoundPending
        winSoundPending = false
        partyMs += dtMs
        return true
    }

    private fun giveUp() {
        asking = false
        for (i in 0 until CELL_COUNT) mistakes[i] = current[i] != solution[i]
        selected = -1
        status = GameStatus.Over(GameStatus.SOLO_LOSS)
        lostThisMove = true
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
        clearEnd()
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
            status = GameStatus.Over(GameStatus.SOLO_WIN)
            selected = -1
            partyMs = 0
            winSoundPending = true
        } else if (value != 0 && !pencil && isFullButWrong()) {
            asking = true
        }
    }

    /** Which answer of the question is at ([x], [y]): 0 fix, 1 show the solution, -1 neither. */
    private fun answerAt(x: Float, y: Float): Int {
        val top = numpadY() + ANSWER_Y_PT
        if (y < top || y > top + ANSWER_H_PT) return -1
        val half = viewWidth / 2f
        return when {
            x >= ANSWER_MARGIN_PT && x <= half - ANSWER_MARGIN_PT / 2f -> 0
            x >= half + ANSWER_MARGIN_PT / 2f && x <= viewWidth - ANSWER_MARGIN_PT -> 1
            else -> -1
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
    internal fun debugAsking(): Boolean = asking
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
            when {
                won -> renderParty(scene)
                lost -> scene.text(viewWidth / 2f, numpadY() + END_TITLE_Y_PT, StringKey.LOSE_TITLE, Palette.DANGER, END_TITLE_PT, Align.CENTER)
                asking -> renderQuestion(scene)
                else -> renderNumpad(scene)
            }
        }

        if (enteredThisMove) scene.sound(SoundId.EAT)
        if (notedThisMove) scene.sound(SoundId.TICK)
        if (lostThisMove) scene.sound(SoundId.LOSE)
        if (wonThisMove) {
            scene.sound(SoundId.WIN)
            scene.haptic()
        }
    }

    private fun isFullButWrong(): Boolean {
        for (i in 0 until CELL_COUNT) if (current[i] == 0) return false
        return !isSolved()
    }

    /** In place of the pad: the grid is full and wrong. */
    private fun renderQuestion(scene: Scene) {
        scene.text(viewWidth / 2f, numpadY() + QUESTION_Y_PT, StringKey.GRID_HAS_ERROR, Palette.DANGER, QUESTION_PT, Align.CENTER)
        val top = numpadY() + ANSWER_Y_PT
        val half = viewWidth / 2f
        val width = half - 1.5f * ANSWER_MARGIN_PT
        scene.rect(ANSWER_MARGIN_PT, top, width, ANSWER_H_PT, Palette.ACCENT, radius = 12f)
        scene.text(ANSWER_MARGIN_PT + width / 2f, top + ANSWER_H_PT / 2f, StringKey.FIX, Palette.BG, ANSWER_PT, Align.CENTER)
        val right = half + ANSWER_MARGIN_PT / 2f
        scene.rect(right, top, width, ANSWER_H_PT, Palette.SURFACE, radius = 12f)
        scene.text(right + width / 2f, top + ANSWER_H_PT / 2f, StringKey.SHOW_SOLUTION, Palette.FG, ANSWER_PT, Align.CENTER)
    }

    /**
     * A win: the solved grid fades back, a rainbow rises over it, unicorns come in and hop, sparkles twinkle. After
     * [PARTY_MS] everything holds still.
     */
    private fun renderParty(scene: Scene) {
        scene.rect(boardRect.x, boardRect.y, boardRect.w, boardRect.h, Palette.BG, alpha = 0.7f)
        val rise = (partyMs / RAINBOW_RISE_MS.toFloat()).coerceIn(0f, 1f)
        val cx = viewWidth / 2f
        val baseY = boardRect.y + boardRect.h * 0.68f
        // Concentric half discs, the last one in the background color, leave six bands.
        val outer = boardRect.w * 0.48f * rise
        if (outer > 1f) {
            for (k in RAINBOW.indices) scene.arc(cx, baseY, outer * (1f - k * BAND_SHARE), 180f, 180f, RAINBOW[k])
            scene.arc(cx, baseY, outer * (1f - RAINBOW.size * BAND_SHARE), 180f, 180f, Palette.BG)
        }
        val t = partyMs / 1000f
        for (k in 0 until SPARKLES) {
            // Fixed places, each twinkling at its own moment.
            val x = boardRect.x + boardRect.w * ((k * 0.618f + 0.07f) % 1f)
            val y = boardRect.y + boardRect.h * ((k * 0.381f + 0.03f) % 1f)
            val glow = 0.5f + 0.5f * sin(t * 5f + k * 1.7f)
            val color = RAINBOW[k % RAINBOW.size]
            val r = 2f + 6f * glow
            scene.line(x - r, y, x + r, y, color, width = 2f, alpha = glow)
            scene.line(x, y - r, x, y + r, color, width = 2f, alpha = glow)
        }
        val arrive = (partyMs / UNICORN_ARRIVE_MS.toFloat()).coerceIn(0f, 1f)
        val settle = 1f - (partyMs / PARTY_MS.toFloat()).coerceIn(0f, 1f)
        val hop = abs(sin(t * 4f)) * 18f * settle
        renderUnicorn(scene, -UNICORN_PT + arrive * (UNICORN_PT + 14f), baseY - UNICORN_PT * 0.3f - hop, UNICORN_RIGHT)
        val other = abs(sin(t * 4f + 1.6f)) * 18f * settle
        renderUnicorn(scene, viewWidth - arrive * (UNICORN_PT + 14f), baseY - UNICORN_PT * 0.3f - other, UNICORN_LEFT)
        scene.text(cx, numpadY() + END_TITLE_Y_PT, StringKey.WIN_TITLE, Palette.ACCENT, END_TITLE_PT, Align.CENTER)
    }

    private fun renderUnicorn(scene: Scene, x: Float, y: Float, sprite: Array<String>) {
        val mark = scene.count
        PixelArt.draw(scene, UNICORN_PT, sprite)
        scene.transformFrom(mark, 1f, x, y)
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

        if (lost) {
            for (i in 0 until CELL_COUNT) {
                if (mistakes[i]) scene.rect(boardRect.x + i % SIZE * cell, boardRect.y + i / SIZE * cell, cell, cell, Palette.DANGER)
            }
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
            // A lost game shows the solution.
            val v = if (lost) solution[i] else current[i]
            if (v == 0) {
                renderNotes(scene, notes[i], cx, cy, cell)
                continue
            }
            val color = when {
                lost && mistakes[i] -> Palette.NEON_WHITE
                given[i] != 0 -> Palette.FG
                else -> Palette.ACCENT
            }
            scene.number(cx, cy, v, color, cell * 0.5f, Align.CENTER)
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
        clearEnd()
        asking = !choosing && isFullButWrong()
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
        // The question shown in place of the pad, relative to the pad's top.
        private const val QUESTION_Y_PT: Float = 18f
        private const val QUESTION_PT: Float = 16f
        private const val ANSWER_Y_PT: Float = 40f
        private const val ANSWER_H_PT: Float = 56f
        private const val ANSWER_MARGIN_PT: Float = 14f
        private const val ANSWER_PT: Float = 16f

        // End screens. The session's button sits at 472..536 of the 560 pt zone, below this title.
        private const val END_TITLE_Y_PT: Float = 24f
        private const val END_TITLE_PT: Float = 30f
        private const val PARTY_MS: Int = 8000
        private const val RAINBOW_RISE_MS: Int = 900
        private const val UNICORN_ARRIVE_MS: Int = 1200
        private const val UNICORN_PT: Float = 112f
        private const val BAND_SHARE: Float = 0.09f
        private const val SPARKLES: Int = 18
        private val RAINBOW = intArrayOf(
            Palette.NEON_RED, Palette.NEON_ORANGE, Palette.NEON_YELLOW,
            Palette.NEON_GREEN, Palette.NEON_CYAN, Palette.NEON_PURPLE,
        )

        /** A unicorn looking right: golden horn, pink mane and tail. */
        private val UNICORN_RIGHT: Array<String> = arrayOf(
            "..............y.",
            ".............y..",
            "............y...",
            "..........pww...",
            ".........ppwwww.",
            "........ppwwdww.",
            ".......ppwwwwwww",
            "......ppwww.wwww",
            "..wwwwpwwww.....",
            "pwwwwwwwwww.....",
            "ppwwwwwwwww.....",
            "pp.wwwwwwww.....",
            "p..ww....ww.....",
            "p..w.w...w.w....",
            "...w..w..w..w...",
            "...uu.uu.uu.uu..",
        )

        private val UNICORN_LEFT: Array<String> = Array(UNICORN_RIGHT.size) { UNICORN_RIGHT[it].reversed() }
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
