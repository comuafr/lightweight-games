package lg.core.games.reversi

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
import lg.core.scene.Align
import lg.core.scene.Palette
import lg.core.scene.ArcadeIcons
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey
import lg.core.util.Layout
import lg.core.util.Rect

/**
 * 8x8 Reversi/Othello. [board] is flat-indexed `row * SIZE + col`, `0` empty,
 * `1` = player 1 (black, always first), `2` = player 2 (white).
 *
 * A tap is legal only if it flips at least one opposing line in one of the 8
 * directions ([wouldFlip]/[flipDirection], both allocation-free). After a
 * move, [advanceTurn] hands play to the other player, or back to the same
 * player if the other has no legal move (a forced pass), or ends the game
 * ([endGame]) if neither can move (this also covers a full board, which
 * trivially has no legal moves for either side).
 *
 * In 1-player mode, player 2 (white) is played by [Minimax] (depth
 * [AI_SEARCH_DEPTH]) via the [SearchBoard] adapter. [SearchBoard] tracks its
 * own [SearchBoard.toMove] because, unlike tic-tac-toe/four-in-a-row, legal
 * moves in Reversi depend on whose turn it is; `apply`/`undo` push/pop a
 * small fixed stack of (cell, player, flip-range) entries into
 * [SearchBoard.flipBuffer] so a whole search is allocation-free and always
 * restores [board] exactly. One simplification: if the *nominal* mover at a
 * search ply has zero legal moves, [Minimax] treats that as a leaf
 * (`moveCount() == 0`) rather than modeling a pass and continuing with the
 * same player — an acceptable approximation at depth 3, where a genuine pass
 * essentially never occurs this early. [SearchBoard.terminalScore] still
 * detects genuine game-end (neither side can move) correctly regardless.
 * The AI move is delayed by [AI_DELAY_MS] so it is visible, same as
 * `TicTacToeGame`/`FourInRowGame`.
 */
public class ReversiGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    /** Total discs on the board (4 initial + 1 per move played; flips don't change this). */
    override var score: Int = 0
        private set

    override val wantsFrames: Boolean
        get() = status == GameStatus.Running && awaitingAiMove

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private val boardRect = Rect()

    private val board = IntArray(CELL_COUNT)
    private var playerCount: Int = 1
    private var currentPlayer: Int = 1

    private var awaitingAiMove: Boolean = false
    private var aiTimerMs: Int = 0
    private val searchBoard = SearchBoard()

    // Events from the most recent action, read (not mutated) by render(). Reset at the top of update()/onGesture.
    private var moveThisMove: Boolean = false
    private var illegalTapThisMove: Boolean = false
    private var passedThisMove: Boolean = false
    private var gameOverThisMove: Boolean = false
    private var tieThisMove: Boolean = false

    override fun reset(seed: Long, players: Int) {
        playerCount = players.coerceIn(1, 2)
        board.fill(0)
        board[idx(3, 3)] = 2
        board[idx(3, 4)] = 1
        board[idx(4, 3)] = 1
        board[idx(4, 4)] = 2
        currentPlayer = 1
        awaitingAiMove = false
        aiTimerMs = 0
        moveThisMove = false
        illegalTapThisMove = false
        passedThisMove = false
        gameOverThisMove = false
        tieThisMove = false
        score = 4
        status = GameStatus.Running
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        Layout.fitSquare(viewWidth, viewHeight, HUD_TOP_PT + INFO_STRIP_PT, boardRect)
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        // Reversi is tap-driven only (via onGesture); raw pointer events are ignored.
    }

    override fun onGesture(gesture: Gesture) {
        moveThisMove = false
        illegalTapThisMove = false
        passedThisMove = false
        gameOverThisMove = false
        tieThisMove = false
        if (status != GameStatus.Running) return
        if (gesture.kind != GestureKind.TAP) return
        if (awaitingAiMove) return
        if (playerCount == 1 && currentPlayer == AI_PLAYER) return
        val idx = cellAt(gesture.x, gesture.y) ?: return
        if (!isLegalMove(idx, currentPlayer)) {
            illegalTapThisMove = true
            return
        }
        playMove(idx, currentPlayer)
    }

    override fun update(dtMs: Int): Boolean {
        moveThisMove = false
        illegalTapThisMove = false
        passedThisMove = false
        gameOverThisMove = false
        tieThisMove = false
        if (status != GameStatus.Running || !awaitingAiMove) return false
        aiTimerMs += dtMs
        if (aiTimerMs < AI_DELAY_MS) return false
        awaitingAiMove = false
        performAiMove()
        return true
    }

    private fun performAiMove() {
        searchBoard.toMove = AI_PLAYER
        val move = Minimax.bestMove(searchBoard, player = AI_PLAYER, opponent = OTHER_PLAYER, maxDepth = AI_SEARCH_DEPTH)
        if (move >= 0) playMove(move, AI_PLAYER)
    }

    // --- Move logic -----------------------------------------------------------

    private fun playMove(cellIdx: Int, player: Int) {
        applyMove(cellIdx, player)
        score = countDiscs(1) + countDiscs(2)
        moveThisMove = true
        advanceTurn(player)
    }

    private fun applyMove(cellIdx: Int, player: Int) {
        board[cellIdx] = player
        val row = cellIdx / SIZE
        val col = cellIdx % SIZE
        for (d in 0 until 8) {
            if (wouldFlip(row, col, DX[d], DY[d], player)) flipDirection(row, col, DX[d], DY[d], player)
        }
    }

    private fun advanceTurn(justMoved: Int) {
        val next = other(justMoved)
        if (hasAnyLegalMove(next)) {
            currentPlayer = next
        } else if (hasAnyLegalMove(justMoved)) {
            currentPlayer = justMoved
            passedThisMove = true
        } else {
            endGame()
            return
        }
        if (playerCount == 1 && currentPlayer == AI_PLAYER) {
            awaitingAiMove = true
            aiTimerMs = 0
        }
    }

    private fun endGame() {
        val p1 = countDiscs(1)
        val p2 = countDiscs(2)
        val winner = if (p1 > p2) 1 else if (p2 > p1) 2 else 0
        tieThisMove = winner == 0
        status = GameStatus.Over(winner)
        gameOverThisMove = true
    }

    private fun other(player: Int): Int = if (player == 1) 2 else 1

    private fun idx(row: Int, col: Int): Int = row * SIZE + col

    private fun wouldFlip(row: Int, col: Int, dr: Int, dc: Int, player: Int): Boolean {
        val opponent = other(player)
        var r = row + dr
        var c = col + dc
        var count = 0
        while (r in 0 until SIZE && c in 0 until SIZE && board[idx(r, c)] == opponent) {
            count++
            r += dr
            c += dc
        }
        return count > 0 && r in 0 until SIZE && c in 0 until SIZE && board[idx(r, c)] == player
    }

    private fun flipDirection(row: Int, col: Int, dr: Int, dc: Int, player: Int) {
        var r = row + dr
        var c = col + dc
        while (board[idx(r, c)] != player) {
            board[idx(r, c)] = player
            r += dr
            c += dc
        }
    }

    private fun isLegalMove(cellIdx: Int, player: Int): Boolean {
        if (board[cellIdx] != 0) return false
        val row = cellIdx / SIZE
        val col = cellIdx % SIZE
        for (d in 0 until 8) {
            if (wouldFlip(row, col, DX[d], DY[d], player)) return true
        }
        return false
    }

    private fun hasAnyLegalMove(player: Int): Boolean {
        for (i in 0 until CELL_COUNT) {
            if (board[i] == 0 && isLegalMove(i, player)) return true
        }
        return false
    }

    private fun countDiscs(player: Int): Int {
        var n = 0
        for (v in board) if (v == player) n++
        return n
    }

    private fun cellAt(x: Float, y: Float): Int? {
        if (boardRect.w <= 0f) return null
        if (x < boardRect.x || x > boardRect.x + boardRect.w) return null
        if (y < boardRect.y || y > boardRect.y + boardRect.h) return null
        val cell = boardRect.w / SIZE
        val col = ((x - boardRect.x) / cell).toInt().coerceIn(0, SIZE - 1)
        val row = ((y - boardRect.y) / cell).toInt().coerceIn(0, SIZE - 1)
        return idx(row, col)
    }

    // --- Test-only accessors (internal: exercised by ReversiRulesTest) ----

    internal fun debugCell(cellIdx: Int): Int = board[cellIdx]
    internal fun debugCurrentPlayer(): Int = currentPlayer
    internal fun debugIsLegalMove(cellIdx: Int, player: Int): Boolean = isLegalMove(cellIdx, player)
    internal fun debugHasAnyLegalMove(player: Int): Boolean = hasAnyLegalMove(player)
    internal fun debugCountDiscs(player: Int): Int = countDiscs(player)
    internal fun debugAwaitingAiMove(): Boolean = awaitingAiMove
    internal fun debugAiSuggestedMove(): Int {
        searchBoard.toMove = AI_PLAYER
        return Minimax.bestMove(searchBoard, player = AI_PLAYER, opponent = OTHER_PLAYER, maxDepth = AI_SEARCH_DEPTH)
    }
    internal fun debugPlayMove(cellIdx: Int): Unit = playMove(cellIdx, currentPlayer)
    internal fun debugSetCurrentPlayer(player: Int) {
        currentPlayer = player
    }
    internal fun debugSetBoard(cells: IntArray) {
        for (i in 0 until CELL_COUNT) board[i] = cells[i]
        score = countDiscs(1) + countDiscs(2)
    }
    internal fun debugSkipAi() {
        var guard = 0
        while (awaitingAiMove && status == GameStatus.Running && guard < 100) {
            update(AI_DELAY_MS)
            guard++
        }
    }

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)

        scene.number(viewWidth * 0.28f, HUD_TOP_PT + INFO_STRIP_PT * 0.6f, countDiscs(1), Palette.P1, INFO_TEXT_SIZE_PT, Align.CENTER)
        scene.number(viewWidth * 0.72f, HUD_TOP_PT + INFO_STRIP_PT * 0.6f, countDiscs(2), Palette.P2, INFO_TEXT_SIZE_PT, Align.CENTER)

        scene.rect(boardRect.x, boardRect.y, boardRect.w, boardRect.h, Palette.SURFACE, radius = boardRect.w * 0.01f)
        val cell = boardRect.w / SIZE
        for (i in 1 until SIZE) {
            val gx = boardRect.x + i * cell
            scene.line(gx, boardRect.y, gx, boardRect.y + boardRect.h, Palette.GRID, width = 1f)
            val gy = boardRect.y + i * cell
            scene.line(boardRect.x, gy, boardRect.x + boardRect.w, gy, Palette.GRID, width = 1f)
        }

        val showHints = status == GameStatus.Running && !awaitingAiMove
        for (row in 0 until SIZE) {
            for (col in 0 until SIZE) {
                val i = idx(row, col)
                val cx = boardRect.x + col * cell + cell / 2f
                val cy = boardRect.y + row * cell + cell / 2f
                when (board[i]) {
                    1 -> scene.circle(cx, cy, cell * DISC_RADIUS_FRACTION, Palette.P1)
                    2 -> scene.circle(cx, cy, cell * DISC_RADIUS_FRACTION, Palette.P2)
                    else -> if (showHints && isLegalMove(i, currentPlayer)) {
                        scene.circle(cx, cy, cell * HINT_RADIUS_FRACTION, Palette.ACCENT, alpha = 0.5f)
                    }
                }
            }
        }

        if (moveThisMove) scene.sound(SoundId.TICK)
        if (illegalTapThisMove) scene.sound(SoundId.HIT)
        if (gameOverThisMove) {
            if (tieThisMove) {
                scene.sound(SoundId.TICK)
            } else {
                scene.sound(SoundId.WIN)
            }
            scene.haptic()
        }
    }

    /**
     * Adapts [board] to [MinimaxBoard]. Unlike the tic-tac-toe/four-in-a-row
     * boards, legal moves depend on whose turn it is, so [toMove] is tracked
     * explicitly and updated by [apply]/[undo] (see the class KDoc).
     */
    private inner class SearchBoard : MinimaxBoard {
        var toMove: Int = 1

        private val stackIdx = IntArray(MAX_SEARCH_STACK)
        private val stackPlayer = IntArray(MAX_SEARCH_STACK)
        private val stackFlipStart = IntArray(MAX_SEARCH_STACK)
        val flipBuffer = IntArray(MAX_FLIP_BUFFER)
        private var stackTop = 0
        private var flipTop = 0

        override fun moveCount(): Int {
            var n = 0
            for (i in 0 until CELL_COUNT) if (board[i] == 0 && isLegalMove(i, toMove)) n++
            return n
        }

        override fun move(i: Int): Int {
            var remaining = i
            for (cellIdx in 0 until CELL_COUNT) {
                if (board[cellIdx] == 0 && isLegalMove(cellIdx, toMove)) {
                    if (remaining == 0) return cellIdx
                    remaining--
                }
            }
            return -1
        }

        override fun apply(id: Int, player: Int) {
            val row = id / SIZE
            val col = id % SIZE
            stackIdx[stackTop] = id
            stackPlayer[stackTop] = player
            stackFlipStart[stackTop] = flipTop
            board[id] = player
            for (d in 0 until 8) {
                if (wouldFlip(row, col, DX[d], DY[d], player)) {
                    var r = row + DX[d]
                    var c = col + DY[d]
                    while (board[idx(r, c)] != player) {
                        board[idx(r, c)] = player
                        flipBuffer[flipTop] = idx(r, c)
                        flipTop++
                        r += DX[d]
                        c += DY[d]
                    }
                }
            }
            stackTop++
            toMove = other(player)
        }

        override fun undo(id: Int) {
            stackTop--
            val player = stackPlayer[stackTop]
            val opponent = other(player)
            val start = stackFlipStart[stackTop]
            for (i in flipTop - 1 downTo start) board[flipBuffer[i]] = opponent
            flipTop = start
            board[stackIdx[stackTop]] = 0
            toMove = player
        }

        override fun terminalScore(player: Int): Int? {
            val opponent = other(player)
            if (!hasAnyLegalMove(player) && !hasAnyLegalMove(opponent)) {
                val diff = countDiscs(player) - countDiscs(opponent)
                return when {
                    diff > 0 -> Minimax.WIN_SCORE
                    diff < 0 -> -Minimax.WIN_SCORE
                    else -> 0
                }
            }
            return null
        }

        override fun heuristic(player: Int): Int {
            val opponent = other(player)
            var positional = 0
            for (i in 0 until CELL_COUNT) {
                when (board[i]) {
                    player -> positional += WEIGHTS[i]
                    opponent -> positional -= WEIGHTS[i]
                }
            }
            var mobilityPlayer = 0
            var mobilityOpponent = 0
            for (i in 0 until CELL_COUNT) {
                if (board[i] != 0) continue
                if (isLegalMove(i, player)) mobilityPlayer++
                if (isLegalMove(i, opponent)) mobilityOpponent++
            }
            return positional + (mobilityPlayer - mobilityOpponent) * MOBILITY_WEIGHT
        }
    }

    public companion object {
        public const val SIZE: Int = 8
        public const val CELL_COUNT: Int = SIZE * SIZE

        private const val AI_PLAYER: Int = 2
        private const val OTHER_PLAYER: Int = 1
        private const val AI_DELAY_MS: Int = 350
        private const val AI_SEARCH_DEPTH: Int = 3
        private const val MOBILITY_WEIGHT: Int = 4
        private const val MAX_SEARCH_STACK: Int = 32
        private const val MAX_FLIP_BUFFER: Int = 32 * 20

        private val DX = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
        private val DY = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)

        // Classic static positional weight table: corners high, X-squares (diagonal
        // to a corner) strongly negative, C-squares (orthogonal to a corner) negative.
        private val WEIGHTS = intArrayOf(
            120, -20, 20, 5, 5, 20, -20, 120,
            -20, -40, -5, -5, -5, -5, -40, -20,
            20, -5, 15, 3, 3, 15, -5, 20,
            5, -5, 3, 3, 3, 3, -5, 5,
            5, -5, 3, 3, 3, 3, -5, 5,
            20, -5, 15, 3, 3, 15, -5, 20,
            -20, -40, -5, -5, -5, -5, -40, -20,
            120, -20, 20, 5, 5, 20, -20, 120,
        )

        private const val DISC_RADIUS_FRACTION: Float = 0.38f
        private const val HINT_RADIUS_FRACTION: Float = 0.12f
        private const val INFO_STRIP_PT: Float = 28f
        private const val INFO_TEXT_SIZE_PT: Float = 22f

        /** Vertical strip reserved for the session HUD (score / best). */
        private const val HUD_TOP_PT: Float = 32f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "reversi",
            titleKey = StringKey.TITLE_REVERSI,
            tier = Tier.UNLOCKED,
            players = 1..2,
            orientation = Orientation.PORTRAIT,
            complexity = Complexity.C,
            resumable = false,
            factory = { ReversiGame() },
            icon = { scene, size -> ArcadeIcons.flipDiscs(scene, size) },
        )
    }
}
