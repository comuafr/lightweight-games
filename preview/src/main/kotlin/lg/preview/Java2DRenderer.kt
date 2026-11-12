package lg.preview

// Dev-only tool: renders a lg.core.scene.Scene into a BufferedImage via
// Java2D so games can be eyeballed as PNGs on a headless Linux box. Never
// shipped; see docs/ARCHITECTURE.md ("preview/" section).

import lg.core.scene.Align
import lg.core.scene.Palette as CorePalette
import lg.core.scene.Prim
import lg.core.scene.Scene
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/**
 * Color tables mirroring `androidApp/src/main/kotlin/lg/android/ui/Palette.kt`
 * exactly (index == [lg.core.scene.Palette] constant). Kept in sync by hand;
 * if the Android tables change, update these too.
 */
internal object PreviewPalette {
    val LIGHT: IntArray = intArrayOf(
        0xFFF6F1E7.toInt(), // BG
        0xFFFFFFFF.toInt(), // SURFACE
        0xFF20202C.toInt(), // FG
        0xFF6E6E80.toInt(), // FG_DIM
        0xFFFF7A45.toInt(), // ACCENT
        0xFF2EC4B6.toInt(), // P1
        0xFF6A4C93.toInt(), // P2
        0xFFDAD6C8.toInt(), // GRID
        0xFF2FB380.toInt(), // OK
        0xFFE6483C.toInt(), // DANGER
        0xFFFFE8CC.toInt(), // TILE_0
        0xFFFFD8A8.toInt(), // TILE_1
        0xFFFFC078.toInt(), // TILE_2
        0xFFFFA94D.toInt(), // TILE_3
        0xFFFF922B.toInt(), // TILE_4
        0xFFF76707.toInt(), // TILE_5
        0xFFE8590C.toInt(), // TILE_6
        0xFFD9480F.toInt(), // TILE_7
        0xFFA61E4D.toInt(), // TILE_8
        0xFF862E9C.toInt(), // TILE_9
        0xFF5F3DC4.toInt(), // TILE_10
        0xFF364FC7.toInt(), // TILE_11
        0xFF0B0B1E.toInt(), // BEZEL
        0xFF241447.toInt(), // BEZEL_EDGE
        0xFFFF2E97.toInt(), // NEON_PINK
        0xFF16E0FF.toInt(), // NEON_CYAN
        0xFFFFE600.toInt(), // NEON_YELLOW
        0xFF39FF14.toInt(), // NEON_GREEN
        0xFFFF8A1F.toInt(), // NEON_ORANGE
        0xFFFF3B3B.toInt(), // NEON_RED
        0xFFF4F4FF.toInt(), // NEON_WHITE
        0xFF9B5CFF.toInt(), // NEON_PURPLE
        0xFF1A4FCF.toInt(), // NUMBER_1
        0xFF136A2C.toInt(), // NUMBER_2
        0xFFAD1B1B.toInt(), // NUMBER_3
        0xFF6A2FA0.toInt(), // NUMBER_4
        0xFF8C3A12.toInt(), // NUMBER_5
        0xFF06626C.toInt(), // NUMBER_6
        0xFF20202C.toInt(), // NUMBER_7
        0xFF5A5A6A.toInt(), // NUMBER_8
    )

    val DARK: IntArray = intArrayOf(
        0xFF14141F.toInt(), // BG
        0xFF20202E.toInt(), // SURFACE
        0xFFF3F3FA.toInt(), // FG
        0xFF9A9AB0.toInt(), // FG_DIM
        0xFFFF8C5A.toInt(), // ACCENT
        0xFF4ECDC4.toInt(), // P1
        0xFF9D7FE0.toInt(), // P2
        0xFF32323F.toInt(), // GRID
        0xFF51CF66.toInt(), // OK
        0xFFFF6B6B.toInt(), // DANGER
        0xFF4A3B2A.toInt(), // TILE_0
        0xFF5C4426.toInt(), // TILE_1
        0xFF6E4E1F.toInt(), // TILE_2
        0xFF8A5518.toInt(), // TILE_3
        0xFFA65A12.toInt(), // TILE_4
        0xFFB85D0C.toInt(), // TILE_5
        0xFFC05B14.toInt(), // TILE_6
        0xFFB0491A.toInt(), // TILE_7
        0xFF8C2E4E.toInt(), // TILE_8
        0xFF6E3585.toInt(), // TILE_9
        0xFF4E3CA8.toInt(), // TILE_10
        0xFF3A4BB0.toInt(), // TILE_11
        0xFF0B0B1E.toInt(), // BEZEL
        0xFF241447.toInt(), // BEZEL_EDGE
        0xFFFF2E97.toInt(), // NEON_PINK
        0xFF16E0FF.toInt(), // NEON_CYAN
        0xFFFFE600.toInt(), // NEON_YELLOW
        0xFF39FF14.toInt(), // NEON_GREEN
        0xFFFF8A1F.toInt(), // NEON_ORANGE
        0xFFFF3B3B.toInt(), // NEON_RED
        0xFFF4F4FF.toInt(), // NEON_WHITE
        0xFF9B5CFF.toInt(), // NEON_PURPLE
        0xFF6EA8FF.toInt(), // NUMBER_1
        0xFF5BD27C.toInt(), // NUMBER_2
        0xFFFF7B72.toInt(), // NUMBER_3
        0xFFB79CFF.toInt(), // NUMBER_4
        0xFFE0A36B.toInt(), // NUMBER_5
        0xFF4FD6D0.toInt(), // NUMBER_6
        0xFFF3F3FA.toInt(), // NUMBER_7
        0xFFA8A8BA.toInt(), // NUMBER_8
    )

    init {
        check(LIGHT.size == CorePalette.COUNT) { "LIGHT palette size mismatch" }
        check(DARK.size == CorePalette.COUNT) { "DARK palette size mismatch" }
    }

    fun colorsFor(dark: Boolean): IntArray = if (dark) DARK else LIGHT
}

/**
 * Renders a [Scene] into a [BufferedImage] with Java2D, mirroring the
 * drawing conventions of `androidApp`'s `CanvasRenderer` (see its KDoc for
 * the per-primitive float layout).
 *
 * @param widthPt logical width in points (same unit the core emits).
 * @param heightPt logical height in points.
 * @param scale device-pixel scale factor; the whole canvas is scaled by this
 *   factor (via [java.awt.Graphics2D.scale]) before any drawing happens, so
 *   every primitive's coordinates, stroke widths and text sizes scale
 *   uniformly for a crisp, higher-resolution PNG.
 * @param dark true to use the night palette instead of the light one.
 */
internal class Java2DRenderer(
    private val widthPt: Float,
    private val heightPt: Float,
    private val scale: Float = 2f,
    private val dark: Boolean = false,
    private val strings: PreviewStrings = PreviewStrings.load(),
    /**
     * Whether to fill the whole canvas with [lg.core.scene.Palette.BG] before
     * drawing. Left `false` for icon-only renders (standalone icon tile and
     * catalog thumbnails), which already draw their own background primitive
     * (or are composited onto one by the caller) and would otherwise show a
     * mismatched BG-colored square behind a SURFACE-colored card.
     */
    private val paintBackground: Boolean = true,
) {
    private val colors = PreviewPalette.colorsFor(dark)
    private val baseFont = Font("SansSerif", Font.BOLD, 1)

    /** Renders [scene] onto a fresh [BufferedImage] sized `widthPt*scale x heightPt*scale`. */
    fun render(scene: Scene): BufferedImage {
        val pxW = (widthPt * scale).toInt().coerceAtLeast(1)
        val pxH = (heightPt * scale).toInt().coerceAtLeast(1)
        val image = BufferedImage(pxW, pxH, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.scale(scale.toDouble(), scale.toDouble())

            if (paintBackground) {
                // Background fill so PNGs don't have a transparent backdrop.
                g.color = rgbOf(colors[CorePalette.BG])
                g.fillRect(0, 0, widthPt.toInt() + 1, heightPt.toInt() + 1)
            }

            val kind = scene.kind
            val f = scene.f
            val color = scene.color
            for (i in 0 until scene.count) {
                val base = i * FLOATS_PER_PRIM
                val col = rgbOf(colors[color[i]])
                when (kind[i]) {
                    Prim.RECT -> drawRect(g, f, base, col)
                    Prim.CIRCLE -> drawCircle(g, f, base, col)
                    Prim.LINE -> drawLine(g, f, base, col)
                    Prim.ARC -> drawArc(g, f, base, col)
                    Prim.TEXT -> drawText(g, scene, i, base, col)
                    Prim.NUMBER -> drawNumber(g, scene, i, base, col)
                    Prim.LABEL -> drawLabel(g, scene, i, base, col)
                }
            }
        } finally {
            g.dispose()
        }
        return image
    }

    private fun alphaByte(alpha: Float): Int = (alpha * 255f).toInt().coerceIn(0, 255)

    /** Palette entries are always fully opaque ARGB; keep only the RGB bits. */
    private fun rgbOf(argb: Int): Color = Color(argb and 0xFFFFFF)

    private fun drawRect(g: java.awt.Graphics2D, f: FloatArray, base: Int, col: Color) {
        val x = f[base]
        val y = f[base + 1]
        val w = f[base + 2]
        val h = f[base + 3]
        val radius = f[base + 4]
        g.color = withAlpha(col, alphaByte(f[base + 5]))
        if (radius > 0f) {
            g.fill(RoundRectangle2D.Float(x, y, w, h, radius * 2f, radius * 2f))
        } else {
            g.fill(java.awt.geom.Rectangle2D.Float(x, y, w, h))
        }
    }

    private fun drawCircle(g: java.awt.Graphics2D, f: FloatArray, base: Int, col: Color) {
        val x = f[base]
        val y = f[base + 1]
        val r = f[base + 2]
        g.color = withAlpha(col, alphaByte(f[base + 3]))
        g.fill(Ellipse2D.Float(x - r, y - r, r * 2f, r * 2f))
    }

    private fun drawLine(g: java.awt.Graphics2D, f: FloatArray, base: Int, col: Color) {
        g.color = withAlpha(col, alphaByte(f[base + 5]))
        g.stroke = java.awt.BasicStroke(f[base + 4].coerceAtLeast(0.01f))
        g.draw(Line2D.Float(f[base], f[base + 1], f[base + 2], f[base + 3]))
    }

    /**
     * Filled pie wedge, matching Android's `Canvas.drawArc(..., useCenter =
     * true)` visually: Android measures sweep clockwise from the 3 o'clock
     * position, while Java2D's [Arc2D] measures positive angles
     * counter-clockwise, so both angles are negated here.
     */
    private fun drawArc(g: java.awt.Graphics2D, f: FloatArray, base: Int, col: Color) {
        val cx = f[base]
        val cy = f[base + 1]
        val r = f[base + 2]
        val start = f[base + 3]
        val sweep = f[base + 4]
        g.color = withAlpha(col, alphaByte(f[base + 5]))
        g.fill(Arc2D.Float(cx - r, cy - r, r * 2f, r * 2f, -start, -sweep, Arc2D.PIE))
    }

    private fun drawText(g: java.awt.Graphics2D, scene: Scene, i: Int, base: Int, col: Color) {
        val text = strings.get(scene.key[i])
        drawAnchoredText(g, scene, i, base, col, text)
    }

    private fun drawLabel(g: java.awt.Graphics2D, scene: Scene, i: Int, base: Int, col: Color) {
        val text = scene.label[i] ?: return
        drawAnchoredText(g, scene, i, base, col, text)
    }

    private fun drawNumber(g: java.awt.Graphics2D, scene: Scene, i: Int, base: Int, col: Color) {
        drawAnchoredText(g, scene, i, base, col, scene.key[i].toString())
    }

    private fun drawAnchoredText(g: java.awt.Graphics2D, scene: Scene, i: Int, base: Int, col: Color, text: String) {
        if (text.isEmpty()) return
        val f = scene.f
        val size = f[base + 2]
        g.font = baseFont.deriveFont(size)
        g.color = withAlpha(col, alphaByte(f[base + 3]))
        val fm = g.fontMetrics
        val width = fm.stringWidth(text)
        val anchorX = f[base]
        val anchorY = f[base + 1]
        val drawX = when (scene.align[i].toInt()) {
            Align.CENTER.ordinal -> anchorX - width / 2f
            Align.RIGHT.ordinal -> anchorX - width
            else -> anchorX
        }
        // Vertical center at anchorY, matching CanvasRenderer's
        // `anchorY - (ascent + descent) / 2` where Android's ascent is
        // negative; Java2D's FontMetrics.ascent is positive, so the
        // equivalent here is `anchorY + (ascent - descent) / 2`.
        val baseline = anchorY + (fm.ascent - fm.descent) / 2f
        g.drawString(text, drawX, baseline)
    }

    private fun withAlpha(c: Color, alpha: Int): Color = Color(c.red, c.green, c.blue, alpha)

    private companion object {
        const val FLOATS_PER_PRIM = 6
    }
}
