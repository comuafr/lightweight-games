import UIKit

/// Thin wrapper around `UIImpactFeedbackGenerator`, prepared once at
/// creation and re-primed after every pulse so the next `Scene.haptic`
/// request (see lg.core.scene.Scene.haptic) fires with minimal latency.
final class Haptics {
    private let generator: UIImpactFeedbackGenerator

    init(style: UIImpactFeedbackGenerator.FeedbackStyle = .light) {
        generator = UIImpactFeedbackGenerator(style: style)
        generator.prepare()
    }

    func pulse() {
        generator.impactOccurred()
        generator.prepare()
    }
}
