package lg.core.games.bird

import lg.core.GameStatus
import lg.core.input.Gesture
import lg.core.input.GestureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

public class BirdRulesTest {
    private fun newGame(seed: Long = 1L): BirdGame {
        val game = BirdGame()
        game.resize(360f, 640f)
        game.reset(seed)
        return game
    }

    private fun tap(game: BirdGame) {
        val g = Gesture()
        g.kind = GestureKind.TAP
        g.x = 100f
        g.y = 100f
        game.onGesture(g)
    }

    @Test
    public fun birdStartsAtRestAndDoesNotFallBeforeFirstTap(): Unit {
        val game = newGame()
        assertFalse(game.debugLaunched())
        val yBefore = game.debugBirdY()
        game.update(200)
        assertEquals(yBefore, game.debugBirdY())
        assertFalse(game.wantsFrames)
    }

    @Test
    public fun firstTapLaunchesAndAppliesUpwardImpulse(): Unit {
        val game = newGame()
        tap(game)
        assertTrue(game.debugLaunched())
        assertTrue(game.debugBirdVY() < 0f)
        assertTrue(game.wantsFrames)
    }

    @Test
    public fun fallingUnderGravityIncreasesDownwardVelocityOverTime(): Unit {
        val game = newGame()
        tap(game)
        val vyAfterTap = game.debugBirdVY()
        game.update(500)
        assertTrue(game.debugBirdVY() > vyAfterTap)
    }

    @Test
    public fun passingAPipeScoresOnePoint(): Unit {
        val game = newGame()
        tap(game)
        val birdX = game.debugBirdX()
        val gapCenter = game.debugBirdY()
        // Comfortably past the bird already (pipe's right edge well left of
        // the bird's left edge), so this update both avoids a collision and
        // satisfies the "pipe fully passed" scoring condition immediately.
        game.debugForcePipe(0, birdX - 80f, gapCenter, scored = false)
        game.update(16)
        assertEquals(1, game.score)
        assertEquals(GameStatus.Running, game.status)
    }

    @Test
    public fun collidingWithAPipeEndsTheGame(): Unit {
        val game = newGame()
        tap(game)
        val birdX = game.debugBirdX()
        // Gap far away from the bird's current y, pipe overlapping the bird's x.
        game.debugForcePipe(0, birdX, game.debugCeilingY() + 5f)
        game.update(16)
        assertTrue(game.status is GameStatus.Over)
        assertEquals(0, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun hittingTheGroundEndsTheGame(): Unit {
        val game = newGame()
        game.debugSetBird(game.debugBirdX(), game.debugGroundY() - 1f, vy = 500f, launched = true)
        game.update(16)
        assertTrue(game.status is GameStatus.Over)
    }

    @Test
    public fun hittingTheCeilingEndsTheGame(): Unit {
        val game = newGame()
        game.debugSetBird(game.debugBirdX(), game.debugCeilingY() + 1f, vy = -500f, launched = true)
        game.update(16)
        assertTrue(game.status is GameStatus.Over)
    }

    @Test
    public fun scoreNeverGoesNegative(): Unit {
        val game = newGame()
        assertTrue(game.score >= 0)
    }
}
