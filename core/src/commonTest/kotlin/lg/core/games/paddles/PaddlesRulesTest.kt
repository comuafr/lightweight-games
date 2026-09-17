package lg.core.games.paddles

import lg.core.GameStatus
import lg.core.input.PointerPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class PaddlesRulesTest {
    // Portrait: pfW=300, pfH=500 (600 - HUD 32*... actually height=532, HUD=32 -> pfH=500), mainLength=500, crossLength=300.
    private fun newGame(seed: Long = 1L, players: Int = 2): PaddlesGame {
        val game = PaddlesGame()
        game.resize(300f, 532f)
        game.reset(seed, players)
        return game
    }

    @Test
    public fun paddleAFollowsAPointerInsideItsOwnZone(): Unit {
        val game = newGame()
        val zoneA = game.debugZoneA()
        val x = zoneA.x + zoneA.w * 0.75f
        val y = zoneA.y + zoneA.h * 0.5f
        game.onPointer(1, PointerPhase.DOWN, x, y)
        assertFloatEquals(x, game.debugPaddleACross(), 0.01f)

        val moveX = zoneA.x + zoneA.w * 0.25f
        game.onPointer(1, PointerPhase.MOVE, moveX, y)
        assertFloatEquals(moveX, game.debugPaddleACross(), 0.01f)
    }

    @Test
    public fun pointerInZoneBDoesNotMovePaddleAAndViceVersa(): Unit {
        val game = newGame(players = 2)
        val paddleABefore = game.debugPaddleACross()
        val zoneB = game.debugZoneB()
        game.onPointer(2, PointerPhase.DOWN, zoneB.x + zoneB.w / 2f, zoneB.y + zoneB.h / 2f)
        assertFloatEquals(paddleABefore, game.debugPaddleACross(), 0.01f)

        val paddleBBefore = game.debugPaddleBCross()
        val zoneA = game.debugZoneA()
        game.onPointer(3, PointerPhase.DOWN, zoneA.x + zoneA.w / 2f, zoneA.y + zoneA.h / 2f)
        assertEquals(paddleBBefore, game.debugPaddleBCross())
    }

    @Test
    public fun onePlayerModeIgnoresPointersInPaddleBsZone(): Unit {
        val game = newGame(players = 1)
        val before = game.debugPaddleBCross()
        val zoneB = game.debugZoneB()
        game.onPointer(9, PointerPhase.DOWN, zoneB.x + zoneB.w * 0.9f, zoneB.y + zoneB.h / 2f)
        assertFloatEquals(before, game.debugPaddleBCross(), 0.01f)
    }

    @Test
    public fun ballPastPaddleAAwardsAPointToPlayerB(): Unit {
        val game = newGame()
        game.debugSetBall(main = -50f, cross = 150f, vMain = -50f, vCross = 0f)
        game.update(4)
        assertEquals(1, game.debugScoreB())
        assertEquals(0, game.debugScoreA())
        assertTrue(game.debugBallMain() in 200f..300f) // re-served near the center (mainLength/2 = 250)
    }

    @Test
    public fun ballPastPaddleBAwardsAPointToPlayerA(): Unit {
        val game = newGame()
        game.debugSetBall(main = 550f, cross = 150f, vMain = 50f, vCross = 0f)
        game.update(4)
        assertEquals(1, game.debugScoreA())
        assertEquals(0, game.debugScoreB())
    }

    @Test
    public fun matchEndsWhenAPlayerReachesTheTarget(): Unit {
        val game = newGame()
        repeat(PaddlesGame.WIN_SCORE_TARGET) {
            game.debugSetBall(main = -50f, cross = 150f, vMain = -50f, vCross = 0f)
            game.update(4)
        }
        assertTrue(game.status is GameStatus.Over)
        assertEquals(2, (game.status as GameStatus.Over).winner)
        assertEquals(PaddlesGame.WIN_SCORE_TARGET, game.debugScoreB())
    }

    @Test
    public fun aiPaddleMovesTowardTheBallsCrossPosition(): Unit {
        val game = newGame(players = 1)
        val start = game.debugPaddleBCross()
        game.debugSetBall(main = 250f, cross = 280f, vMain = 0f, vCross = 0f)
        game.update(64)
        val after = game.debugPaddleBCross()
        assertTrue(after > start, "AI paddle should move toward the ball ($start -> $after)")
        assertTrue(after <= 280f)
    }

    private fun assertFloatEquals(expected: Float, actual: Float, tolerance: Float) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "expected $expected but was $actual (tolerance $tolerance)",
        )
    }
}
