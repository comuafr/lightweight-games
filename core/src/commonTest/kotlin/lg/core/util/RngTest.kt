package lg.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class RngTest {
    @Test
    public fun sameSeedProducesSameSequence(): Unit {
        val a = Rng(42L)
        val b = Rng(42L)
        for (i in 0 until 100) {
            assertEquals(a.nextLong(), b.nextLong())
        }
    }

    @Test
    public fun differentSeedsProduceDifferentSequences(): Unit {
        val a = Rng(1L)
        val b = Rng(2L)
        var anyDifferent = false
        for (i in 0 until 20) {
            if (a.nextLong() != b.nextLong()) anyDifferent = true
        }
        assertTrue(anyDifferent)
    }

    @Test
    public fun nextIntStaysWithinBound(): Unit {
        val rng = Rng(7L)
        val bound = 17
        for (i in 0 until 10_000) {
            val v = rng.nextInt(bound)
            assertTrue(v in 0 until bound)
        }
    }

    @Test
    public fun nextFloatStaysWithinUnitRange(): Unit {
        val rng = Rng(9L)
        for (i in 0 until 10_000) {
            val v = rng.nextFloat()
            assertTrue(v >= 0f && v < 1f)
        }
    }

    @Test
    public fun nextBooleanProducesBothValues(): Unit {
        val rng = Rng(3L)
        var sawTrue = false
        var sawFalse = false
        for (i in 0 until 200) {
            if (rng.nextBoolean()) sawTrue = true else sawFalse = true
        }
        assertTrue(sawTrue && sawFalse)
    }

    @Test
    public fun shuffleIsDeterministicForSameSeed(): Unit {
        val a = IntArray(20) { it }
        val b = IntArray(20) { it }
        Rng(123L).shuffle(a)
        Rng(123L).shuffle(b)
        assertEquals(a.toList(), b.toList())
    }

    @Test
    public fun shufflePreservesElements(): Unit {
        val a = IntArray(30) { it }
        Rng(55L).shuffle(a)
        assertEquals((0 until 30).toList(), a.toList().sorted())
    }
}
