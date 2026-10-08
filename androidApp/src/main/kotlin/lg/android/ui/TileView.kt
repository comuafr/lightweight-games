package lg.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.View.MeasureSpec
import kotlin.math.roundToInt
import lg.core.GameMeta
import lg.core.scene.Scene
import lg.core.scene.Palette as CorePalette

/**
 * One catalogue tile: draws [GameMeta.icon] through [CanvasRenderer], the
 * localized title below it, and — when [locked] — a scrim plus a small
 * drawn padlock. Square-ish, at least [MIN_SIZE_DP] dp on a side. Pressed
 * state is a simple alpha change on the background (no ripple drawable, to
 * avoid pulling in any AppCompat/Material dependency).
 */
internal class TileView(
    context: Context,
    val meta: GameMeta,
) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val iconScene = Scene(ICON_SCENE_CAPACITY)
    private val strings = Strings(context)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // TextPaint (not plain Paint) because TextUtils.ellipsize below requires it.
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lockBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lockShacklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    /** Position in the catalogue; picks the neon outline color. */
    var borderIndex: Int = 0

    var locked: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
        minimumWidth = (MIN_SIZE_DP * density).roundToInt()
        minimumHeight = (MIN_SIZE_DP * density).roundToInt()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minPx = (MIN_SIZE_DP * density).roundToInt()
        val proposed = MeasureSpec.getSize(widthMeasureSpec)
        val side = if (proposed > 0) proposed else minPx
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val colors = Palette.colorsFor(context)
        val sizeDp = width / density
        if (sizeDp <= 0f) return
        val pad = sizeDp * PADDING_FRACTION
        val titleAreaDp = sizeDp * TITLE_AREA_FRACTION
        val iconSizeDp = (sizeDp - 2 * pad - titleAreaDp).coerceAtLeast(1f)

        canvas.save()
        canvas.scale(density, density)

        val corner = sizeDp * CORNER_FRACTION
        rect.set(0f, 0f, sizeDp, sizeDp)
        // Arcade card: neon outline around a dark body, colour cycling with the game's position.
        bgPaint.color = colors[NEON_BORDERS[borderIndex % NEON_BORDERS.size]]
        bgPaint.alpha = if (isPressed) PRESSED_ALPHA else 255
        canvas.drawRoundRect(rect, corner, corner, bgPaint)
        rect.set(BORDER_DP, BORDER_DP, sizeDp - BORDER_DP, sizeDp - BORDER_DP)
        bgPaint.color = colors[CorePalette.BEZEL]
        canvas.drawRoundRect(rect, corner - BORDER_DP, corner - BORDER_DP, bgPaint)
        rect.set(0f, 0f, sizeDp, sizeDp)

        canvas.save()
        canvas.translate(pad, pad)
        iconScene.clear()
        meta.icon(iconScene, iconSizeDp)
        CanvasRenderer.draw(canvas, iconScene, colors, strings)
        canvas.restore()

        titlePaint.color = colors[CorePalette.NEON_WHITE]
        titlePaint.typeface = Typeface.DEFAULT_BOLD
        titlePaint.textSize = titleAreaDp * TITLE_TEXT_SIZE_FRACTION
        val titleCenterY = sizeDp - titleAreaDp / 2f
        val baseline = titleCenterY - (titlePaint.ascent() + titlePaint.descent()) / 2f
        // Longer titles ("Color Sequence", "Four in a Row", "Sliding
        // Puzzle"...) can exceed the tile width, in either language, at the
        // narrow MIN_SIZE_DP tile size a 2-column phone layout produces.
        // Ellipsize instead of clipping/overflowing so every tile stays
        // legible and the same size (PLAN.md §9 M5 "catalog layout sanity").
        val availableWidth = sizeDp - 2 * pad
        val title = TextUtils.ellipsize(strings.get(meta.titleKey).uppercase(), titlePaint, availableWidth, TextUtils.TruncateAt.END)
        canvas.drawText(title, 0, title.length, sizeDp / 2f, baseline, titlePaint)

        if (locked) {
            scrimPaint.color = colors[CorePalette.BG]
            scrimPaint.alpha = LOCK_SCRIM_ALPHA
            canvas.drawRoundRect(rect, corner, corner, scrimPaint)
            drawPadlock(canvas, colors, sizeDp)
        }

        canvas.restore()
    }

    private fun drawPadlock(canvas: Canvas, colors: IntArray, sizeDp: Float) {
        val cx = sizeDp / 2f
        val cy = sizeDp / 2f
        val bodyW = sizeDp * 0.2f
        val bodyH = sizeDp * 0.16f
        val bodyTop = cy - bodyH * 0.1f

        lockShacklePaint.color = colors[CorePalette.FG]
        lockShacklePaint.strokeWidth = bodyW * 0.18f
        val shackleR = bodyW * 0.32f
        rect.set(cx - shackleR, bodyTop - shackleR * 2f, cx + shackleR, bodyTop)
        canvas.drawArc(rect, 180f, 180f, false, lockShacklePaint)

        lockBodyPaint.color = colors[CorePalette.FG]
        rect.set(cx - bodyW / 2f, bodyTop, cx + bodyW / 2f, bodyTop + bodyH)
        canvas.drawRoundRect(rect, bodyW * 0.2f, bodyW * 0.2f, lockBodyPaint)
    }

    private companion object {
        const val MIN_SIZE_DP = 120f
        const val PADDING_FRACTION = 0.1f
        const val TITLE_AREA_FRACTION = 0.22f
        const val TITLE_TEXT_SIZE_FRACTION = 0.55f
        const val CORNER_FRACTION = 0.08f
        const val PRESSED_ALPHA = 180
        const val LOCK_SCRIM_ALPHA = 140
        const val ICON_SCENE_CAPACITY = 160
        const val BORDER_DP = 2.5f
        val NEON_BORDERS = intArrayOf(
            CorePalette.NEON_CYAN,
            CorePalette.NEON_PINK,
            CorePalette.NEON_YELLOW,
            CorePalette.NEON_GREEN,
        )
    }
}
