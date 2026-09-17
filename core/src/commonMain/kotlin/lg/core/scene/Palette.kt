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

    /** Total number of palette entries; platform color tables must have this size. */
    public const val COUNT: Int = 22
}
