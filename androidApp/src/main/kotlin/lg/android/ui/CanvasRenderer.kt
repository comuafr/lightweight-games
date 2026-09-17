package lg.android.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import lg.core.scene.Align
import lg.core.scene.Prim
import lg.core.scene.Scene

/**
 * Draws a [Scene] onto a [Canvas]. The caller ([GameView], [TileView]) is
 * expected to have already scaled the canvas by the display density, so
 * every coordinate and size read here is in the same dp unit the core
 * emits — no per-primitive dp-to-px conversion is needed, and the canvas
 * matrix takes care of it for free (including stroke width and text size).
 *
 * All [Paint]s, the [RectF] and the number-formatting [CharArray] are
 * preallocated once and reused for every primitive of every frame: `draw`
 * never allocates.
 */
internal object CanvasRenderer {
    private const val FLOATS_PER_PRIM = 6

    private val rect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fontMetrics = Paint.FontMetrics()

    // Enough digits for any Int (including the sign): -2147483648 is 11 chars.
    private val numberChars = CharArray(11)
    private var numberStart = 0
    private var numberCount = 0

    fun draw(canvas: Canvas, scene: Scene, colors: IntArray, strings: Strings) {
        val kind = scene.kind
        val f = scene.f
        val color = scene.color
        for (i in 0 until scene.count) {
            val base = i * FLOATS_PER_PRIM
            val argb = colors[color[i]]
            when (kind[i]) {
                Prim.RECT -> drawRect(canvas, f, base, argb)
                Prim.CIRCLE -> drawCircle(canvas, f, base, argb)
                Prim.LINE -> drawLine(canvas, f, base, argb)
                Prim.ARC -> drawArc(canvas, f, base, argb)
                Prim.TEXT -> drawText(canvas, scene, i, base, argb, strings)
                Prim.NUMBER -> drawNumber(canvas, scene, i, base, argb)
                Prim.LABEL -> drawLabel(canvas, scene, i, base, argb)
            }
        }
    }

    private fun alphaByte(alpha: Float): Int = (alpha * 255f).toInt().coerceIn(0, 255)

    private fun drawRect(canvas: Canvas, f: FloatArray, base: Int, argb: Int) {
        val x = f[base]
        val y = f[base + 1]
        val w = f[base + 2]
        val h = f[base + 3]
        val radius = f[base + 4]
        fillPaint.color = argb
        fillPaint.alpha = alphaByte(f[base + 5])
        rect.set(x, y, x + w, y + h)
        if (radius > 0f) {
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        } else {
            canvas.drawRect(rect, fillPaint)
        }
    }

    private fun drawCircle(canvas: Canvas, f: FloatArray, base: Int, argb: Int) {
        fillPaint.color = argb
        fillPaint.alpha = alphaByte(f[base + 3])
        canvas.drawCircle(f[base], f[base + 1], f[base + 2], fillPaint)
    }

    private fun drawLine(canvas: Canvas, f: FloatArray, base: Int, argb: Int) {
        strokePaint.color = argb
        strokePaint.alpha = alphaByte(f[base + 5])
        strokePaint.strokeWidth = f[base + 4]
        canvas.drawLine(f[base], f[base + 1], f[base + 2], f[base + 3], strokePaint)
    }

    /** Filled pie wedge: the [lg.core.scene.Scene.ARC] float layout has no stroke width. */
    private fun drawArc(canvas: Canvas, f: FloatArray, base: Int, argb: Int) {
        val cx = f[base]
        val cy = f[base + 1]
        val r = f[base + 2]
        fillPaint.color = argb
        fillPaint.alpha = alphaByte(f[base + 5])
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, f[base + 3], f[base + 4], true, fillPaint)
    }

    private fun drawText(canvas: Canvas, scene: Scene, i: Int, base: Int, argb: Int, strings: Strings) {
        val text = strings.get(scene.key[i])
        preparePaint(scene.f, base, argb)
        val width = textPaint.measureText(text)
        val (drawX, baseline) = anchor(scene, i, base, width)
        canvas.drawText(text, drawX, baseline, textPaint)
    }

    private fun drawLabel(canvas: Canvas, scene: Scene, i: Int, base: Int, argb: Int) {
        val text = scene.label[i] ?: return
        preparePaint(scene.f, base, argb)
        val width = textPaint.measureText(text)
        val (drawX, baseline) = anchor(scene, i, base, width)
        canvas.drawText(text, drawX, baseline, textPaint)
    }

    private fun drawNumber(canvas: Canvas, scene: Scene, i: Int, base: Int, argb: Int) {
        formatInt(scene.key[i])
        preparePaint(scene.f, base, argb)
        val width = textPaint.measureText(numberChars, numberStart, numberCount)
        val (drawX, baseline) = anchor(scene, i, base, width)
        canvas.drawText(numberChars, numberStart, numberCount, drawX, baseline, textPaint)
    }

    private fun preparePaint(f: FloatArray, base: Int, argb: Int) {
        textPaint.color = argb
        textPaint.alpha = alphaByte(f[base + 3])
        textPaint.textSize = f[base + 2]
    }

    /** @return (drawX, baseline) for the horizontal [Align] and vertically-centered anchor `(x, y)`. */
    private fun anchor(scene: Scene, i: Int, base: Int, textWidth: Float): FloatArray {
        val f = scene.f
        val anchorX = f[base]
        val anchorY = f[base + 1]
        val drawX = when (scene.align[i].toInt()) {
            Align.CENTER.ordinal -> anchorX - textWidth / 2f
            Align.RIGHT.ordinal -> anchorX - textWidth
            else -> anchorX
        }
        textPaint.getFontMetrics(fontMetrics)
        val baseline = anchorY - (fontMetrics.ascent + fontMetrics.descent) / 2f
        anchorScratch[0] = drawX
        anchorScratch[1] = baseline
        return anchorScratch
    }

    // Reused instead of returning a Kotlin Pair (which would box two floats
    // every text primitive); a tiny fixed-size scratch array instead.
    private val anchorScratch = FloatArray(2)

    private fun formatInt(value: Int) {
        val buf = numberChars
        val end = buf.size
        var i = end
        val negative = value < 0
        if (value == 0) {
            i--
            buf[i] = '0'
        } else {
            var v = value.toLong()
            if (negative) v = -v
            while (v > 0) {
                i--
                buf[i] = '0' + (v % 10).toInt()
                v /= 10
            }
        }
        if (negative) {
            i--
            buf[i] = '-'
        }
        numberStart = i
        numberCount = end - i
    }
}
