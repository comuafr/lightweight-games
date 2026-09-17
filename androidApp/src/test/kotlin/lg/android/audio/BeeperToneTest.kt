package lg.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Plain JVM unit test for [Tones]: no Android dependency, so this runs
 * under `testDebugUnitTest` without Robolectric or a device.
 */
class BeeperToneTest {

    private val allTones: Map<String, ShortArray> = mapOf(
        "tick" to Tones.tick(),
        "eat" to Tones.eat(),
        "hit" to Tones.hit(),
        "win" to Tones.win(),
        "lose" to Tones.lose(),
    )

    @Test
    fun `buffer lengths match their nominal duration at 22050 Hz`() {
        val expectedMs = mapOf(
            "tick" to 30,
            "eat" to 90,
            "hit" to 50,
            "win" to 240,
            "lose" to 300,
        )
        for ((name, samples) in allTones) {
            val expectedSamples = Tones.SAMPLE_RATE_HZ.toLong() * expectedMs.getValue(name) / 1000L
            assertEquals("$name buffer length", expectedSamples, samples.size.toLong())
        }
    }

    @Test
    fun `no sample clips beyond the 16-bit range and amplitude stays bounded`() {
        for ((name, samples) in allTones) {
            for (s in samples) {
                assertTrue(
                    "$name: sample $s must be within [-0.9, 0.9] * Short.MAX_VALUE",
                    abs(s.toInt()) <= (0.9 * Short.MAX_VALUE).toInt(),
                )
            }
        }
    }

    @Test
    fun `envelope starts and ends near zero to avoid clicks`() {
        // Threshold: well under peak amplitude (0.6 * Short.MAX_VALUE ~= 19660),
        // loose enough to tolerate the first/last sample not being exactly 0.
        val threshold = (0.1 * Short.MAX_VALUE).toInt()
        for ((name, samples) in allTones) {
            assertTrue("$name: first sample $threshold", abs(samples.first().toInt()) <= threshold)
            assertTrue("$name: last sample $threshold", abs(samples.last().toInt()) <= threshold)
        }
    }

    @Test
    fun `every tone is non-empty and non-silent`() {
        for ((name, samples) in allTones) {
            assertTrue("$name must not be empty", samples.isNotEmpty())
            assertTrue("$name must contain audible samples", samples.any { abs(it.toInt()) > 1000 })
        }
    }
}
