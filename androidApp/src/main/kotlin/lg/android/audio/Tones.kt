package lg.android.audio

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure PCM tone generator, no Android dependency, so it can run under a
 * plain JVM unit test (`BeeperToneTest`) without Robolectric or a device.
 * [Beeper] is the only caller in production; it feeds the generated
 * [ShortArray]s straight into `AudioTrack`s created in `MODE_STATIC`.
 *
 * All five recipes are mirrored exactly in iOS's `Tones.swift` (same
 * frequencies, durations and envelope shape) so the two platforms sound the
 * same (PLAN.md §5/§7).
 */
internal object Tones {
    const val SAMPLE_RATE_HZ = 22_050

    /** Linear attack/release length applied at both ends of every tone to avoid clicks. */
    private const val ENVELOPE_MS = 5

    /** Peak amplitude, well under [Short.MAX_VALUE] so nothing clips after the envelope. */
    private const val AMPLITUDE = 0.6 * Short.MAX_VALUE

    fun tick(): ShortArray = tone(durationMs = 30) { t, durationS ->
        sin(2.0 * PI * 1200.0 * t)
    }

    fun eat(): ShortArray = tone(durationMs = 90) { t, durationS ->
        // Rising two-tone: first half at 660 Hz, second half at 880 Hz.
        val freq = if (t < durationS / 2.0) 660.0 else 880.0
        sin(2.0 * PI * freq * t)
    }

    fun hit(): ShortArray = tone(durationMs = 50) { t, _ ->
        // Square-ish 330 Hz: sign of the sine, softened by a small amount of
        // the fundamental so it isn't a harsh pure square wave.
        val fundamental = sin(2.0 * PI * 330.0 * t)
        val square = if (fundamental >= 0.0) 1.0 else -1.0
        0.7 * square + 0.3 * fundamental
    }

    fun win(): ShortArray = tone(durationMs = 240) { t, durationS ->
        // Three ascending notes: C5, E5, G5, each one third of the duration.
        val third = durationS / 3.0
        val freq = when {
            t < third -> 523.25 // C5
            t < 2 * third -> 659.25 // E5
            else -> 783.99 // G5
        }
        sin(2.0 * PI * freq * t)
    }

    fun lose(): ShortArray = tone(durationMs = 300) { t, durationS ->
        // Descending sweep from 440 Hz to 220 Hz.
        val progress = (t / durationS).coerceIn(0.0, 1.0)
        val freq = 440.0 - (440.0 - 220.0) * progress
        sin(2.0 * PI * freq * t)
    }

    /**
     * Generates [durationMs] of mono PCM16 at [SAMPLE_RATE_HZ], calling
     * [wave] with `(t seconds, durationSeconds)` for each sample and applying
     * a linear attack/release envelope so every buffer starts and ends near
     * zero (no clicks on loop restart).
     */
    private inline fun tone(durationMs: Int, wave: (t: Double, durationS: Double) -> Double): ShortArray {
        val sampleCount = (SAMPLE_RATE_HZ.toLong() * durationMs / 1000L).toInt()
        val durationS = durationMs / 1000.0
        val envelopeSamples = min(sampleCount / 2, SAMPLE_RATE_HZ * ENVELOPE_MS / 1000).coerceAtLeast(1)
        val out = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            val t = i.toDouble() / SAMPLE_RATE_HZ
            var sample = wave(t, durationS)
            // Linear attack.
            if (i < envelopeSamples) {
                sample *= i.toDouble() / envelopeSamples
            }
            // Linear release.
            val fromEnd = sampleCount - 1 - i
            if (fromEnd < envelopeSamples) {
                sample *= fromEnd.toDouble() / envelopeSamples
            }
            out[i] = (sample * AMPLITUDE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }
}
