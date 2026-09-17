package lg.core.util

/**
 * Deterministic pseudo-random number generator (SplitMix64).
 *
 * Used by every game so that a given seed always produces the exact same
 * sequence of decisions (food placement, tile spawns, shuffles, AI
 * randomness), which is what [lg.core.session.GameSession] relies on to be
 * reproducible in tests (`fuzz/DeterminismTest`).
 */
public class Rng(seed: Long) {
    private var raw: Long = seed

    /**
     * Current internal state. Together with [fromState], this lets a
     * resumable game persist "where the sequence is" in its save data and
     * continue it exactly on restore, instead of restarting the sequence
     * from the original seed.
     */
    public val state: Long get() = raw

    /** Returns the next raw 64-bit value and advances the internal state. */
    public fun nextLong(): Long {
        raw += GOLDEN_GAMMA
        var z = raw
        z = (z xor (z ushr 30)) * 0xbf58476d1ce4e5b9UL.toLong()
        z = (z xor (z ushr 27)) * 0x94d049bb133111ebUL.toLong()
        return z xor (z ushr 31)
    }

    /** Returns a uniformly distributed integer in `[0, bound)`. [bound] must be > 0. */
    public fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be > 0" }
        // Simple modulo bias is negligible for the small bounds used by these games.
        val unsigned = nextLong() ushr 1 // clear sign bit, keep 63 bits of entropy
        return (unsigned % bound).toInt()
    }

    /** Returns a uniformly distributed float in `[0, 1)`. */
    public fun nextFloat(): Float {
        val bits = (nextLong() ushr 40).toInt() // top 24 bits
        return bits / (1 shl 24).toFloat()
    }

    /** Returns a uniformly distributed boolean. */
    public fun nextBoolean(): Boolean = (nextLong() and 1L) != 0L

    /** Shuffles [array] in place using a Fisher-Yates shuffle. */
    public fun shuffle(array: IntArray) {
        for (i in array.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            val tmp = array[i]
            array[i] = array[j]
            array[j] = tmp
        }
    }

    public companion object {
        // 2^64 / golden ratio, odd, per the SplitMix64 reference algorithm.
        private val GOLDEN_GAMMA = 0x9E3779B97F4A7C15UL.toLong()

        /** Reconstructs an [Rng] that continues exactly from a previously observed [state]. */
        public fun fromState(state: Long): Rng {
            val rng = Rng(0L)
            rng.raw = state
            return rng
        }
    }
}
