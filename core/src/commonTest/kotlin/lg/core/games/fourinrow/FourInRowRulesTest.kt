package lg.core.games.fourinrow

import lg.core.GameStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class FourInRowRulesTest {
    private fun newGame(seed: Long = 1L, players: Int = 2): FourInRowGame {
        val game = FourInRowGame()
        game.resize(350f, 332f)
        game.reset(seed, players)
        return game
    }

    private fun dropAndLand(game: FourInRowGame, col: Int) {
        game.debugTapColumn(col)
        game.debugSkipAnimationAndAi()
    }

    @Test
    public fun discsStackViaGravity(): Unit {
        val game = newGame()
        dropAndLand(game, 0)
        assertEquals(1, game.debugCell(0, 0))
        assertEquals(1, game.debugHeightOfCol(0))

        dropAndLand(game, 0)
        assertEquals(2, game.debugCell(0, 1))
        assertEquals(2, game.debugHeightOfCol(0))
        assertEquals(GameStatus.Running, game.status)
    }

    @Test
    public fun fourInARowHorizontally(): Unit {
        val game = newGame()
        game.debugForceCell(0, 0, 1)
        game.debugForceCell(1, 0, 1)
        game.debugForceCell(2, 0, 1)
        dropAndLand(game, 3) // completes the row for player 1
        assertTrue(game.status is GameStatus.Over)
        assertEquals(1, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun fourInARowVertically(): Unit {
        val game = newGame()
        game.debugForceCell(0, 0, 1)
        game.debugForceCell(0, 1, 1)
        game.debugForceCell(0, 2, 1)
        dropAndLand(game, 0)
        assertTrue(game.status is GameStatus.Over)
        assertEquals(1, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun fourInARowDiagonallyUpward(): Unit {
        val game = newGame()
        // Winning diagonal (0,0)-(1,1)-(2,2)-(3,3); force the top three, drop the last piece at column 0.
        game.debugForceCell(1, 1, 1)
        game.debugForceCell(2, 2, 1)
        game.debugForceCell(3, 3, 1)
        dropAndLand(game, 0)
        assertTrue(game.status is GameStatus.Over)
        assertEquals(1, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun fourInARowDiagonallyDownward(): Unit {
        val game = newGame()
        // Winning diagonal (0,3)-(1,2)-(2,1)-(3,0); force the top three, drop the last piece at column 3.
        game.debugForceCell(0, 3, 1)
        game.debugForceCell(1, 2, 1)
        game.debugForceCell(2, 1, 1)
        dropAndLand(game, 3)
        assertTrue(game.status is GameStatus.Over)
        assertEquals(1, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun fullBoardWithNoWinnerIsADraw(): Unit {
        val game = newGame()
        // A hand-verified full-board fill with no 4-in-a-row in any direction
        // (rows alternate every cell; the alternation phase flips every 2 rows).
        for (c in 0 until FourInRowGame.COLS) {
            val maxRow = if (c == FourInRowGame.COLS - 1) FourInRowGame.ROWS - 2 else FourInRowGame.ROWS - 1
            for (r in 0..maxRow) {
                val player = 1 + ((c + (r / 2) % 2) % 2)
                game.debugForceCell(c, r, player)
            }
        }
        dropAndLand(game, FourInRowGame.COLS - 1) // fills the last remaining cell
        assertTrue(game.status is GameStatus.Over)
        assertEquals(0, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun aiBlocksAnImmediateHumanWin(): Unit {
        val game = newGame(players = 1)
        // Player 1 has three in a row at columns 0,1,2 on the bottom row; column 3 must be blocked.
        game.debugForceCell(0, 0, 1)
        game.debugForceCell(1, 0, 1)
        game.debugForceCell(2, 0, 1)
        assertEquals(3, game.debugAiSuggestedColumn())
    }

    @Test
    public fun aiTakesAnImmediateWinWhenAvailable(): Unit {
        val game = newGame(players = 1)
        game.debugForceCell(0, 0, 2)
        game.debugForceCell(1, 0, 2)
        game.debugForceCell(2, 0, 2)
        assertEquals(3, game.debugAiSuggestedColumn())
    }

    @Test
    public fun inputIsIgnoredWhileTheDropIsAnimating(): Unit {
        val game = newGame()
        game.debugTapColumn(0)
        assertTrue(game.debugIsAnimating())
        val heightBefore = game.debugHeightOfCol(1)
        game.debugTapColumn(1) // ignored: a drop is still animating
        assertEquals(heightBefore, game.debugHeightOfCol(1))
    }
}
