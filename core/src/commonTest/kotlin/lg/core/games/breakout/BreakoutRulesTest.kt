package lg.core.games.breakout

import lg.core.GameStatus
import lg.core.input.PointerPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

public class BreakoutRulesTest {
    private companion object {
        const val VIEW_W = 360f
        const val VIEW_H = 640f
        const val PADDLE_WIDTH_FRACTION = 0.22f
    }

    private fun newGame(seed: Long = 1L): BreakoutGame {
        val game = BreakoutGame()
        game.reset(seed)
        game.resize(VIEW_W, VIEW_H)
        return game
    }

    private fun paddleHalfWidth(): Float = VIEW_W * PADDLE_WIDTH_FRACTION / 2f

    @Test
    public fun ballWaitsOnPaddleUntilFirstPointerDown(): Unit {
        val game = newGame()
        assertFalse(game.debugBallInFlight())
        assertFalse(game.wantsFrames, "no frame loop needed while the ball rests on the paddle")

        game.onPointer(0, PointerPhase.DOWN, VIEW_W / 2f, VIEW_H - 50f)
        assertTrue(game.debugBallInFlight())
        assertTrue(game.wantsFrames)
    }

    @Test
    public fun wallBounceInvertsHorizontalVelocity(): Unit {
        val game = newGame()
        game.debugSetBall(x = 3f, y = 300f, vx = -50f, vy = 0f, inFlight = true)
        game.update(4)
        assertTrue(game.debugBallVX() > 0f, "vx must flip sign off the left wall")
        assertEquals(6f, game.debugBallX(), 0.01f) // clamped to the ball radius
    }

    @Test
    public fun brickHitDecrementsHitPointsAndScores(): Unit {
        val game = newGame()
        val brickLeft = game.debugBrickLeft(0)
        val brickTop = game.debugBrickTop(0)
        // Overlap brick (row 0, col 0) from just below it, moving up into it.
        game.debugSetBall(x = brickLeft + 5f, y = brickTop + game.debugBrickHeight() - 3f, vx = 0f, vy = -100f, inFlight = true)
        assertEquals(1, game.debugHitPoints(0, 0))
        val bricksBefore = game.debugBricksRemaining()

        game.update(4)

        assertEquals(0, game.debugHitPoints(0, 0))
        assertEquals(bricksBefore - 1, game.debugBricksRemaining())
        assertEquals(10 * game.debugLevel(), game.score)
    }

    @Test
    public fun losingTheBallDecrementsLivesAndReattachesIt(): Unit {
        val game = newGame()
        val livesBefore = game.debugLives()
        game.debugSetBall(x = VIEW_W / 2f, y = VIEW_H + 50f, vx = 0f, vy = 20f, inFlight = true)

        game.update(4)

        assertEquals(livesBefore - 1, game.debugLives())
        assertFalse(game.debugBallInFlight())
        assertEquals(game.debugPaddleX(), game.debugBallX(), 0.01f)
        assertEquals(game.debugPaddleTop() - 6f, game.debugBallY(), 0.01f)
        assertEquals(GameStatus.Running, game.status)
    }

    @Test
    public fun lastLifeLostEndsTheGame(): Unit {
        val game = newGame()
        game.debugSetLives(1)
        game.debugSetBall(x = VIEW_W / 2f, y = VIEW_H + 50f, vx = 0f, vy = 20f, inFlight = true)

        game.update(4)

        assertEquals(0, game.debugLives())
        assertTrue(game.status is GameStatus.Over)
        assertEquals(0, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun clearingAllBricksStartsLevelTwoWithBricksRestored(): Unit {
        val game = newGame()
        val total = BreakoutGame.BRICK_COLS * BreakoutGame.BRICK_ROWS
        for (row in 0 until BreakoutGame.BRICK_ROWS) {
            for (col in 0 until BreakoutGame.BRICK_COLS) {
                game.debugDestroyBrick(row, col)
            }
        }

        assertEquals(2, game.debugLevel())
        assertEquals(total, game.debugBricksRemaining())
        for (row in 0 until BreakoutGame.BRICK_ROWS) {
            for (col in 0 until BreakoutGame.BRICK_COLS) {
                assertEquals(1, game.debugHitPoints(row, col), "brick ($row,$col) must be restored for the new level")
            }
        }
        // 48 bricks at level 1 (10 pts each) + the level-clear bonus at the new level (2).
        assertEquals(total * 10 + 50 * 2, game.score)
        assertFalse(game.debugBallInFlight())
    }

    @Test
    public fun paddleMovesByTheDragDistanceAndIsClamped(): Unit {
        val game = newGame()
        val start = game.debugPaddleX()

        // Touching far from the paddle must not teleport it.
        game.onPointer(0, PointerPhase.DOWN, 5f, VIEW_H - 50f)
        assertEquals(start, game.debugPaddleX(), 0.01f)

        game.onPointer(0, PointerPhase.MOVE, 25f, VIEW_H - 50f)
        assertEquals(start + 20f, game.debugPaddleX(), 0.01f)

        game.onPointer(0, PointerPhase.MOVE, -1_000_000f, VIEW_H - 50f)
        assertEquals(paddleHalfWidth(), game.debugPaddleX(), 0.01f)

        game.onPointer(0, PointerPhase.MOVE, 1_000_000f, VIEW_H - 50f)
        assertEquals(VIEW_W - paddleHalfWidth(), game.debugPaddleX(), 0.01f)
    }

    @Test
    public fun aSecondFingerDoesNotSteerThePaddle(): Unit {
        val game = newGame()
        game.onPointer(0, PointerPhase.DOWN, 100f, VIEW_H - 50f)
        val x = game.debugPaddleX()
        game.onPointer(1, PointerPhase.DOWN, 300f, VIEW_H - 50f)
        game.onPointer(1, PointerPhase.MOVE, 350f, VIEW_H - 50f)
        assertEquals(x, game.debugPaddleX(), 0.01f)
    }
}
