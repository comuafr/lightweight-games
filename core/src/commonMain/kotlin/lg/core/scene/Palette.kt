package lg.core.scene

/**
 * Color indices used by [Scene.color]. The core never emits ARGB values; each
 * platform renderer maps these indices to actual colors depending on the
 * current light/dark theme (see PLAN.md §4).
 */
public object Palette {
    public const val BG: Int = 0
    public const val SURFACE: Int = 1
    public const val FG: Int = 2
    public const val FG_DIM: Int = 3
    public const val ACCENT: Int = 4
    public const val P1: Int = 5
    public const val P2: Int = 6
    public const val GRID: Int = 7
    public const val OK: Int = 8
    public const val DANGER: Int = 9
    public const val TILE_0: Int = 10
    public const val TILE_1: Int = 11
    public const val TILE_2: Int = 12
    public const val TILE_3: Int = 13
    public const val TILE_4: Int = 14
    public const val TILE_5: Int = 15
    public const val TILE_6: Int = 16
    public const val TILE_7: Int = 17
    public const val TILE_8: Int = 18
    public const val TILE_9: Int = 19
    public const val TILE_10: Int = 20
    public const val TILE_11: Int = 21

    // 80s-arcade colors: identical in light and dark themes (the cabinet is always dark).
    public const val BEZEL: Int = 22
    public const val BEZEL_EDGE: Int = 23
    public const val NEON_PINK: Int = 24
    public const val NEON_CYAN: Int = 25
    public const val NEON_YELLOW: Int = 26
    public const val NEON_GREEN: Int = 27
    public const val NEON_ORANGE: Int = 28
    public const val NEON_RED: Int = 29
    public const val NEON_WHITE: Int = 30
    public const val NEON_PURPLE: Int = 31

    // Digits of the minesweeper board (1 to 8): classic hues, tuned per theme to stay readable (contrast of at
    // least 4.5:1) on the light and the dark revealed-cell background.
    public const val NUMBER_1: Int = 32
    public const val NUMBER_2: Int = 33
    public const val NUMBER_3: Int = 34
    public const val NUMBER_4: Int = 35
    public const val NUMBER_5: Int = 36
    public const val NUMBER_6: Int = 37
    public const val NUMBER_7: Int = 38
    public const val NUMBER_8: Int = 39

    /** Total number of palette entries; platform color tables must have this size. */
    public const val COUNT: Int = 40
}
