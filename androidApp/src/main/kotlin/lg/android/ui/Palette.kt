package lg.android.ui

import android.content.Context
import android.content.res.Configuration
import lg.core.scene.Palette as CorePalette

/**
 * Maps [CorePalette] indices to ARGB colors for light and dark themes. A
 * pleasant, high-contrast, kid-friendly palette (PLAN.md §1 target
 * audience). [colorsFor] returns one of the two preallocated tables (never
 * allocates), so it is safe to call from [CanvasRenderer]'s draw loop.
 */
internal object Palette {
    // Index == lg.core.scene.Palette constant; size == CorePalette.COUNT.
    private val LIGHT: IntArray = intArrayOf(
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
    )

    private val DARK: IntArray = intArrayOf(
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
    )

    init {
        check(LIGHT.size == CorePalette.COUNT) { "LIGHT palette size mismatch" }
        check(DARK.size == CorePalette.COUNT) { "DARK palette size mismatch" }
    }

    /** Returns the color table matching the current light/dark configuration. Never allocates. */
    fun colorsFor(context: Context): IntArray {
        val night = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return if (night) DARK else LIGHT
    }
}
