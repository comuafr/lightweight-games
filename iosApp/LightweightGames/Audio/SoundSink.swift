import Foundation

/// Plays procedural sound effects requested via `Scene.soundMask`, an
/// OR-combination of `SoundBit` values (see CoreConstants.swift, mirroring
/// `lg.core.scene.SoundId`).
///
/// `Beeper` is the real, `AVAudioEngine`-backed implementation wired in by
/// `Services` (PLAN.md §5); `NoopSoundSink` remains a drop-in placeholder
/// for tests.
protocol SoundSink {
    func play(mask: Int32)
}

final class NoopSoundSink: SoundSink {
    func play(mask: Int32) {}
}
