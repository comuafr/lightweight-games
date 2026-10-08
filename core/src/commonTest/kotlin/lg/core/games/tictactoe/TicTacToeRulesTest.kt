package lg.core.games.tictactoe

import lg.core.GameStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class TicTacToeRulesTest {
    private fun newGame(seed: Long = 1L, players: Int = 2): TicTacToeGame {
        val game = TicTacToeGame()
        game.resize(300f, 332f)
        game.reset(seed, players)
        return game
    }

    @Test
    public fun winsOnARow(): Unit {
        val game = newGame()
        game.debugPlayMove(0, 1)
        game.debugPlayMove(3, 2)
        game.debugPlayMove(1, 1)
        game.debugPlayMove(4, 2)
        game.debugPlayMove(2, 1) // row 0,1,2
        assertTrue(game.status is GameStatus.Over)
        assertEquals(1, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun winsOnAColumn(): Unit {
        val game = newGame()
        game.debugPlayMove(0, 1)
        game.debugPlayMove(1, 2)
        game.debugPlayMove(3, 1)
        game.debugPlayMove(2, 2)
        game.debugPlayMove(6, 1) // column 0,3,6
        assertTrue(game.status is GameStatus.Over)
        assertEquals(1, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun winsOnADiagonal(): Unit {
        val game = newGame()
        game.debugPlayMove(0, 1)
        game.debugPlayMove(1, 2)
        game.debugPlayMove(4, 1)
        game.debugPlayMove(2, 2)
        game.debugPlayMove(8, 1) // diagonal 0,4,8
        assertTrue(game.status is GameStatus.Over)
        assertEquals(1, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun fullBoardWithNoLineIsADraw(): Unit {
        val game = newGame()
        // X O X / X O O / O X X -> full, no line.
        val xs = intArrayOf(0, 2, 3, 7, 8)
        val os = intArrayOf(1, 4, 5, 6)
        for (i in xs) game.debugSetCell(i, 1)
        for (i in os) game.debugSetCell(i, 2)
        // Play the final move through the real path so win/draw detection runs.
        game.debugSetCell(8, 0)
        game.debugSetCurrentPlayer(1)
        game.debugPlayMove(8, 1)
        assertTrue(game.status is GameStatus.Over)
        assertEquals(GameStatus.DRAW, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun aiBlocksAnImmediateHumanWin(): Unit {
        val game = newGame(players = 1)
        // X X . / O . . / . . . -> AI (O) must block at index 2.
        game.debugSetCell(0, 1)
        game.debugSetCell(1, 1)
        game.debugSetCell(3, 2)
        assertEquals(2, game.debugAiSuggestedMove())
    }

    @Test
    public fun aiTakesAnImmediateWin(): Unit {
        val game = newGame(players = 1)
        // O O . / X X . -> AI (O) must win at index 2.
        game.debugSetCell(0, 2)
        game.debugSetCell(1, 2)
        game.debugSetCell(3, 1)
        game.debugSetCell(4, 1)
        assertEquals(2, game.debugAiSuggestedMove())
    }

    @Test
    public fun aiMovesAfterADelayInOnePlayerMode(): Unit {
        val game = newGame(players = 1)
        game.debugPlayMove(0, 1) // human plays X; AI (O) turn is now pending
        assertEquals(2, game.debugCurrentPlayer())
        assertTrue(game.wantsFrames)

        game.update(100)
        // Still within the delay: AI has not moved yet.
        var oCount = 0
        for (i in 0 until TicTacToeGame.CELL_COUNT) if (game.debugCell(i) == 2) oCount++
        assertEquals(0, oCount)

        game.update(300) // total 400ms >= AI_DELAY_MS
        oCount = 0
        for (i in 0 until TicTacToeGame.CELL_COUNT) if (game.debugCell(i) == 2) oCount++
        assertEquals(1, oCount)
    }
}
