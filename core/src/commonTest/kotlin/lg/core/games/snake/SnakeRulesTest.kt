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
    public fun tickStaysTheSameWhateverTheSnakeLength(): Unit {
        val game = newGame()
        // At the start, a 159ms update must not trigger a tick.
        val hx = game.debugHeadX()
        game.update(159)
        assertEquals(hx, game.debugHeadX())
        game.update(1)
        assertEquals(hx + 1, game.debugHeadX())

        // After eating many apples (a straight run of 12 fits the 20-cell row) the pace is still one step per 160 ms.
        for (i in 1..12) {
            game.debugPlaceFood(game.debugHeadX() + 1, game.debugHeadY())
            game.update(160)
            if (game.status !is GameStatus.Running) break
        }
        assertTrue(game.debugLength() > 12, "the snake must have grown a lot")
        val before = game.debugHeadX()
        game.update(159)
        assertEquals(before, game.debugHeadX(), "still no step before 160 ms")
        game.update(1)
        assertTrue(game.debugHeadX() != before, "one step after 160 ms")
    }

    @Test
    public fun bonusAppearsAfterItsDelayOnAFreeCell(): Unit {
        val game = newGame()
        assertEquals(-1, game.debugBonusX())
        game.debugForceNextBonusIn(500)
        game.update(400)
        assertEquals(-1, game.debugBonusX(), "not yet")
        game.update(160)
        assertTrue(game.debugBonusX() >= 0 && game.debugBonusY() >= 0, "bonus must have appeared")
        assertTrue(game.debugBonusLeftMs() in 1..SnakeGame.BONUS_LIFETIME_MS)
        assertTrue(game.debugBonusX() != game.debugFoodX() || game.debugBonusY() != game.debugFoodY())
    }

    @Test
    public fun bonusVanishesAfterItsLifetime(): Unit {
        val game = newGame()
        game.debugPlaceBonus(0, 0, leftMs = 300)
        game.update(200)
        assertTrue(game.debugBonusX() >= 0)
        game.update(200)
        assertEquals(-1, game.debugBonusX())
        assertEquals(0, game.debugBonusLeftMs())
    }

    @Test
    public fun eatingTheBonusGivesPointsAndGrowsWithoutMovingTheFood(): Unit {
        val game = newGame()
        val lengthBefore = game.debugLength()
        val hx = game.debugHeadX()
        val hy = game.debugHeadY()
        val foodX = game.debugFoodX()
        val foodY = game.debugFoodY()
        game.debugPlaceBonus(hx + 1, hy)
        game.update(160)
        assertEquals(SnakeGame.BONUS_POINTS, game.score)
        assertEquals(lengthBefore + 1, game.debugLength())
        assertEquals(-1, game.debugBonusX())
        assertEquals(foodX, game.debugFoodX())
        assertEquals(foodY, game.debugFoodY())
    }

    @Test
    public fun foodNeverRespawnsOnTheBonus(): Unit {
        val game = newGame()
        repeat(40) {
            val hx = game.debugHeadX()
            val hy = game.debugHeadY()
            game.debugPlaceBonus(0, 0)
            game.debugPlaceFood((hx + 1) % game.debugCols(), hy)
            game.update(160)
            assertTrue(game.debugFoodX() != game.debugBonusX() || game.debugFoodY() != game.debugBonusY() || game.debugBonusX() < 0)
        }
    }

    @Test
    public fun twoQuickSwipesCannotMakeAUTurnInsideOneTick(): Unit {
        val game = newGame()
        // Moving RIGHT: "up" then "left" before the next tick used to reverse the snake into itself.
        game.onGesture(gestureOf(GestureKind.SWIPE_UP))
        game.onGesture(gestureOf(GestureKind.SWIPE_LEFT))
        game.update(160)
        assertEquals(GameStatus.Running, game.status)
        game.update(160)
        assertEquals(GameStatus.Running, game.status, "the second turn is applied one tick later, not in the same tick")
    }

    @Test
    public fun queuedTurnsAreAppliedOneTickAtATime(): Unit {
        val game = newGame()
        val hx = game.debugHeadX()
        val hy = game.debugHeadY()
        game.onGesture(gestureOf(GestureKind.SWIPE_UP))
        game.onGesture(gestureOf(GestureKind.SWIPE_LEFT))
        game.update(160)
        assertEquals(hx, game.debugHeadX())
        assertEquals(hy - 1, game.debugHeadY())
        game.update(160)
        assertEquals(hx - 1, game.debugHeadX())
        assertEquals(hy - 1, game.debugHeadY())
    }

    @Test
    public fun aSwipeOppositeToTheQueuedTurnIsIgnored(): Unit {
        val game = newGame()
        val hy = game.debugHeadY()
        game.onGesture(gestureOf(GestureKind.SWIPE_UP))
        game.onGesture(gestureOf(GestureKind.SWIPE_DOWN)) // would reverse the queued "up"
        game.update(160)
        game.update(160)
        assertEquals(hy - 2, game.debugHeadY())
        assertEquals(GameStatus.Running, game.status)
    }
}
