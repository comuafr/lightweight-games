package lg.core.games.sequence

import lg.core.GameStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class SequenceRulesTest {
    private fun newGame(seed: Long = 1L): SequenceGame {
        val game = SequenceGame()
        game.resize(400f, 432f)
        game.reset(seed)
        return game
    }

    @Test
    public fun tapsDuringPlaybackAreIgnored(): Unit {
        val game = newGame()
        assertTrue(!game.debugIsInputPhase())
        val before = game.debugInputIndex()
        game.debugTap(0)
        assertEquals(before, game.debugInputIndex())
        assertEquals(GameStatus.Running, game.status)
    }

    @Test
    public fun correctReplayAdvancesAndScoresARound(): Unit {
        val game = newGame()
        game.debugSkipToInput()
        assertTrue(game.debugIsInputPhase())
        val length = game.debugSequenceLength()
        assertEquals(1, length)

        game.debugTap(game.debugSequenceValue(0))
        assertEquals(1, game.score)
        assertEquals(GameStatus.Running, game.status)
        assertEquals(2, game.debugSequenceLength()) // grew for the next round
    }

    @Test
    public fun wrongTapEndsTheGame(): Unit {
        val game = newGame()
        game.debugSkipToInput()
        val correct = game.debugSequenceValue(0)
        val wrong = (correct + 1) % SequenceGame.PAD_COUNT
        game.debugTap(wrong)
        assertTrue(game.status is GameStatus.Over)
        assertEquals(0, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun multiRoundReplayKeepsExtendingTheSequence(): Unit {
        val game = newGame()
        repeat(3) {
            game.debugSkipToInput()
            val length = game.debugSequenceLength()
            for (i in 0 until length) {
                game.debugTap(game.debugSequenceValue(i))
            }
            assertEquals(GameStatus.Running, game.status)
        }
        assertEquals(3, game.score)
        assertEquals(4, game.debugSequenceLength())
    }
}
