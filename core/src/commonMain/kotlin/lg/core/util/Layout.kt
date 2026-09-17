package lg.core.util

/** A mutable axis-aligned rectangle in logical points, reused to avoid allocation. */
public class Rect(
    public var x: Float = 0f,
    public var y: Float = 0f,
    public var w: Float = 0f,
    public var h: Float = 0f,
)

/**
 * Layout helpers shared by every game: fitting a square play area below a HUD
 * band, and splitting the screen for two simultaneous local players.
 */
public object Layout {
    /** Minimum recommended touch target size, in points, per PLAN.md §4. */
    public const val MIN_TARGET_PT: Float = 48f

    /**
     * Computes the largest square that fits in `(w, h)` below a HUD band of
     * height [hudTop], centered horizontally, and writes the result into [out].
     */
    public fun fitSquare(w: Float, h: Float, hudTop: Float, out: Rect) {
        val availableH = (h - hudTop).coerceAtLeast(0f)
        val side = if (w < availableH) w else availableH
        out.x = (w - side) / 2f
        out.y = hudTop + (availableH - side) / 2f
        out.w = side
        out.h = side
    }

    /**
     * Splits `(w, h)` into two equal zones for local two-player games: top/bottom
     * in portrait (`h >= w`), left/right in landscape. Writes into [outA]
     * (player 1) and [outB] (player 2).
     */
    public fun splitTwoPlayers(w: Float, h: Float, outA: Rect, outB: Rect) {
        if (h >= w) {
            val half = h / 2f
            outA.x = 0f
            outA.y = 0f
            outA.w = w
            outA.h = half
            outB.x = 0f
            outB.y = half
            outB.w = w
            outB.h = half
        } else {
            val half = w / 2f
            outA.x = 0f
            outA.y = 0f
            outA.w = half
            outA.h = h
            outB.x = half
            outB.y = 0f
            outB.w = half
            outB.h = h
        }
    }
}
