package lg.core.games.reversi

import lg.core.GameStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

public class ReversiRulesTest {
    private fun newGame(seed: Long = 1L, players: Int = 2): ReversiGame {
        val game = ReversiGame()
        game.resize(360f, 392f)
        game.reset(seed, players)
        return game
    }

    @Test
    public fun initialBoardHasFourDiscs(): Unit {
        val game = newGame()
        assertEquals(4, game.debugCountDiscs(1) + game.debugCountDiscs(2))
        assertEquals(2, game.debugCountDiscs(1))
        assertEquals(2, game.debugCountDiscs(2))
        assertEquals(4, game.score)
    }

    @Test
    public fun onlyMovesThatFlipAtLeastOneDiscAreLegal(): Unit {
        val game = newGame()
        // Corner is never legal from the initial position (no line to flip).
        assertFalse(game.debugIsLegalMove(0, 1))
        // The three classic opening moves for black are legal.
        assertTrue(game.debugIsLegalMove(2 * ReversiGame.SIZE + 3, 1)) // (row 2, col 3)
        assertTrue(game.debugIsLegalMove(3 * ReversiGame.SIZE + 2, 1)) // (row 3, col 2)
    }

    @Test
    public fun playingALegalMoveFlipsTheCapturedLine(): Unit {
        val game = newGame()
        val move = 2 * ReversiGame.SIZE + 3 // (row 2, col 3): flips (row 3, col 3) from white to black
        assertEquals(2, game.debugCell(3 * ReversiGame.SIZE + 3))
        game.debugPlayMove(move)
        assertEquals(1, game.debugCell(move))
        assertEquals(1, game.debugCell(3 * ReversiGame.SIZE + 3))
        assertEquals(4, game.debugCountDiscs(1))
        assertEquals(1, game.debugCountDiscs(2))
        assertEquals(5, game.score)
    }

    @Test
    public fun flipsWorkInAllEightDirections(): Unit {
        val game = newGame()
        // Build a board where a move at the center flips in all 8 directions.
        val cells = IntArray(ReversiGame.CELL_COUNT)
        val center = 4 * ReversiGame.SIZE + 4
        val dx = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
        val dy = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        for (d in 0 until 8) {
            val or = 4 + dx[d]
            val oc = 4 + dy[d]
            val pr = 4 + dx[d] * 2
            val pc = 4 + dy[d] * 2
            cells[or * ReversiGame.SIZE + oc] = 2 // opponent disc to flip
            cells[pr * ReversiGame.SIZE + pc] = 1 // anchoring player disc
        }
        game.debugSetBoard(cells)
        game.debugSetCurrentPlayer(1)
        assertTrue(game.debugIsLegalMove(center, 1))
        game.debugPlayMove(center)
        for (d in 0 until 8) {
            val or = 4 + dx[d]
            val oc = 4 + dy[d]
            assertEquals(1, game.debugCell(or * ReversiGame.SIZE + oc), "direction $d should have flipped")
        }
    }

    @Test
    public fun playerPassesWhenTheyHaveNoLegalMove(): Unit {
        val game = newGame()
        // Two isolated capture clusters, each anchored against a board edge so
        // the lone white disc in each cannot itself be used as a terminal for
        // any white move (its "far side" is off-board):
        //   cluster A, row 4: empty(4,5) - white(4,6) - black(4,7, edge col)
        //   cluster B, row 0: empty(0,2) - white(0,1) - black(0,0, edge col)
        // Everywhere else is empty. Black has two independent legal moves;
        // white (only 2 discs, both edge-trapped) has none anywhere.
        val cells = IntArray(ReversiGame.CELL_COUNT)
        cells[4 * ReversiGame.SIZE + 6] = 2
        cells[4 * ReversiGame.SIZE + 7] = 1
        cells[0 * ReversiGame.SIZE + 1] = 2
        cells[0 * ReversiGame.SIZE + 0] = 1
        game.debugSetBoard(cells)
        game.debugSetCurrentPlayer(1)

        val moveA = 4 * ReversiGame.SIZE + 5
        assertTrue(game.debugIsLegalMove(moveA, 1))
        assertFalse(game.debugHasAnyLegalMove(2))

        game.debugPlayMove(moveA)
        assertEquals(GameStatus.Running, game.status)
        assertEquals(1, game.debugCurrentPlayer()) // white had no move: black passes to itself
        assertTrue(game.debugIsLegalMove(0 * ReversiGame.SIZE + 2, 1)) // cluster B still playable
    }

    @Test
    public fun gameEndsWhenNeitherPlayerCanMoveAndWinnerIsMostDiscs(): Unit {
        val game = newGame()
        // Board almost entirely black, one empty cell and one white disc
        // positioned so black's move both is legal and fills the board
        // completely, ending the game immediately with black far ahead.
        val cells = IntArray(ReversiGame.CELL_COUNT) { 1 }
        val emptyIdx = 4 * ReversiGame.SIZE + 4
        val whiteIdx = 4 * ReversiGame.SIZE + 5
        cells[emptyIdx] = 0
        cells[whiteIdx] = 2
        game.debugSetBoard(cells)
        game.debugSetCurrentPlayer(1)
        assertTrue(game.debugIsLegalMove(emptyIdx, 1))

        game.debugPlayMove(emptyIdx)
        assertTrue(game.status is GameStatus.Over)
        val winner = (game.status as GameStatus.Over).winner
        assertEquals(1, winner)
        assertEquals(ReversiGame.CELL_COUNT, game.debugCountDiscs(1))
        assertEquals(0, game.debugCountDiscs(2))
    }

    @Test
    public fun aiPlaysALegalMoveWhenItIsItsTurn(): Unit {
        val game = newGame(players = 1)
        // After black's opening move, it's white's (AI's) turn.
        game.debugPlayMove(2 * ReversiGame.SIZE + 3)
        assertTrue(game.debugAwaitingAiMove())
        val suggested = game.debugAiSuggestedMove()
        assertTrue(suggested >= 0)
        assertTrue(game.debugIsLegalMove(suggested, 2))
        game.debugSkipAi()
        assertEquals(GameStatus.Running, game.status)
        assertEquals(1, game.debugCurrentPlayer())
    }
}
