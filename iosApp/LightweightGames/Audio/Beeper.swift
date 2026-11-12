import AVFoundation
import UIKit

/// Real `SoundSink`: five short PCM tones (see `Tones.swift`) precomputed
/// into `AVAudioPCMBuffer`s at construction, played back through a single
/// `AVAudioPlayerNode` on a lazily-started `AVAudioEngine` (PLAN.md §5,
/// §9). Mirrors Android's `Beeper.kt` behavior and priority rule.
///
/// Lifetime: created once in `Services` and kept for the app's lifetime
/// (see the note on Android's `Beeper` for why an app-wide singleton is
/// simplest here too). The engine itself is started lazily on first play
/// and stopped when the app backgrounds, restarting lazily on the next
/// play after the app returns.
final class Beeper: SoundSink {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()

    private let tickBuffer: AVAudioPCMBuffer?
    private let eatBuffer: AVAudioPCMBuffer?
    private let hitBuffer: AVAudioPCMBuffer?
    private let winBuffer: AVAudioPCMBuffer?
    private let loseBuffer: AVAudioPCMBuffer?
    private let explosionBuffer: AVAudioPCMBuffer?

    private var sessionConfigured = false

    init() {
        // Float32, mono, 22.05 kHz, non-interleaved: what AVAudioEngine wants
        // for an AVAudioPCMBuffer and what Tones.swift produces directly.
        let format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Tones.sampleRateHz,
            channels: 1,
            interleaved: false
        ) ?? AVAudioFormat(standardFormatWithSampleRate: Tones.sampleRateHz, channels: 1)!

        tickBuffer = Beeper.makeBuffer(samples: Tones.tick(), format: format)
        eatBuffer = Beeper.makeBuffer(samples: Tones.eat(), format: format)
        hitBuffer = Beeper.makeBuffer(samples: Tones.hit(), format: format)
        winBuffer = Beeper.makeBuffer(samples: Tones.win(), format: format)
        loseBuffer = Beeper.makeBuffer(samples: Tones.lose(), format: format)
        explosionBuffer = Beeper.makeBuffer(samples: Tones.explosion(), format: format)

        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    func play(mask: Int32) {
        // Priority order (highest first), matching Android's Beeper.kt, so
        // simultaneous bits in one frame don't play on top of each other:
        // EXPLOSION > LOSE > WIN > EAT > HIT > TICK.
        let buffer: AVAudioPCMBuffer?
        if mask & SoundBit.explosion != 0 {
            buffer = explosionBuffer
        } else if mask & SoundBit.lose != 0 {
            buffer = loseBuffer
        } else if mask & SoundBit.win != 0 {
            buffer = winBuffer
        } else if mask & SoundBit.eat != 0 {
            buffer = eatBuffer
        } else if mask & SoundBit.hit != 0 {
            buffer = hitBuffer
        } else if mask & SoundBit.tick != 0 {
            buffer = tickBuffer
        } else {
            buffer = nil
        }
        guard let buffer else { return }
        guard ensureEngineRunning() else { return }

        // .interrupts stops whatever is currently scheduled/playing and
        // starts this buffer immediately, which is the desired "cut off and
        // replace" behavior when a new sound wins priority mid-playback.
        player.scheduleBuffer(buffer, at: nil, options: .interrupts, completionHandler: nil)
        if !player.isPlaying {
            player.play()
        }
    }

    @objc private func handleDidEnterBackground() {
        player.stop()
        engine.pause()
    }

    /// Configures the (ambient) audio session and starts the engine, both
    /// lazily and both best-effort: any failure falls back to silence
    /// rather than crashing or throwing.
    private func ensureEngineRunning() -> Bool {
        if !sessionConfigured {
            let session = AVAudioSession.sharedInstance()
            do {
                // .ambient: mixes with other audio, respects the silent
                // switch, and never interrupts music the user is already
                // playing (PLAN.md's "never interrupts the user" goal).
                try session.setCategory(.ambient, mode: .default, options: [])
                try session.setActive(true, options: [])
                sessionConfigured = true
            } catch {
                return false
            }
        }
        if engine.isRunning {
            return true
        }
        do {
            try engine.start()
            return true
        } catch {
            return false
        }
    }

    private static func makeBuffer(samples: [Float], format: AVAudioFormat) -> AVAudioPCMBuffer? {
        guard !samples.isEmpty,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count))
        else { return nil }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        guard let channel = buffer.floatChannelData?[0] else { return nil }
        for i in 0..<samples.count {
            channel[i] = samples[i]
        }
        return buffer
    }
}
