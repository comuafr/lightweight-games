package lg.android.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure PCM sound-effect synthesizer, no Android dependency, so it runs under
 * a plain JVM unit test (`BeeperToneTest`). [Beeper] is the only production
 * caller.
 *
 * Each effect is a handful of short "voices" (a pitch, an optional glide, a
 * waveform and an exponential decay, like a plucked or struck note) mixed
 * into one buffer, with a light echo and a final normalisation. Compared with
 * plain sine/square beeps this gives soft attacks, natural decays and some
 * overtones, which is what keeps it from sounding like a MIDI ringtone.
 *
 * iOS still has its own, simpler `Tones.swift`; it is not in sync with these
 * recipes.
 */
internal object Tones {
    const val SAMPLE_RATE_HZ = 44_100

    /** Peak level after normalisation, well under full scale. */
    private const val PEAK = 0.55

    /** Attack and final fade lengths, in seconds, applied to every voice to avoid clicks. */
    private const val ATTACK_S = 0.003
    private const val FADE_S = 0.012

    private enum class Wave { SINE, TRIANGLE, PLUCK, NOISE }

    /** Soft "pop", like a bubble. */
    fun tick(): ShortArray = render(durationMs = 90) { buf ->
        voice(buf, 0.0, 0.08, 1100.0, 700.0, Wave.SINE, decayS = 0.022)
    }

    /** Bright two-note "bloop" going up. */
    fun eat(): ShortArray = render(durationMs = 260, echoMs = 80, echoGain = 0.25) { buf ->
        voice(buf, 0.0, 0.13, 784.0, 784.0, Wave.PLUCK, decayS = 0.05)
        voice(buf, 0.07, 0.16, 1174.7, 1174.7, Wave.PLUCK, decayS = 0.07, gain = 0.85)
    }

    /** Quick sparkling run up, for a bonus pick-up. */
    fun bonus(): ShortArray = render(durationMs = 420, echoMs = 90, echoGain = 0.3) { buf ->
        voice(buf, 0.00, 0.12, 880.0, 880.0, Wave.PLUCK, decayS = 0.05)
        voice(buf, 0.06, 0.12, 1108.7, 1108.7, Wave.PLUCK, decayS = 0.05)
        voice(buf, 0.12, 0.12, 1318.5, 1318.5, Wave.PLUCK, decayS = 0.05)
        voice(buf, 0.18, 0.22, 1760.0, 1760.0, Wave.PLUCK, decayS = 0.09)
    }

    /** Dull "thock", like a ball on a wall: a falling thump plus a tiny click. */
    fun hit(): ShortArray = render(durationMs = 150) { buf ->
        voice(buf, 0.0, 0.13, 260.0, 120.0, Wave.TRIANGLE, decayS = 0.04)
        voice(buf, 0.0, 0.012, 0.0, 0.0, Wave.NOISE, decayS = 0.004, gain = 0.5)
    }

    /** Happy rising arpeggio, C major, with a long last note. */
    fun win(): ShortArray = render(durationMs = 850, echoMs = 140, echoGain = 0.3) { buf ->
        voice(buf, 0.00, 0.20, 523.25, 523.25, Wave.PLUCK, decayS = 0.08)
        voice(buf, 0.11, 0.20, 659.25, 659.25, Wave.PLUCK, decayS = 0.08)
        voice(buf, 0.22, 0.20, 783.99, 783.99, Wave.PLUCK, decayS = 0.08)
        voice(buf, 0.33, 0.45, 1046.5, 1046.5, Wave.PLUCK, decayS = 0.16)
    }

    /** Sad falling phrase, then a low slide down. */
    fun lose(): ShortArray = render(durationMs = 850, echoMs = 120, echoGain = 0.25) { buf ->
        voice(buf, 0.00, 0.20, 392.0, 392.0, Wave.TRIANGLE, decayS = 0.10)
        voice(buf, 0.17, 0.20, 311.1, 311.1, Wave.TRIANGLE, decayS = 0.10)
        voice(buf, 0.34, 0.30, 261.6, 261.6, Wave.TRIANGLE, decayS = 0.14)
        voice(buf, 0.52, 0.25, 200.0, 90.0, Wave.TRIANGLE, decayS = 0.12, gain = 0.8)
    }

    private inline fun render(
        durationMs: Int,
        echoMs: Int = 0,
        echoGain: Double = 0.0,
        build: (DoubleArray) -> Unit,
    ): ShortArray {
        val n = (SAMPLE_RATE_HZ.toLong() * durationMs / 1000L).toInt()
        val buf = DoubleArray(n)
        build(buf)
        val delay = SAMPLE_RATE_HZ * echoMs / 1000
        if (delay > 0) {
            for (i in delay until n) buf[i] += buf[i - delay] * echoGain
        }
        // Final fade so the buffer always ends at zero, then normalise to PEAK.
        val fade = (SAMPLE_RATE_HZ * FADE_S).toInt().coerceAtLeast(1)
        for (k in 0 until min(fade, n)) buf[n - 1 - k] *= k.toDouble() / fade
        var peak = 0.0
        for (v in buf) if (v > peak) peak = v else if (-v > peak) peak = -v
        val scale = if (peak > 0.0) PEAK * Short.MAX_VALUE / peak else 0.0
        return ShortArray(n) { (buf[it] * scale).toInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }

    /**
     * Adds one voice to [buf]: starts at [startS], lasts [durS] seconds, glides
     * linearly in pitch from [f0] to [f1] Hz and decays exponentially with time
     * constant [decayS].
     */
    private fun voice(
        buf: DoubleArray,
        startS: Double,
        durS: Double,
        f0: Double,
        f1: Double,
        wave: Wave,
        decayS: Double,
        gain: Double = 1.0,
    ) {
        val first = (startS * SAMPLE_RATE_HZ).toInt()
        val count = (durS * SAMPLE_RATE_HZ).toInt()
        val fadeSamples = (SAMPLE_RATE_HZ * FADE_S).toInt().coerceAtLeast(1)
        var phase = 0.0
        var noise = 12345
        for (i in 0 until count) {
            val idx = first + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SAMPLE_RATE_HZ
            val freq = f0 + (f1 - f0) * (i.toDouble() / count)
            phase += 2.0 * PI * freq / SAMPLE_RATE_HZ
            val sample = when (wave) {
                Wave.SINE -> sin(phase)
                Wave.TRIANGLE -> triangle(phase)
                // Fundamental plus a few overtones: a soft, bell-like pluck.
                Wave.PLUCK -> 0.7 * sin(phase) + 0.25 * sin(2.0 * phase) + 0.12 * sin(3.0 * phase)
                Wave.NOISE -> {
                    noise = noise * 1103515245 + 12345
                    ((noise shr 16) and 0x7FFF) / 16384.0 - 1.0
                }
            }
            var amp = exp(-t / decayS) * gain
            val attack = (SAMPLE_RATE_HZ * ATTACK_S).toInt().coerceAtLeast(1)
            if (i < attack) amp *= i.toDouble() / attack
            val fromEnd = count - 1 - i
            if (fromEnd < fadeSamples) amp *= fromEnd.toDouble() / fadeSamples
            buf[idx] += sample * amp
        }
    }

    private fun triangle(phase: Double): Double {
        val x = (phase / (2.0 * PI)) % 1.0
        return 4.0 * kotlin.math.abs(x - 0.5) - 1.0
    }
}
