package lg.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import lg.core.scene.SoundId

/**
 * Real [SoundSink]: five short PCM16 tones (see [Tones]) each preloaded into
 * its own `AudioTrack` in `MODE_STATIC` at construction time, so [play] never
 * allocates or touches the filesystem — it just rewinds and restarts the
 * matching track (PLAN.md §7, "aucune allocation dans update/render").
 *
 * Lifetime: constructed lazily by [lg.android.App] (`by lazy`) the first
 * time a [lg.android.ui.GameView] needs a sink, and kept for the process
 * lifetime rather than released per-`GameActivity`. A single app-wide set of
 * `AudioTrack`s is simpler than tearing them down and rebuilding them every
 * time a game screen opens/closes, and five ~350 KB-or-less STATIC tracks
 * are a negligible, fixed memory cost — so there is no [release] call wired
 * to `onDestroy`; [release] exists for completeness (e.g. tests) but is
 * unused in production.
 *
 * If `AudioTrack` construction fails on a given device (e.g. no audio
 * output available), the corresponding track is left `null` and [play]
 * silently does nothing for that sound — never crashes, never throws.
 */
internal class Beeper : SoundSink {

    private val tickTrack = buildTrack(Tones.tick())
    private val eatTrack = buildTrack(Tones.eat())
    private val hitTrack = buildTrack(Tones.hit())
    private val winTrack = buildTrack(Tones.win())
    private val loseTrack = buildTrack(Tones.lose())

    override fun play(soundMask: Int) {
        // Priority order (highest first) so simultaneous bits in one frame
        // don't play on top of each other: LOSE > WIN > EAT > HIT > TICK.
        val track = when {
            soundMask and SoundId.LOSE.bit != 0 -> loseTrack
            soundMask and SoundId.WIN.bit != 0 -> winTrack
            soundMask and SoundId.EAT.bit != 0 -> eatTrack
            soundMask and SoundId.HIT.bit != 0 -> hitTrack
            soundMask and SoundId.TICK.bit != 0 -> tickTrack
            else -> null
        }
        track?.let(::retrigger)
    }

    /** Frees the native `AudioTrack`s. Not called in production; see class doc. */
    fun release() {
        tickTrack?.release()
        eatTrack?.release()
        hitTrack?.release()
        winTrack?.release()
        loseTrack?.release()
    }

    private fun retrigger(track: AudioTrack) {
        try {
            track.stop()
            track.reloadStaticData()
            track.play()
        } catch (_: IllegalStateException) {
            // Track was released concurrently or in a bad state; drop the sound.
        }
    }

    private fun buildTrack(samples: ShortArray): AudioTrack? = try {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Tones.SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val bytes = samples.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            null
        } else {
            track.write(samples, 0, samples.size)
            track
        }
    } catch (e: Exception) {
        // UnsupportedOperationException / IllegalArgumentException / IllegalStateException
        // all mean "no usable audio track on this device/config" -> fall back to silence.
        Log.w("Beeper", "AudioTrack init failed, sound disabled for this tone", e)
        null
    }
}
