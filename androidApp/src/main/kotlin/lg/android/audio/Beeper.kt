package lg.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.io.File
import lg.core.scene.SoundId

/**
 * Real [SoundSink]: the five short tones of [Tones] are written once as tiny
 * WAV files in the app's cache directory and loaded into a [SoundPool], the
 * platform's own tool for game sound effects (low latency, overlapping
 * playback, no per-sound thread or buffer handling on our side). [play] only
 * calls [SoundPool.play] with an id resolved at load time, so it never
 * allocates or touches the filesystem (PLAN.md §7, "aucune allocation dans
 * update/render").
 *
 * Lifetime: constructed lazily by [lg.android.App] and kept for the process
 * lifetime. If loading a tone fails, that sound is silently skipped: never a
 * crash.
 */
internal class Beeper(context: Context) : SoundSink {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val tick = load(context, "tick", Tones.tick())
    private val eat = load(context, "eat", Tones.eat())
    private val hit = load(context, "hit", Tones.hit())
    private val win = load(context, "win", Tones.win())
    private val lose = load(context, "lose", Tones.lose())

    override fun play(soundMask: Int) {
        // Priority order (highest first) so simultaneous bits in one frame
        // don't play on top of each other: LOSE > WIN > EAT > HIT > TICK.
        val id = when {
            soundMask and SoundId.LOSE.bit != 0 -> lose
            soundMask and SoundId.WIN.bit != 0 -> win
            soundMask and SoundId.EAT.bit != 0 -> eat
            soundMask and SoundId.HIT.bit != 0 -> hit
            soundMask and SoundId.TICK.bit != 0 -> tick
            else -> 0
        }
        if (id != 0) pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    /** Frees the pool. Not called in production; see class doc. */
    fun release() {
        pool.release()
    }

    /** @return the SoundPool id, or 0 when the tone could not be loaded. */
    private fun load(context: Context, name: String, samples: ShortArray): Int = try {
        val file = File(context.cacheDir, "tone_$name.wav")
        file.writeBytes(wav(samples))
        pool.load(file.absolutePath, 1)
    } catch (e: Exception) {
        Log.w("Beeper", "could not load tone $name, it will be silent", e)
        0
    }

    /** Mono PCM16 WAV file bytes at [Tones.SAMPLE_RATE_HZ]. */
    private fun wav(samples: ShortArray): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArray(44 + dataBytes)
        fun put(offset: Int, value: Int, bytes: Int) {
            for (i in 0 until bytes) out[offset + i] = (value shr (8 * i) and 0xFF).toByte()
        }
        fun tag(offset: Int, text: String) {
            for (i in text.indices) out[offset + i] = text[i].code.toByte()
        }
        tag(0, "RIFF")
        put(4, 36 + dataBytes, 4)
        tag(8, "WAVE")
        tag(12, "fmt ")
        put(16, 16, 4)
        put(20, 1, 2) // PCM
        put(22, 1, 2) // mono
        put(24, Tones.SAMPLE_RATE_HZ, 4)
        put(28, Tones.SAMPLE_RATE_HZ * 2, 4)
        put(32, 2, 2)
        put(34, 16, 2)
        tag(36, "data")
        put(40, dataBytes, 4)
        for (i in samples.indices) put(44 + i * 2, samples[i].toInt(), 2)
        return out
    }
}
