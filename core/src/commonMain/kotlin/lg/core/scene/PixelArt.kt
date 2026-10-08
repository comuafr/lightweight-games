package lg.core.scene

/**
 * Draws a square pixel-art sprite (ASCII rows, one character per pixel) into a
 * [Scene] as chunky rectangles. Horizontal runs of the same color are merged
 * into one rectangle, so a 12x12 sprite costs well under 60 primitives.
 *
 * Legend: `.` empty, `c` cyan, `p` pink, `y` yellow, `g` green, `o` orange,
 * `r` red, `w` white, `u` purple, `d` dim (dark edge color).
 */
internal object PixelArt {
    /** Overlap between neighbouring pixels, so anti-aliasing never leaves hairline gaps. */
    private const val SEAM: Float = 0.35f

    fun draw(scene: Scene, size: Float, rows: Array<String>) {
        val n = rows.size
        val cell = size / n
        for (y in 0 until n) {
            val row = rows[y]
            var x = 0
            while (x < row.length) {
                val ch = row[x]
                var end = x + 1
                while (end < row.length && row[end] == ch) end++
                val color = colorOf(ch)
                if (color >= 0) {
                    scene.rect(x * cell, y * cell, (end - x) * cell + SEAM, cell + SEAM, color)
                }
                x = end
            }
        }
    }

    private fun colorOf(ch: Char): Int = when (ch) {
        'c' -> Palette.NEON_CYAN
        'p' -> Palette.NEON_PINK
        'y' -> Palette.NEON_YELLOW
        'g' -> Palette.NEON_GREEN
        'o' -> Palette.NEON_ORANGE
        'r' -> Palette.NEON_RED
        'w' -> Palette.NEON_WHITE
        'u' -> Palette.NEON_PURPLE
        'd' -> Palette.BEZEL_EDGE
        else -> -1
    }
}
