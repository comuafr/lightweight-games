package lg.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import kotlin.math.roundToInt
import lg.core.scene.Palette as CorePalette

/**
 * The paywall's parental gate (PLAN.md §6, §8): a button that only fires
 * [onConfirmed] after being held down for [holdMs], filling its own
 * background left-to-right as visual progress. Lifting the finger (or
 * disabling the view) before completion resets progress to zero; nothing is
 * triggered until a full, uninterrupted hold.
 *
 * No allocation happens per frame or per touch event: the tick [Runnable],
 * and every [Paint] / [RectF], are created once and reused. Progress ticks
 * are driven by [postOnAnimation] (vsync-aligned, like [android.view.Choreographer])
 * rather than a fixed-period timer.
 */
internal class HoldToConfirmButton @JvmOverloads constructor(
    context: Context,
    private val holdMs: Long = DEFAULT_HOLD_MS,
) : View(context) {

    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Invoked once, on the main thread, when a hold completes. */
    var onConfirmed: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val rect = RectF()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private var holding = false
    private var startUptimeMs = 0L
    private var progress = 0f

    private val tick = Runnable { onTick() }

    init {
        isClickable = true
        isFocusable = true
        minimumHeight = (MIN_HEIGHT_DP * density).roundToInt()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) cancelHold()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minH = (MIN_HEIGHT_DP * density).roundToInt()
        val proposedH = MeasureSpec.getSize(heightMeasureSpec)
        val h = if (proposedH > 0) maxOf(proposedH, minH) else minH
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!holding) {
                    holding = true
                    startUptimeMs = SystemClock.uptimeMillis()
                    postOnAnimation(tick)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun onTick() {
        if (!holding) return
        val elapsed = SystemClock.uptimeMillis() - startUptimeMs
        progress = (elapsed.toFloat() / holdMs).coerceIn(0f, 1f)
        invalidate()
        if (progress >= 1f) {
            holding = false
            progress = 0f
            onConfirmed?.invoke()
        } else {
            postOnAnimation(tick)
        }
    }

    private fun cancelHold() {
        if (!holding && progress == 0f) return
        holding = false
        progress = 0f
        removeCallbacks(tick)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val colors = Palette.colorsFor(context)
        val corner = h * CORNER_FRACTION
        rect.set(0f, 0f, w, h)
        bgPaint.color = colors[CorePalette.SURFACE]
        bgPaint.alpha = if (isEnabled) 255 else DISABLED_ALPHA
        canvas.drawRoundRect(rect, corner, corner, bgPaint)

        if (progress > 0f) {
            canvas.save()
            canvas.clipRect(0f, 0f, w * progress, h)
            fillPaint.color = colors[CorePalette.ACCENT]
            canvas.drawRoundRect(rect, corner, corner, fillPaint)
            canvas.restore()
        }

        textPaint.color = colors[if (isEnabled) CorePalette.FG else CorePalette.FG_DIM]
        textPaint.textSize = h * TEXT_SIZE_FRACTION
        val baseline = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, w / 2f, baseline, textPaint)
    }

    private companion object {
        const val DEFAULT_HOLD_MS = 3000L
        const val MIN_HEIGHT_DP = 64f
        const val CORNER_FRACTION = 0.18f
        const val TEXT_SIZE_FRACTION = 0.32f
        const val DISABLED_ALPHA = 120
    }
}
