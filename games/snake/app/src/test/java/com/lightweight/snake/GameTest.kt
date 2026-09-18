package com.lightweight.snake

import com.lightweight.snake.Game.Dir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM unit tests for the pure Snake logic. No emulator. Run with: ./gradlew test
 * A seeded RNG keeps every scenario deterministic.
 */
class GameTest {

    /** Deterministic RNG: replays a fixed sequence of doubles in [0,1). */
    private fun seeded(vararg values: Double): () -> Double {
        var i = 0
        return { values[i++ % values.size] }
    }

    private fun foodIsOnSnake(g: Game): Boolean {
        for (i in 0 until g.length) {
            if (g.segmentX(i) == g.foodX && g.segmentY(i) == g.foodY) return true
        }
        return false
    }

    @Test
    fun newGameStartsClean() {
        val g = Game(10, 10, seeded(0.0))
        assertEquals(0L, g.score)
        assertFalse(g.over)
        assertEquals(3, g.length)
        assertEquals(Dir.RIGHT, g.dir)
        assertEquals(4, g.headX)   // cols/2 - 1
        assertEquals(5, g.headY)   // rows/2
    }

    @Test
    fun movingAdvancesHeadAndKeepsLength() {
        val g = Game(10, 10, seeded(0.0)) // food parks at (0,0), out of the path
        g.tick()
        assertEquals(5, g.headX)
        assertEquals(5, g.headY)
        assertEquals(3, g.length)
        assertEquals(0L, g.score)
    }

    @Test
    fun eatingFoodGrowsAndScores() {
        val g = Game(10, 10, seeded(0.0))
        g.placeFoodAt(g.headX + 1, g.headY)   // food directly ahead
        val ate = g.tick()
        assertTrue(ate)
        assertEquals(4, g.length)
        assertEquals(1L, g.score)
        assertFalse(g.over)
    }

    @Test
    fun reversalIntoNeckIsIgnored() {
        val g = Game(10, 10, seeded(0.0))     // moving RIGHT
        g.turn(Dir.LEFT)                       // 180° reversal — must be ignored
        g.tick()
        assertEquals(Dir.RIGHT, g.dir)
        assertEquals(5, g.headX)
        assertFalse(g.over)
    }

    @Test
    fun hittingWallEndsGame() {
        val g = Game(10, 10, seeded(0.0))     // head at x=4, moving RIGHT
        repeat(5) { g.tick() }                 // reach x=9 (last column)
        assertFalse(g.over)
        assertEquals(9, g.headX)
        g.tick()                               // step into the wall
        assertTrue(g.over)
    }

    @Test
    fun runningIntoBodyEndsGame() {
        val g = Game(10, 10, seeded(0.0))
        g.placeFoodAt(5, 5); g.tick()          // eat -> length 4, head (5,5)
        g.placeFoodAt(6, 5); g.tick()          // eat -> length 5, head (6,5)
        g.turn(Dir.UP); g.tick()               // head (6,4)
        g.turn(Dir.LEFT); g.tick()             // head (5,4)
        g.turn(Dir.DOWN); g.tick()             // head (5,5) bites the body
        assertTrue(g.over)
    }

    @Test
    fun followingOwnTailIsAllowed() {
        val g = Game(10, 10, seeded(0.0))
        g.placeFoodAt(5, 5); g.tick()          // length 4, head (5,5)
        g.turn(Dir.UP); g.tick()               // head (5,4)
        g.turn(Dir.LEFT); g.tick()             // head (4,4)
        g.turn(Dir.DOWN); g.tick()             // head (4,5) = vacating tail -> OK
        assertFalse(g.over)
    }

    @Test
    fun foodNeverSpawnsOnSnake() {
        // Drive the snake around, eating repeatedly, and check food stays off-body.
        val g = Game(8, 8, seeded(0.1, 0.37, 0.62, 0.83, 0.05, 0.5, 0.95))
        repeat(40) {
            g.turn(if (it % 8 < 4) Dir.RIGHT else Dir.LEFT)
            g.tick()
            if (g.over) g.reset()
            assertFalse(foodIsOnSnake(g), "food landed on the snake")
        }
    }

    @Test
    fun resetRestoresInitialState() {
        val g = Game(10, 10, seeded(0.0))
        repeat(3) { g.tick() }
        g.reset()
        assertEquals(0L, g.score)
        assertFalse(g.over)
        assertEquals(3, g.length)
        assertEquals(4, g.headX)
        assertEquals(Dir.RIGHT, g.dir)
    }
}
