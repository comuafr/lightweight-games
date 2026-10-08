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

    private fun sceneOf(game: SequenceGame): lg.core.scene.Scene {
        val scene = lg.core.scene.Scene()
        game.render(scene)
        return scene
    }

    /** The countdown digit on screen, or 0 when none is drawn. */
    private fun countdownDigitShown(game: SequenceGame): Int {
        val scene = sceneOf(game)
        for (i in 0 until scene.count) {
            if (scene.kind[i] == lg.core.scene.Prim.NUMBER) return scene.key[i]
        }
        return 0
    }

    @Test
    public fun everyRoundStartsWithAThreeTwoOneCountdown(): Unit {
        val game = newGame()
        assertEquals(3, countdownDigitShown(game), "the game opens on a 3")

        // 3 -> 2 -> 1, then the sequence plays (no digit any more), then the input phase.
        var seen = mutableListOf(countdownDigitShown(game))
        var guard = 0
        while (countdownDigitShown(game) != 0 && guard++ < 1000) {
            game.update(50)
            val d = countdownDigitShown(game)
            if (d != 0 && d != seen.last()) seen.add(d)
        }
        assertEquals(listOf(3, 2, 1), seen)
        assertTrue(!game.debugIsInputPhase(), "the sequence is still to be shown after the countdown")

        // Answer correctly: the next round counts down again before showing the longer sequence.
        game.debugSkipToInput()
        game.debugTap(game.debugSequenceValue(0))
        assertEquals(2, game.debugSequenceLength())
        assertEquals(3, countdownDigitShown(game), "the next round also opens on a 3")
        assertTrue(!game.debugIsInputPhase())
    }

    @Test
    public fun tapsDuringTheCountdownAreIgnoredAndDoNotLoseTheGame(): Unit {
        val game = newGame()
        game.debugSkipToInput()
        game.debugTap(game.debugSequenceValue(0)) // round 1 done, countdown for round 2 starts
        val wrongPad = (game.debugSequenceValue(0) + 1) % 4
        game.debugTap(wrongPad) // a late tap during the countdown
        assertEquals(GameStatus.Running, game.status)
        assertEquals(0, game.debugInputIndex())
    }
}
