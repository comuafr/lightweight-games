import Foundation

/// Pure PCM tone generator, no `AVFoundation` dependency: just produces
/// `[Float]` sample arrays in `[-1, 1]` that `Beeper` wraps into
/// `AVAudioPCMBuffer`s. The five recipes mirror Android's `Tones.kt`
/// exactly (same frequencies, durations and envelope shape) so both
/// platforms sound the same (PLAN.md §5/§7).
enum Tones {
    static let sampleRateHz: Double = 22_050

    /// Linear attack/release length applied at both ends of every tone to avoid clicks.
    private static let envelopeMs = 5

    /// Peak amplitude, comfortably under 1.0 so nothing clips after the envelope.
    private static let amplitude: Float = 0.6

    static func tick() -> [Float] {
        tone(durationMs: 30) { t, _ in
            sin(2.0 * Double.pi * 1200.0 * t)
        }
    }

    static func eat() -> [Float] {
        tone(durationMs: 90) { t, durationS in
            // Rising two-tone: first half at 660 Hz, second half at 880 Hz.
            let freq = t < durationS / 2.0 ? 660.0 : 880.0
            return sin(2.0 * Double.pi * freq * t)
        }
    }

    static func hit() -> [Float] {
        tone(durationMs: 50) { t, _ in
            // Square-ish 330 Hz: sign of the sine, softened by a small amount
            // of the fundamental so it isn't a harsh pure square wave.
            let fundamental = sin(2.0 * Double.pi * 330.0 * t)
            let square: Double = fundamental >= 0.0 ? 1.0 : -1.0
            return 0.7 * square + 0.3 * fundamental
        }
    }

    static func win() -> [Float] {
        tone(durationMs: 240) { t, durationS in
            // Three ascending notes: C5, E5, G5, each one third of the duration.
            let third = durationS / 3.0
            let freq: Double
            if t < third {
                freq = 523.25 // C5
            } else if t < 2 * third {
                freq = 659.25 // E5
            } else {
                freq = 783.99 // G5
            }
            return sin(2.0 * Double.pi * freq * t)
        }
    }

    static func lose() -> [Float] {
        tone(durationMs: 300) { t, durationS in
            // Descending sweep from 440 Hz to 220 Hz.
            let progress = min(1.0, max(0.0, t / durationS))
            let freq = 440.0 - (440.0 - 220.0) * progress
            return sin(2.0 * Double.pi * freq * t)
        }
    }

    /// Generates `durationMs` of mono Float32 samples at `sampleRateHz`,
    /// calling `wave` with `(t seconds, durationSeconds)` for each sample
    /// and applying a linear attack/release envelope so every buffer starts
    /// and ends near zero (no clicks on replay).
    private static func tone(durationMs: Int, wave: (Double, Double) -> Double) -> [Float] {
        let sampleCount = Int(sampleRateHz * Double(durationMs) / 1000.0)
        let durationS = Double(durationMs) / 1000.0
        let envelopeSamples = max(1, min(sampleCount / 2, Int(sampleRateHz) * envelopeMs / 1000))
        var out = [Float](repeating: 0, count: sampleCount)
        for i in 0..<sampleCount {
            let t = Double(i) / sampleRateHz
            var sample = wave(t, durationS)
            if i < envelopeSamples {
                sample *= Double(i) / Double(envelopeSamples)
            }
            let fromEnd = sampleCount - 1 - i
            if fromEnd < envelopeSamples {
                sample *= Double(fromEnd) / Double(envelopeSamples)
            }
            out[i] = Float(sample) * amplitude
        }
        return out
    }
}
