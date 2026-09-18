import UIKit
import GamesCore

/// Draws a `GameMeta.icon` tile icon through the same `Scene` +
/// `CoreGraphicsRenderer` pipeline used for gameplay, so tile icons and
/// in-game rendering always look consistent for free (PLAN.md §4: icons are
/// "dessinées par le core").
final class IconView: UIView {
    private let renderer = CoreGraphicsRenderer()
    private let scene = Scene(capacity: 1024)
    private var meta: GameMeta?
    private var cgColors: [CGColor] = ScenePalette.cgColors(for: .current)

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        backgroundColor = .clear
        contentMode = .redraw
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    func configure(meta: GameMeta) {
        self.meta = meta
        setNeedsDisplay()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        cgColors = ScenePalette.cgColors(for: traitCollection)
        setNeedsDisplay()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let meta, let context = UIGraphicsGetCurrentContext() else { return }
        scene.clear()
        let size = Float(min(bounds.width, bounds.height))
        guard size > 0 else { return }
        // Kotlin lambdas box primitive parameters when exported to Objective-C:
        // `(Scene, Float) -> Unit` becomes `(Scene, KotlinFloat) -> Void`.
        meta.icon(scene, KotlinFloat(value: size))
        renderer.draw(scene, in: context, colors: cgColors)
    }
}
