package lg.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Minimal 3x3 tic-tac-toe [MinimaxBoard] used to exercise [Minimax] directly
 * (independent of `TicTacToeGame`'s own adapter). `1` = X, `2` = O.
 */
private class TestTicTacToeBoard : MinimaxBoard {
    val cells = IntArray(9)

    override fun moveCount(): Int = cells.count { it == 0 }

    override fun move(i: Int): Int {
        var remaining = i
        for (idx in cells.indices) {
            if (cells[idx] == 0) {
                if (remaining == 0) return idx
                remaining--
            }
        }
        return -1
    }

    override fun apply(id: Int, player: Int) {
        cells[id] = player
    }

    override fun undo(id: Int) {
        cells[id] = 0
    }

    override fun terminalScore(player: Int): Int? {
        val opponent = if (player == 1) 2 else 1
        if (hasLine(player)) return Minimax.WIN_SCORE
        if (hasLine(opponent)) return -Minimax.WIN_SCORE
        if (cells.none { it == 0 }) return 0
        return null
    }

    override fun heuristic(player: Int): Int = 0

    private fun hasLine(player: Int): Boolean = LINES.any { (a, b, c) ->
        cells[a] == player && cells[b] == player && cells[c] == player
    }

    private companion object {
        val LINES = listOf(
            Triple(0, 1, 2), Triple(3, 4, 5), Triple(6, 7, 8),
            Triple(0, 3, 6), Triple(1, 4, 7), Triple(2, 5, 8),
            Triple(0, 4, 8), Triple(2, 4, 6),
        )
    }
}

public class MinimaxTest {
    @Test
    public fun blocksAnImmediateOpponentWin(): Unit {
        val board = TestTicTacToeBoard()
        // X X . / O O . / . . .  -> O (player 2) must block at index 2.
        board.cells[0] = 1
        board.cells[1] = 1
        board.cells[3] = 2
        board.cells[4] = 2

        val move = Minimax.bestMove(board, player = 2, opponent = 1, maxDepth = 9)
        assertEquals(2, move)
    }

    @Test
    public fun takesAnImmediateWinWhenAvailable(): Unit {
        val board = TestTicTacToeBoard()
        // O O . in the top row, O to move and win at index 2.
        board.cells[0] = 2
        board.cells[1] = 2
        board.cells[3] = 1
        board.cells[4] = 1

        val move = Minimax.bestMove(board, player = 2, opponent = 1, maxDepth = 9)
        assertEquals(2, move)
    }

    @Test
    public fun perfectPlayFromEmptyBoardAlwaysDraws(): Unit {
        val board = TestTicTacToeBoard()
        var current = 1
        var moves = 0
        while (moves < 9) {
            val opponent = if (current == 1) 2 else 1
            val move = Minimax.bestMove(board, player = current, opponent = opponent, maxDepth = 9)
            board.apply(move, current)
            moves++
            // terminalScore(opponent) reflects the position from the side about to move next:
            // WIN_SCORE for `current` having just won means -WIN_SCORE from `opponent`'s view.
            val result = board.terminalScore(opponent)
            if (result != null) {
                assertEquals(0, result, "perfect play must never produce a winner")
                return
            }
            current = opponent
        }
        // The board filled up with no terminal result observed above: also a draw.
    }
}
