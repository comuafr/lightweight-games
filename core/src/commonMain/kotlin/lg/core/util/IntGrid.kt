package lg.core.util

/** Fixed-size 2D grid of integers backed by a single flat [IntArray] (no per-cell boxing). */
public class IntGrid(public val cols: Int, public val rows: Int) {
    private val cells = IntArray(cols * rows)

    /** Returns true if `(x, y)` is within `[0, cols) x [0, rows)`. */
    public fun inBounds(x: Int, y: Int): Boolean = x in 0 until cols && y in 0 until rows

    public fun get(x: Int, y: Int): Int = cells[y * cols + x]

    public fun set(x: Int, y: Int, value: Int) {
        cells[y * cols + x] = value
    }

    /** Sets every cell to [value]. */
    public fun fill(value: Int) {
        cells.fill(value)
    }
}
