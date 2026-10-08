package lg.core.games.fourinrow

import lg.core.Complexity
import lg.core.Game
import lg.core.GameMeta
import lg.core.GameStatus
import lg.core.Orientation
import lg.core.Tier
import lg.core.ai.Minimax
import lg.core.ai.MinimaxBoard
import lg.core.input.Gesture
import lg.core.input.GestureKind
import lg.core.input.PointerPhase
import lg.core.scene.Palette
import lg.core.scene.ArcadeIcons
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey

/**
 * 7x6 "four in a row" with gravity. [cell] is flat-indexed `col * ROWS + row`
 * with `row == 0` at the bottom (where discs land first); [heightOfCol]
 * caches the next free row per column for O(1) drop targeting.
 *
 * A tap on a column drops a disc for [currentPlayer]: the board mutates to
 * its final, logical state immediately (win/draw detection happens right
 * away, in [dropDisc]), while a short [DROP_ANIM_MS] fall is played back
 * separately in [render]/[update] (the same "mutate now, animate
 * separately" approach as `Game2048`). Input (new taps) is ignored while
 * [animActive], so the state transition — whose turn is next, or the game
 * ending — is only applied once the disc visually lands ([land]).
 *
 * In 1-player mode, player 2 is played by [Minimax] (alpha-beta, depth
 * [AI_SEARCH_DEPTH]) via the [SearchBoard] adapter, which reuses [cell]/
 * [heightOfCol] directly so the search is allocation-free. The AI move is
 * delayed by [AI_DELAY_MS] so it is visible; [wantsFrames] is true while
 * either the drop animation or the AI delay is in progress.
 */
public class FourInRowGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    /** Total discs dropped so far (by both players); see the class KDoc. */
    override var score: Int = 0
        private set

    override val wantsFrames: Boolean
        get() = status == GameStatus.Running && (animActive || awaitingAiMove)

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private var cellSize: Float = 1f
    private var boardX: Float = 0f
    private var boardY: Float = 0f

    private val cell = IntArray(COLS * ROWS)
    private val heightOfCol = IntArray(COLS)
    private var playerCount: Int = 1
    private var currentPlayer: Int = 1

    private var animActive: Boolean = false
    private var animCol: Int = 0
    private var animToRow: Int = 0
    private var animPlayer: Int = 0
    private var animT: Float = 1f

    private var awaitingAiMove: Boolean = false
    private var aiTimerMs: Int = 0
    private val searchBoard = SearchBoard()

    private var pendingWinner: Int = 0
    private var pendingDraw: Boolean = false
    private val winCells = IntArray(4) { -1 }
    private var winCount: Int = 0

    // Events from the most recent action, read (not mutated) by render(). Reset at the top of update().
    private var dropThisUpdate: Boolean = false
    private var wonThisUpdate: Boolean = false
    private var drawThisUpdate: Boolean = false

    override fun reset(seed: Long, players: Int) {
        playerCount = players.coerceIn(1, 2)
        cell.fill(0)
        heightOfCol.fill(0)
        currentPlayer = 1
        animActive = false
        animT = 1f
        awaitingAiMove = false
        aiTimerMs = 0
        pendingWinner = 0
        pendingDraw = false
        winCells.fill(-1)
        winCount = 0
        dropThisUpdate = false
        wonThisUpdate = false
        drawThisUpdate = false
        score = 0
        status = GameStatus.Running
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        val boardH = (viewHeight - HUD_TOP_PT).coerceAtLeast(1f)
        cellSize = minOf(viewWidth / COLS, boardH / ROWS).coerceAtLeast(0f)
        boardX = (viewWidth - COLS * cellSize) / 2f
        boardY = HUD_TOP_PT + (boardH - ROWS * cellSize) / 2f
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        // FourInRow is tap-driven only (via onGesture); raw pointer events are ignored.
    }

    override fun onGesture(gesture: Gesture) {
        if (status != GameStatus.Running) return
        if (gesture.kind != GestureKind.TAP) return
        if (animActive || awaitingAiMove) return
        val col = columnAt(gesture.x, gesture.y) ?: return
        if (heightOfCol[col] >= ROWS) return
        dropDisc(col, currentPlayer)
    }

    private fun columnAt(x: Float, y: Float): Int? {
        if (cellSize <= 0f) return null
        if (x < boardX || x > boardX + COLS * cellSize) return null
        if (y < boardY || y > boardY + ROWS * cellSize) return null
        return ((x - boardX) / cellSize).toInt().coerceIn(0, COLS - 1)
    }

    private fun dropDisc(col: Int, player: Int) {
        val row = heightOfCol[col]
        heightOfCol[col] = row + 1
        cell[idx(col, row)] = player
        score++

        animCol = col
        animToRow = row
        animPlayer = player
        animT = 0f
        animActive = true
        dropThisUpdate = true

        val won = computeWinningLineFrom(col, row, player)
        pendingWinner = if (won) player else 0
        pendingDraw = !won && isBoardFull()
    }

    override fun update(dtMs: Int): Boolean {
        dropThisUpdate = false
        wonThisUpdate = false
        drawThisUpdate = false
        if (status != GameStatus.Running) return false

        if (animActive) {
            animT = (animT + dtMs / DROP_ANIM_MS).coerceAtMost(1f)
            if (animT >= 1f) {
                animActive = false
                land()
            }
            return true
        }
        if (awaitingAiMove) {
            aiTimerMs += dtMs
            if (aiTimerMs >= AI_DELAY_MS) {
                awaitingAiMove = false
                performAiMove()
            }
            return true
        }
        return false
    }

    private fun land() {
        if (pendingWinner != 0) {
            status = GameStatus.Over(pendingWinner)
            wonThisUpdate = true
            return
        }
        if (pendingDraw) {
            status = GameStatus.Over(GameStatus.DRAW)
            drawThisUpdate = true
            return
        }
        currentPlayer = other(currentPlayer)
        if (playerCount == 1 && currentPlayer == 2) {
            awaitingAiMove = true
            aiTimerMs = 0
        }
    }

    private fun performAiMove() {
        val col = Minimax.bestMove(searchBoard, player = 2, opponent = 1, maxDepth = AI_SEARCH_DEPTH)
        if (col >= 0) dropDisc(col, 2)
    }

    private fun other(player: Int): Int = if (player == 1) 2 else 1

    private fun idx(col: Int, row: Int): Int = col * ROWS + row

    private fun isBoardFull(): Boolean {
        for (c in 0 until COLS) if (heightOfCol[c] < ROWS) return false
        return true
    }

    /** True if [player] has 4-in-a-row through the just-dropped `(col, row)`; records [winCells] if so. */
    private fun computeWinningLineFrom(col: Int, row: Int, player: Int): Boolean {
        for (d in 0 until 4) {
            val dx = DIR_DX[d]
            val dy = DIR_DY[d]
            var count = 1
            var startCol = col
            var startRow = row

            var c = col + dx
            var r = row + dy
            while (inBounds(c, r) && cell[idx(c, r)] == player) {
                count++
                c += dx
                r += dy
            }
            c = col - dx
            r = row - dy
            while (inBounds(c, r) && cell[idx(c, r)] == player) {
                count++
                startCol = c
                startRow = r
                c -= dx
                r -= dy
            }

            if (count >= 4) {
                for (i in 0 until 4) winCells[i] = idx(startCol + dx * i, startRow + dy * i)
                winCount = 4
                return true
            }
        }
        return false
    }

    private fun inBounds(c: Int, r: Int): Boolean = c in 0 until COLS && r in 0 until ROWS

    private fun boardHasFour(player: Int): Boolean {
        for (r in 0 until ROWS) for (c in 0..COLS - 4) if (allEqual(player, c, r, 1, 0)) return true
        for (c in 0 until COLS) for (r in 0..ROWS - 4) if (allEqual(player, c, r, 0, 1)) return true
        for (c in 0..COLS - 4) for (r in 0..ROWS - 4) if (allEqual(player, c, r, 1, 1)) return true
        for (c in 0..COLS - 4) for (r in 3 until ROWS) if (allEqual(player, c, r, 1, -1)) return true
        return false
    }

    private fun allEqual(player: Int, c: Int, r: Int, dx: Int, dy: Int): Boolean {
        for (i in 0 until 4) {
            if (cell[idx(c + dx * i, r + dy * i)] != player) return false
        }
        return true
    }

    // --- Test-only accessors (internal: exercised by FourInRowRulesTest) ----

    internal fun debugCell(col: Int, row: Int): Int = cell[idx(col, row)]
    internal fun debugHeightOfCol(col: Int): Int = heightOfCol[col]
    internal fun debugCurrentPlayer(): Int = currentPlayer
    internal fun debugIsAnimating(): Boolean = animActive
    internal fun debugAwaitingAiMove(): Boolean = awaitingAiMove
    internal fun debugAiSuggestedColumn(): Int = Minimax.bestMove(searchBoard, player = 2, opponent = 1, maxDepth = AI_SEARCH_DEPTH)
    internal fun debugTapColumn(col: Int) {
        if (status != GameStatus.Running || animActive || awaitingAiMove) return
        if (heightOfCol[col] >= ROWS) return
        dropDisc(col, currentPlayer)
    }
    internal fun debugSetCurrentPlayer(player: Int) {
        currentPlayer = player
    }

    /** Places a disc directly, bypassing turn/animation/win logic, to build arbitrary board scenarios for tests. */
    internal fun debugForceCell(col: Int, row: Int, player: Int) {
        cell[idx(col, row)] = player
        if (heightOfCol[col] <= row) heightOfCol[col] = row + 1
    }

    /** Fast-forwards until neither the drop animation nor the AI delay is in progress (bounded). */
    internal fun debugSkipAnimationAndAi() {
        var guard = 0
        while ((animActive || awaitingAiMove) && status == GameStatus.Running && guard < 100_000) {
            update(16)
            guard++
        }
    }

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)
        scene.rect(boardX, boardY, COLS * cellSize, ROWS * cellSize, Palette.SURFACE, radius = cellSize * 0.08f)

        for (c in 0 until COLS) {
            for (r in 0 until ROWS) {
                val v = if (animActive && c == animCol && r == animToRow) 0 else cell[idx(c, r)]
                drawDisc(scene, c, r, v)
            }
        }

        if (animActive) {
            val fromY = boardY - cellSize
            val toY = screenY(animToRow)
            val y = fromY + (toY - fromY) * animT
            val x = screenX(animCol)
            scene.circle(x, y, cellSize * DISC_RADIUS_FRACTION, colorForPlayer(animPlayer))
        }

        if (winCount == 4 && status is GameStatus.Over) {
            val sx = screenX(winCells[0] / ROWS)
            val sy = screenY(winCells[0] % ROWS)
            val ex = screenX(winCells[3] / ROWS)
            val ey = screenY(winCells[3] % ROWS)
            scene.line(sx, sy, ex, ey, Palette.ACCENT, width = cellSize * 0.12f)
        }

        if (dropThisUpdate) scene.sound(SoundId.TICK)
        if (wonThisUpdate) {
            scene.sound(SoundId.WIN)
            scene.haptic()
        }
    }

    private fun screenX(col: Int): Float = boardX + col * cellSize + cellSize / 2f
    private fun screenY(row: Int): Float = boardY + (ROWS - 1 - row) * cellSize + cellSize / 2f

    private fun drawDisc(scene: Scene, col: Int, row: Int, value: Int) {
        val color = if (value == 0) Palette.GRID else colorForPlayer(value)
        scene.circle(screenX(col), screenY(row), cellSize * DISC_RADIUS_FRACTION, color)
    }

    private fun colorForPlayer(player: Int): Int = if (player == 1) Palette.P1 else Palette.P2

    /**
     * Adapts [cell]/[heightOfCol] to [MinimaxBoard]: reuses the actual game
     * arrays for search, so `apply`/`undo` are allocation-free and always
     * leave them exactly as found once a top-level search finishes.
     */
    private inner class SearchBoard : MinimaxBoard {
        override fun moveCount(): Int {
            var n = 0
            for (c in 0 until COLS) if (heightOfCol[c] < ROWS) n++
            return n
        }

        override fun move(i: Int): Int {
            var remaining = i
            for (c in 0 until COLS) {
                if (heightOfCol[c] < ROWS) {
                    if (remaining == 0) return c
                    remaining--
                }
            }
            return -1
        }

        override fun apply(id: Int, player: Int) {
            val row = heightOfCol[id]
            cell[idx(id, row)] = player
            heightOfCol[id] = row + 1
        }

        override fun undo(id: Int) {
            val row = heightOfCol[id] - 1
            cell[idx(id, row)] = 0
            heightOfCol[id] = row
        }

        override fun terminalScore(player: Int): Int? {
            val opponent = other(player)
            if (boardHasFour(opponent)) return -Minimax.WIN_SCORE
            if (boardHasFour(player)) return Minimax.WIN_SCORE
            if (isBoardFull()) return 0
            return null
        }

        override fun heuristic(player: Int): Int {
            val opponent = other(player)
            var h = 0
            for (r in 0 until ROWS) {
                val v = cell[idx(CENTER_COL, r)]
                if (v == player) h += CENTER_WEIGHT else if (v == opponent) h -= CENTER_WEIGHT
            }
            h += windowScore(player) - windowScore(opponent)
            return h
        }

        private fun windowScore(player: Int): Int {
            val opponent = other(player)
            var total = 0
            for (r in 0 until ROWS) for (c in 0..COLS - 4) total += evalWindow(player, opponent, c, r, 1, 0)
            for (c in 0 until COLS) for (r in 0..ROWS - 4) total += evalWindow(player, opponent, c, r, 0, 1)
            for (c in 0..COLS - 4) for (r in 0..ROWS - 4) total += evalWindow(player, opponent, c, r, 1, 1)
            for (c in 0..COLS - 4) for (r in 3 until ROWS) total += evalWindow(player, opponent, c, r, 1, -1)
            return total
        }

        private fun evalWindow(player: Int, opponent: Int, c: Int, r: Int, dx: Int, dy: Int): Int {
            var countP = 0
            var countOpp = 0
            for (i in 0 until 4) {
                when (cell[idx(c + dx * i, r + dy * i)]) {
                    player -> countP++
                    opponent -> countOpp++
                }
            }
            if (countOpp > 0) return 0
            return when (countP) {
                3 -> 50
                2 -> 10
                1 -> 1
                else -> 0
            }
        }
    }

    public companion object {
        public const val COLS: Int = 7
        public const val ROWS: Int = 6

        private const val AI_DELAY_MS: Int = 350
        private const val AI_SEARCH_DEPTH: Int = 4
        private const val CENTER_COL: Int = COLS / 2
        private const val CENTER_WEIGHT: Int = 3

        private const val DROP_ANIM_MS: Float = 200f
        private const val DISC_RADIUS_FRACTION: Float = 0.38f

        private val DIR_DX = intArrayOf(1, 0, 1, 1)
        private val DIR_DY = intArrayOf(0, 1, 1, -1)

        /** Vertical strip reserved for the session HUD (score / best). */
        private const val HUD_TOP_PT: Float = 32f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "fourinrow",
            titleKey = StringKey.TITLE_FOURINROW,
            tier = Tier.UNLOCKED,
            players = 1..2,
            orientation = Orientation.PORTRAIT,
            complexity = Complexity.M,
            resumable = false,
            factory = { FourInRowGame() },
            icon = { scene, size -> ArcadeIcons.dropFour(scene, size) },
        )
    }
}
