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
        /** Lowest row of the first level (the one the ball meets first). */
        const val BOTTOM_ROW = 5
    }

    private fun newGame(seed: Long = 1L): BreakoutGame {
        val game = BreakoutGame()
        game.reset(seed)
        game.resize(VIEW_W, VIEW_H)
        // A game always opens on the level-choice screen; pick level 1, the only one open at first.
        tap(game, game.debugChoiceTileCenterX(1), game.debugChoiceTileCenterY(1))
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
    public fun clearingAllBricksStartsLevelTwoWithItsOwnLayout(): Unit {
        val game = newGame()
        val total = game.debugBricksRemaining()
        assertEquals(48, total) // level 1: six full rows of eight
        for (row in 0 until BreakoutGame.BRICK_ROWS) {
            for (col in 0 until BreakoutGame.BRICK_COLS) {
                game.debugDestroyBrick(row, col)
            }
        }

        assertEquals(2, game.debugLevel())
        // Level 2 is a checkerboard: 24 bricks in the first six rows, each one hit.
        assertEquals(24, game.debugBricksRemaining())
        assertEquals(1, game.debugHitPoints(0, 0))
        assertEquals(0, game.debugHitPoints(0, 1))
        // 48 bricks at level 1 (10 pts each) + the level-clear bonus at the new level (2).
        assertEquals(total * 10 + 50 * 2, game.score)
        assertFalse(game.debugBallInFlight())
    }

    @Test
    public fun everyLevelCanBeClearedAndLoopsAfterTheTenth(): Unit {
        val game = newGame()
        repeat(12) {
            val level = game.debugLevel()
            assertTrue(game.debugBricksRemaining() > 0, "level $level must have destructible bricks")
            for (row in 0 until BreakoutGame.BRICK_ROWS) {
                for (col in 0 until BreakoutGame.BRICK_COLS) game.debugDestroyBrick(row, col)
            }
            assertEquals(level + 1, game.debugLevel(), "level $level must end once its destructible bricks are gone")
        }
        assertEquals(13, game.debugLevel())
    }

    @Test
    public fun toughBricksNeedSeveralHitsAndSteelNeverBreaks(): Unit {
        val game = newGame()
        // Jump to level 4 (top row = two-hit bricks) and level 5 (steel pillars).
        fun clearLevel() {
            for (row in 0 until BreakoutGame.BRICK_ROWS) {
                for (col in 0 until BreakoutGame.BRICK_COLS) game.debugDestroyBrick(row, col)
            }
        }
        repeat(3) { clearLevel() }
        assertEquals(4, game.debugLevel())
        assertEquals(2, game.debugHitPoints(0, 0))
        clearLevel()
        assertEquals(5, game.debugLevel())
        assertEquals(BreakoutGame.STEEL, game.debugHitPoints(1, 1))

        // Hit the steel pillar from below: it stays, the ball bounces.
        val x = game.debugBrickLeft(1) + game.debugBrickWidth() / 2f
        val y = game.debugBrickTop(4) + game.debugBrickHeight() + 5f
        game.debugSetBall(x = x, y = y, vx = 0f, vy = -220f, inFlight = true)
        val remaining = game.debugBricksRemaining()
        game.update(30)
        assertEquals(BreakoutGame.STEEL, game.debugHitPoints(4, 1))
        assertEquals(remaining, game.debugBricksRemaining())
        assertTrue(game.debugBallVY() > 0f, "ball must bounce back off the steel")
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

    // --- Start position -----------------------------------------------------------------------

    @Test
    public fun paddleStartsInTheMiddleWhenTheSizeIsKnownBeforeTheGameStarts(): Unit {
        // GameSession tells the game its fixed size once, before any start/restart.
        val game = BreakoutGame()
        game.resize(VIEW_W, VIEW_H)
        game.reset(1L)
        assertEquals(VIEW_W / 2f, game.debugPaddleX(), 0.01f)
        assertEquals(VIEW_W / 2f, game.debugBallX(), 0.01f)
        assertEquals(game.debugPaddleTop() - 6f, game.debugBallY(), 0.01f)
    }

    // --- Capsules: bonus and malus --------------------------------------------------------------

    /** A ball hanging still in the middle of the zone, so only the timers and capsules move. */
    private fun floatingBallGame(vy: Float = 0f): BreakoutGame {
        val game = newGame()
        game.debugSetBall(x = VIEW_W / 2f, y = 350f, vx = 0f, vy = vy, inFlight = true)
        return game
    }

    @Test
    public fun multiBallAddsTwoBallsAndLosingOneDoesNotCostALife(): Unit {
        val game = floatingBallGame(vy = -100f)
        game.debugApplyEffect(BreakoutGame.TYPE_MULTI)
        assertEquals(3, game.debugActiveBalls())
        val lives = game.debugLives()
        // Let the balls fly: they leave through the top only by bouncing, so none is lost yet.
        game.update(50)
        assertEquals(lives, game.debugLives())
    }

    @Test
    public fun lifeIsLostOnlyWhenTheLastBallFalls(): Unit {
        val game = newGame()
        game.debugSetBall(x = VIEW_W / 2f, y = VIEW_H + 50f, vx = 0f, vy = 20f, inFlight = true)
        game.debugApplyEffect(BreakoutGame.TYPE_MULTI)
        val lives = game.debugLives()
        game.update(50)
        // All three copies sit below the zone, so the life goes now, effects and extras with it.
        assertEquals(lives - 1, game.debugLives())
        assertEquals(1, game.debugActiveBalls())
        assertFalse(game.debugBallInFlight())
    }

    @Test
    public fun bigBallGrowsThenReturnsToNormalSize(): Unit {
        val game = floatingBallGame()
        game.debugApplyEffect(BreakoutGame.TYPE_BIG_BALL)
        assertTrue(game.debugBallRadius() > 6f * 1.5f)
        repeat(260) { game.update(50) } // 13 s > 12 s
        assertEquals(6f, game.debugBallRadius(), 0.01f)
        assertEquals(0, game.debugEffectLeftMs(BreakoutGame.TYPE_BIG_BALL))
    }

    @Test
    public fun fastAndSlowScaleTheBallSpeedAndCancelEachOther(): Unit {
        val game = floatingBallGame(vy = -100f)
        game.debugApplyEffect(BreakoutGame.TYPE_FAST)
        assertEquals(140f, game.debugBallSpeed(), 0.5f)
        game.debugApplyEffect(BreakoutGame.TYPE_SLOW)
        assertEquals(70f, game.debugBallSpeed(), 0.5f)
        assertEquals(0, game.debugEffectLeftMs(BreakoutGame.TYPE_FAST))
    }

    @Test
    public fun speedGoesBackToNormalWhenTheEffectEnds(): Unit {
        val game = floatingBallGame(vy = -1f)
        game.debugApplyEffect(BreakoutGame.TYPE_SLOW)
        assertEquals(0.7f, game.debugBallSpeed(), 0.01f)
        repeat(220) { game.update(50) } // 11 s > 10 s
        assertEquals(1f, game.debugBallSpeed(), 0.01f)
    }

    @Test
    public fun biggerAndSmallerPaddleReplaceEachOther(): Unit {
        val game = floatingBallGame()
        val normal = game.debugPaddleWidth()
        game.debugApplyEffect(BreakoutGame.TYPE_BIG_PADDLE)
        assertEquals(normal * 1.5f, game.debugPaddleWidth(), 0.01f)
        game.debugApplyEffect(BreakoutGame.TYPE_SMALL_PADDLE)
        assertEquals(normal * 0.62f, game.debugPaddleWidth(), 0.01f)
        assertEquals(0, game.debugEffectLeftMs(BreakoutGame.TYPE_BIG_PADDLE))
    }

    @Test
    public fun extraLifeIsCappedAtFive(): Unit {
        val game = floatingBallGame()
        repeat(6) { game.debugApplyEffect(BreakoutGame.TYPE_LIFE) }
        assertEquals(5, game.debugLives())
    }

    @Test
    public fun aCapsuleCaughtByThePaddleApplies(): Unit {
        val game = floatingBallGame()
        val lives = game.debugLives()
        game.debugSpawnCapsule(BreakoutGame.TYPE_LIFE, game.debugPaddleX(), game.debugPaddleTop() - 5f)
        assertEquals(1, game.debugCapsuleCount())
        game.update(50)
        assertEquals(lives + 1, game.debugLives())
        assertEquals(0, game.debugCapsuleCount())
    }

    @Test
    public fun aMissedCapsuleFallsOutAndDisappears(): Unit {
        val game = floatingBallGame()
        val lives = game.debugLives()
        game.debugSpawnCapsule(BreakoutGame.TYPE_LIFE, 10f, game.debugPaddleTop())
        repeat(200) { game.update(50) }
        assertEquals(0, game.debugCapsuleCount())
        assertEquals(lives, game.debugLives())
    }

    @Test
    public fun capsulesKeepComingEvenWithBadLuck(): Unit {
        val game = newGame()
        repeat(7) { game.debugDestroyBrick(0, it) }
        assertTrue(game.debugCapsuleCount() >= 1, "at least one capsule within every 7 bricks")
    }

    @Test
    public fun losingALifeDropsEveryEffect(): Unit {
        val game = floatingBallGame(vy = -100f)
        game.debugApplyEffect(BreakoutGame.TYPE_BIG_BALL)
        game.debugApplyEffect(BreakoutGame.TYPE_FAST)
        game.debugSetBall(x = VIEW_W / 2f, y = VIEW_H + 50f, vx = 0f, vy = 20f, inFlight = true)
        game.update(50)
        assertEquals(0, game.debugEffectLeftMs(BreakoutGame.TYPE_BIG_BALL))
        assertEquals(0, game.debugEffectLeftMs(BreakoutGame.TYPE_FAST))
        assertEquals(6f, game.debugBallRadius(), 0.01f)
    }

    // --- Bounce off bricks depends on where the ball hits --------------------------------------

    /** Ball just below the bottom row's brick in [col], moving straight up, hitting at [fraction] of the brick width (0 = left edge, 1 = right edge). */
    private fun hitBrickFrom(col: Int, fraction: Float): BreakoutGame {
        val game = newGame()
        val x = game.debugBrickLeft(col) + game.debugBrickWidth() * fraction
        val y = game.debugBrickTop(BOTTOM_ROW) + game.debugBrickHeight() + 5f
        game.debugSetBall(x = x, y = y, vx = 0f, vy = -220f, inFlight = true)
        game.update(30)
        return game
    }

    @Test
    public fun hittingTheLeftPartOfABrickSendsTheBallLeft(): Unit {
        val game = hitBrickFrom(col = 3, fraction = 0.1f)
        assertEquals(0, game.debugHitPoints(BOTTOM_ROW, 3))
        assertTrue(game.debugBallVX() < -20f, "vx was ${game.debugBallVX()}")
        assertTrue(game.debugBallVY() > 0f, "ball must come back down")
    }

    @Test
    public fun hittingTheRightPartOfABrickSendsTheBallRight(): Unit {
        val game = hitBrickFrom(col = 3, fraction = 0.9f)
        assertTrue(game.debugBallVX() > 20f, "vx was ${game.debugBallVX()}")
        assertTrue(game.debugBallVY() > 0f)
    }

    @Test
    public fun hittingTheCentreOfABrickKeepsTheBallStraight(): Unit {
        val game = hitBrickFrom(col = 3, fraction = 0.5f)
        assertEquals(0f, game.debugBallVX(), 5f)
        assertTrue(game.debugBallVY() > 0f)
    }

    @Test
    public fun theBrickBounceKeepsTheBallSpeed(): Unit {
        val left = hitBrickFrom(col = 3, fraction = 0.1f)
        val right = hitBrickFrom(col = 5, fraction = 0.8f)
        assertEquals(220f, left.debugBallSpeed(), 1f)
        assertEquals(220f, right.debugBallSpeed(), 1f)
    }

    // --- Level choice ---------------------------------------------------------------------------

    private fun tap(game: BreakoutGame, x: Float, y: Float) {
        val g = lg.core.input.Gesture()
        g.kind = lg.core.input.GestureKind.TAP
        g.x = x
        g.y = y
        game.onGesture(g)
    }

    @Test
    public fun theFirstGameShowsAllTenLevelsWithOnlyTheFirstOpen(): Unit {
        val game = BreakoutGame()
        game.resize(VIEW_W, VIEW_H)
        game.reset(1L)
        assertTrue(game.debugChoosing())
        assertEquals(1, game.progress)

        // Level 2 is shown but locked: tapping it does nothing.
        tap(game, game.debugChoiceTileCenterX(2), game.debugChoiceTileCenterY(2))
        assertTrue(game.debugChoosing())

        tap(game, game.debugChoiceTileCenterX(1), game.debugChoiceTileCenterY(1))
        assertFalse(game.debugChoosing())
        assertEquals(1, game.debugLevel())
    }

    @Test
    public fun reachingALevelRaisesTheProgress(): Unit {
        val game = newGame()
        for (row in 0 until BreakoutGame.BRICK_ROWS) {
            for (col in 0 until BreakoutGame.BRICK_COLS) game.debugDestroyBrick(row, col)
        }
        assertEquals(2, game.progress)
    }

    @Test
    public fun withProgressTheGameOffersALevelChoiceAndAReachedTileStartsThatLevel(): Unit {
        val game = BreakoutGame()
        game.restoreProgress(4)
        game.resize(VIEW_W, VIEW_H)
        game.reset(1L)
        assertTrue(game.debugChoosing())
        assertFalse(game.wantsFrames)

        tap(game, game.debugChoiceTileCenterX(3), game.debugChoiceTileCenterY(3))
        assertFalse(game.debugChoosing())
        assertEquals(3, game.debugLevel())
        assertEquals(3, game.debugLives())
        assertEquals(0, game.score)
        assertEquals(VIEW_W / 2f, game.debugPaddleX(), 0.01f)
    }

    @Test
    public fun aLevelNotReachedYetCannotBeChosen(): Unit {
        val game = BreakoutGame()
        game.restoreProgress(3)
        game.resize(VIEW_W, VIEW_H)
        game.reset(1L)
        tap(game, game.debugChoiceTileCenterX(8), game.debugChoiceTileCenterY(8))
        assertTrue(game.debugChoosing())
        tap(game, 1f, 1f) // outside every tile
        assertTrue(game.debugChoosing())
    }

    @Test
    public fun touchesDoNotLaunchTheBallBehindTheChoiceScreen(): Unit {
        val game = BreakoutGame()
        game.restoreProgress(2)
        game.resize(VIEW_W, VIEW_H)
        game.reset(1L)
        game.onPointer(0, PointerPhase.DOWN, 100f, 100f)
        assertFalse(game.debugBallInFlight())
    }

    @Test
    public fun restartingAfterGameOverOffersTheChoiceAgain(): Unit {
        val game = BreakoutGame()
        game.restoreProgress(5)
        game.resize(VIEW_W, VIEW_H)
        game.reset(1L)
        tap(game, game.debugChoiceTileCenterX(5), game.debugChoiceTileCenterY(5))
        assertEquals(5, game.debugLevel())
        game.reset(2L)
        assertTrue(game.debugChoosing())
        assertEquals(5, game.progress)
    }
}
