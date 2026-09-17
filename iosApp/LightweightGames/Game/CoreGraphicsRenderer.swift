import UIKit
import GamesCore

/// Draws a `lg.core.scene.Scene` with `CGContext`. This is the *only* place
/// that interprets the `Scene` SoA arrays (PLAN.md §2: "un seul renderer par
/// plateforme"). Stateless aside from a small per-integer-size font cache;
/// the palette -> `CGColor` table is owned by the caller (`GameView` /
/// `IconView`) and passed in, since it must be rebuilt on trait changes.
final class CoreGraphicsRenderer {
    /// Cache of `UIFont.systemFont(ofSize:weight:)`, keyed by the rounded
    /// point size, so repeated primitives at the same size (score, HUD,
    /// board numbers) never re-create a font.
    private var fontCache: [Int: UIFont] = [:]

    /// ARC primitives carry no dedicated stroke-width field in `Scene.f`
    /// (unlike LINE); M0 registers no game that emits ARC, so this constant
    /// is a placeholder to verify against real usage once a game draws one.
    private static let arcLineWidth: CGFloat = 2

    /// Draws every primitive in `scene.0..<scene.count` into `context`.
    /// `colors` must have exactly `PaletteIndex.count` entries (see
    /// `ScenePalette.cgColors(for:)`), indexed by each primitive's
    /// `Scene.color` (a `Palette` index, never a raw color).
    func draw(_ scene: Scene, in context: CGContext, colors: [CGColor]) {
        let count = Int(scene.count)
        var i = 0
        while i < count {
            let kind = scene.kind.get(index: Int32(i))
            let colorIndex = Int(scene.color.get(index: Int32(i)))
            guard colors.indices.contains(colorIndex) else {
                i += 1
                continue
            }
            let color = colors[colorIndex]
            let base = i * 6

            switch kind {
            case PrimKind.rect:
                drawRect(scene, base: base, color: color, context: context)
            case PrimKind.circle:
                drawCircle(scene, base: base, color: color, context: context)
            case PrimKind.line:
                drawLine(scene, base: base, color: color, context: context)
            case PrimKind.arc:
                drawArc(scene, base: base, color: color, context: context)
            case PrimKind.text:
                let ordinal = scene.key.get(index: Int32(i))
                drawText(Strings.string(forOrdinal: ordinal), scene: scene, base: base, color: color, context: context)
            case PrimKind.number:
                let value = scene.key.get(index: Int32(i))
                // Allocates a String per call; acceptable on the Swift side
                // (unlike lg.core, which must never allocate in render()).
                drawText(String(value), scene: scene, base: base, color: color, context: context)
            case PrimKind.label:
                if let text = scene.label.get(index: Int32(i)) as String? {
                    drawText(text, scene: scene, base: base, color: color, context: context)
                }
            default:
                break
            }
            i += 1
        }
    }

    // MARK: - Primitives

    private func drawRect(_ scene: Scene, base: Int, color: CGColor, context: CGContext) {
        let x = f(scene, base, 0)
        let y = f(scene, base, 1)
        let w = f(scene, base, 2)
        let h = f(scene, base, 3)
        let radius = f(scene, base, 4)
        let alpha = f(scene, base, 5)

        context.saveGState()
        context.setAlpha(alpha)
        context.setFillColor(color)
        let rect = CGRect(x: x, y: y, width: w, height: h)
        if radius > 0 {
            context.addPath(UIBezierPath(roundedRect: rect, cornerRadius: radius).cgPath)
            context.fillPath()
        } else {
            context.fill(rect)
        }
        context.restoreGState()
    }

    private func drawCircle(_ scene: Scene, base: Int, color: CGColor, context: CGContext) {
        let cx = f(scene, base, 0)
        let cy = f(scene, base, 1)
        let r = f(scene, base, 2)
        let alpha = f(scene, base, 3)

        context.saveGState()
        context.setAlpha(alpha)
        context.setFillColor(color)
        context.fillEllipse(in: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2))
        context.restoreGState()
    }

    private func drawLine(_ scene: Scene, base: Int, color: CGColor, context: CGContext) {
        let x1 = f(scene, base, 0)
        let y1 = f(scene, base, 1)
        let x2 = f(scene, base, 2)
        let y2 = f(scene, base, 3)
        let width = f(scene, base, 4)
        let alpha = f(scene, base, 5)

        context.saveGState()
        context.setAlpha(alpha)
        context.setStrokeColor(color)
        context.setLineWidth(width)
        context.setLineCap(.round)
        context.move(to: CGPoint(x: x1, y: y1))
        context.addLine(to: CGPoint(x: x2, y: y2))
        context.strokePath()
        context.restoreGState()
    }

    private func drawArc(_ scene: Scene, base: Int, color: CGColor, context: CGContext) {
        let cx = f(scene, base, 0)
        let cy = f(scene, base, 1)
        let r = f(scene, base, 2)
        let startDeg = f(scene, base, 3)
        let sweepDeg = f(scene, base, 4)
        let alpha = f(scene, base, 5)

        let startRad = startDeg * .pi / 180
        let endRad = (startDeg + sweepDeg) * .pi / 180

        context.saveGState()
        context.setAlpha(alpha)
        context.setStrokeColor(color)
        context.setLineWidth(CoreGraphicsRenderer.arcLineWidth)
        context.setLineCap(.round)
        context.addArc(center: CGPoint(x: cx, y: cy), radius: r, startAngle: startRad, endAngle: endRad, clockwise: false)
        context.strokePath()
        context.restoreGState()
    }

    /// Shared by TEXT/NUMBER/LABEL: `x, y, size, alpha` at `base`, where `y`
    /// is the vertical center of the line and `x`'s meaning depends on
    /// `align` (LEFT/CENTER/RIGHT), per `Scene`'s KDoc.
    private func drawText(_ text: String, scene: Scene, base: Int, color: CGColor, context: CGContext) {
        guard !text.isEmpty else { return }
        let x = f(scene, base, 0)
        let y = f(scene, base, 1)
        let size = f(scene, base, 2)
        let alpha = f(scene, base, 3)
        // align lives in the parallel Scene.align array, not in the 6-float
        // slice; the primitive index is (base / 6).
        let align = scene.align.get(index: Int32(base / 6))

        let font = cachedFont(size: size)
        let uiColor = UIColor(cgColor: color).withAlphaComponent(alpha)
        let attributed = NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: uiColor])
        let textSize = attributed.size()

        var originX = x
        switch align {
        case AlignKind.center:
            originX = x - textSize.width / 2
        case AlignKind.right:
            originX = x - textSize.width
        default:
            break // LEFT: x is already the left edge.
        }
        let originY = y - textSize.height / 2

        context.saveGState()
        UIGraphicsPushContext(context)
        attributed.draw(at: CGPoint(x: originX, y: originY))
        UIGraphicsPopContext()
        context.restoreGState()
    }

    // MARK: - Helpers

    @inline(__always)
    private func f(_ scene: Scene, _ base: Int, _ offset: Int) -> CGFloat {
        CGFloat(scene.f.get(index: Int32(base + offset)))
    }

    private func cachedFont(size: CGFloat) -> UIFont {
        let key = max(1, Int(size.rounded()))
        if let cached = fontCache[key] {
            return cached
        }
        let font = UIFont.systemFont(ofSize: CGFloat(key), weight: .semibold)
        fontCache[key] = font
        return font
    }
}
