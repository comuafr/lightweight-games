package lg.android.audio

/**
 * Plays the sounds requested by a frame's `Scene.soundMask`. [Beeper] is the
 * real, `AudioTrack`-backed implementation wired in by [lg.android.App]
 * (PLAN.md §5); [NoopSoundSink] remains a drop-in placeholder for tests.
 */
internal interface SoundSink {
    fun play(soundMask: Int)
}

internal object NoopSoundSink : SoundSink {
    override fun play(soundMask: Int) = Unit
}
