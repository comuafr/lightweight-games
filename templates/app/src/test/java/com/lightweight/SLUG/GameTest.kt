package com.lightweight.{{SLUG_PKG}}

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TEMPLATE — JVM unit tests for the pure game logic. No emulator required.
 * Run with: ./gradlew test
 *
 * Use a seeded RNG so tests are deterministic.
 */
class GameTest {

    /** Deterministic RNG: replays a fixed sequence of doubles in [0,1). */
    private fun seeded(vararg values: Double): () -> Double {
        var i = 0
        return { values[i++ % values.size] }
    }

    @Test
    fun newGameStartsClean() {
        val g = Game(seeded(0.0))
        assertEquals(0L, g.score)
        assertTrue(!g.over)
    }

    @Test
    fun rollBelowStaysInRange() {
        val g = Game(seeded(0.0, 0.5, 0.999))
        repeat(10) {
            val v = g.rollBelow(7)
            assertTrue(v in 0..6)
        }
    }

    // TODO: add tests for the real rules — collisions, scoring, win/lose,
    // RNG fairness invariants, and boundary conditions.
}
