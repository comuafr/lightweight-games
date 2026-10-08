package lg.core.games.snake

import lg.core.GameStatus
import lg.core.input.Gesture
import lg.core.input.GestureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class SnakeRulesTest {
    private fun gestureOf(kind: GestureKind): Gesture {
        val g = Gesture()
        g.kind = kind
        g.x = 0f
        g.y = 0f
        g.pointerId = 0
        return g
    }

    private fun newGame(seed: Long = 1L): SnakeGame {
        val game = SnakeGame()
        game.resize(400f, 432f) // 20 cols, 32 pt HUD strip -> cellSize 20; rows = 400/20 = 20
        game.reset(seed)
        return game
    }

    @Test
    public fun movesRightEachTickByDefault(): Unit {
        val game = newGame()
        val startX = game.debugHeadX()
        val startY = game.debugHeadY()
        game.update(160) // exactly one BASE_TICK_MS tick at score 0
        assertEquals(startX + 1, game.debugHeadX())
        assertEquals(startY, game.debugHeadY())
    }

    @Test
    public fun growsAndScoresOnFoodWithForcedPlacement(): Unit {
        val game = newGame()
        val lengthBefore = game.debugLength()
        val hx = game.debugHeadX()
        val hy = game.debugHeadY()
        game.debugPlaceFood(hx + 1, hy) // directly in front of the head (moving RIGHT)
        game.update(160)
        assertEquals(1, game.score)
        assertEquals(lengthBefore + 1, game.debugLength())
    }

    @Test
    public fun cannotReverseDirection(): Unit {
        val game = newGame()
        val hx = game.debugHeadX()
        game.onGesture(gestureOf(GestureKind.SWIPE_LEFT)) // opposite of RIGHT: must be ignored
        game.update(160)
        assertEquals(hx + 1, game.debugHeadX())
        assertEquals(GameStatus.Running, game.status)
    }

    @Test
    public fun wrapsAroundTheEdgesLikeATorus(): Unit {
        val game = newGame()
        val cols = game.debugCols()
        // Keep moving RIGHT well past the board width: the snake must never die and must re-enter on the left.
        repeat(cols + 5) { game.update(160) }
        assertEquals(GameStatus.Running, game.status)
        assertTrue(game.debugHeadX() in 0 until cols)

        // Same vertically.
        game.onGesture(gestureOf(GestureKind.SWIPE_UP))
        repeat(game.debugRows() + 5) { game.update(160) }
        assertEquals(GameStatus.Running, game.status)
        assertTrue(game.debugHeadY() in 0 until game.debugRows())
    }

    @Test
    public fun diesOnSelfCollision(): Unit {
        val game = newGame()
        // Grow to length 6 by eating food placed directly ahead while moving RIGHT.
        repeat(3) {
            val hx = game.debugHeadX()
            val hy = game.debugHeadY()
            game.debugPlaceFood(hx + 1, hy)
            game.update(160)
        }
        assertEquals(6, game.debugLength())
        assertEquals(GameStatus.Running, game.status)

        // Box loop smaller than the body length forces a self-collision on the last turn.
        game.onGesture(gestureOf(GestureKind.SWIPE_DOWN))
        game.update(160)
        game.onGesture(gestureOf(GestureKind.SWIPE_LEFT))
        game.update(160)
        game.onGesture(gestureOf(GestureKind.SWIPE_UP))
        game.update(160)

        assertTrue(game.status is GameStatus.Over)
    }

    @Test
    public fun tickSpeedsUpAsScoreGrows(): Unit {
        val game = newGame()
        // At score 0, a 159ms update must not trigger a tick.
        val hx = game.debugHeadX()
        game.update(159)
        assertEquals(hx, game.debugHeadX())
        game.update(1)
        assertEquals(hx + 1, game.debugHeadX())
    }
}
