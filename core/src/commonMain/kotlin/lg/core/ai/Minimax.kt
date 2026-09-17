package lg.core.ai

/**
 * Abstract two-player, zero-sum game board searched by [Minimax].
 *
 * Move ids are opaque integers meaningful only to the implementation (a
 * board cell index for [lg.core.games.tictactoe.TicTacToeGame], a column
 * index for [lg.core.games.fourinrow.FourInRowGame]). [moveCount] and
 * [move] enumerate the moves legal in the *current* position; [apply] and
 * [undo] mutate the board in place (no allocation), always called in
 * perfectly nested pairs (`apply(id, p)` then, later, `undo(id)` before any
 * sibling move is applied), so an implementation may safely reuse a single
 * mutable board representation for the entire search.
 */
public interface MinimaxBoard {
    /** Number of legal moves in the current position. */
    public fun moveCount(): Int

    /** The [i]-th legal move id, `0 <= i < moveCount()`. */
    public fun move(i: Int): Int

    /** Plays move [id] for [player] on the board. */
    public fun apply(id: Int, player: Int)

    /** Reverses the effect of the most recent matching [apply] call for move [id]. */
    public fun undo(id: Int)

    /**
     * If the current position is terminal (won, lost or drawn), the score
     * from [player]'s point of view (positive = [player] wins, negative =
     * [player] loses, zero = draw); `null` if the game is not over.
     */
    public fun terminalScore(player: Int): Int?

    /** Static evaluation of a non-terminal position from [player]'s point of view; higher is better for [player]. */
    public fun heuristic(player: Int): Int
}

/**
 * Generic, allocation-free negamax search with alpha-beta pruning over a
 * [MinimaxBoard]. Used by `TicTacToeGame` (full-depth, perfect play on a 3x3
 * board) and `FourInRowGame` (depth-limited with a heuristic).
 *
 * Search state lives entirely on the call stack (primitives and the shared
 * [board] instance); no arrays or objects are allocated per call.
 */
public object Minimax {
    /** Score magnitude used for a certain win/loss, comfortably above any heuristic value. */
    public const val WIN_SCORE: Int = 1_000_000

    /**
     * Picks the move id that is best for [player] (about to move now),
     * assuming [opponent] always replies optimally, searching at most
     * [maxDepth] plies ahead. @return the chosen move id, or -1 if there are
     * no legal moves.
     */
    public fun bestMove(board: MinimaxBoard, player: Int, opponent: Int, maxDepth: Int): Int {
        val n = board.moveCount()
        if (n == 0) return -1
        var bestId = -1
        var bestScore = Int.MIN_VALUE
        var alpha = -WIN_SCORE - 1
        val beta = WIN_SCORE + 1
        for (i in 0 until n) {
            val id = board.move(i)
            board.apply(id, player)
            val score = -negamax(board, opponent, player, maxDepth - 1, -beta, -alpha)
            board.undo(id)
            if (score > bestScore || bestId == -1) {
                bestScore = score
                bestId = id
            }
            if (bestScore > alpha) alpha = bestScore
        }
        return bestId
    }

    private fun negamax(board: MinimaxBoard, player: Int, opponent: Int, depth: Int, alphaIn: Int, beta: Int): Int {
        val terminal = board.terminalScore(player)
        if (terminal != null) return terminal
        if (depth <= 0) return board.heuristic(player)
        val n = board.moveCount()
        if (n == 0) return board.heuristic(player)

        var alpha = alphaIn
        var best = -WIN_SCORE - 1
        for (i in 0 until n) {
            val id = board.move(i)
            board.apply(id, player)
            val score = -negamax(board, opponent, player, depth - 1, -beta, -alpha)
            board.undo(id)
            if (score > best) best = score
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        return best
    }
}
