package lg.core.scene

/**
 * Fixed-capacity, structure-of-arrays draw-list buffer.
 *
 * A [Scene] is the only channel through which a [lg.core.Game] communicates
 * what to draw. It is built once by [lg.core.session.GameSession] and reused
 * frame after frame: every `add*` call from a game's `render` writes into
 * pre-sized arrays at index [count] and never allocates. Platforms translate
 * the arrays into native drawing calls (`Canvas` on Android, `CGContext` on
 * iOS) without touching the core's internals.
 *
 * ### Layout
 *
 * Each primitive occupies one slot across all parallel arrays:
 * - [kind]: one of the [Prim] constants.
 * - [f]: exactly 6 floats per primitive (see below for the per-kind layout).
 * - [color]: a [Palette] index, never a raw ARGB value.
 * - [align]: a [Align] ordinal (only meaningful for TEXT/NUMBER/LABEL).
 * - [key]: for TEXT, the [StringKey] ordinal; for NUMBER, the integer value to
 *   render; unused (0) for other kinds.
 * - [label]: for LABEL, the literal string to draw; `null` for other kinds.
 *
 * ### Per-kind float layout (indices are relative to the primitive's 6-float
 * slice: `f[i*6 + 0]` .. `f[i*6 + 5]`)
 *
 * - [Prim.RECT]: `x, y, w, h, radius, alpha`
 * - [Prim.CIRCLE]: `x, y, radius, alpha, _, _`
 * - [Prim.LINE]: `x1, y1, x2, y2, strokeWidth, alpha`
 * - [Prim.ARC]: `x, y, radius, startAngleDeg, sweepAngleDeg, alpha`
 * - [Prim.TEXT]: `x, y, size, alpha, _, _` (string comes from [key] via [StringKey])
 * - [Prim.NUMBER]: `x, y, size, alpha, _, _` (integer value comes from [key])
 * - [Prim.LABEL]: `x, y, size, alpha, _, _` (string comes from [label])
 *
 * If more than [capacity] primitives are requested in a single frame, the
 * extra ones are silently dropped and counted in [dropped]; callers (notably
 * the fuzz tests) are expected to assert `dropped == 0`.
 */
public class Scene(public val capacity: Int = 1280) {
    private companion object {
        const val FLOATS_PER_PRIM = 6
    }

    /** Primitive kind for each used slot (see [Prim]). */
    public val kind: ByteArray = ByteArray(capacity)

    /** Six floats per primitive; see the class KDoc for the per-kind layout. */
    public val f: FloatArray = FloatArray(capacity * FLOATS_PER_PRIM)

    /** [Palette] index for each used slot. */
    public val color: IntArray = IntArray(capacity)

    /** [Align] ordinal for each used slot (meaningful for text-like kinds). */
    public val align: ByteArray = ByteArray(capacity)

    /** [StringKey] ordinal (TEXT) or literal integer value (NUMBER); 0 otherwise. */
    public val key: IntArray = IntArray(capacity)

    /** Literal string (LABEL only); `null` otherwise. */
    public val label: Array<String?> = arrayOfNulls(capacity)

    /** Number of primitives currently in use; valid indices are `0 until count`. */
    public var count: Int = 0
        private set

    /** Number of primitives dropped this frame because [capacity] was exceeded. */
    public var dropped: Int = 0
        private set

    /** OR-combined [SoundId.bit] values requested this frame. */
    public var soundMask: Int = 0
        private set

    /** Whether a haptic pulse was requested this frame. */
    public var haptic: Boolean = false
        private set

    /** Resets the scene to empty. Must be called once at the start of every frame. */
    public fun clear() {
        count = 0
        dropped = 0
        soundMask = 0
        haptic = false
    }

    /** Draws a filled rectangle with an optional corner [radius]. */
    public fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float = 0f, alpha: Float = 1f) {
        val i = beginPrim(Prim.RECT, color) ?: return
        val base = i * FLOATS_PER_PRIM
        f[base] = x
        f[base + 1] = y
        f[base + 2] = w
        f[base + 3] = h
        f[base + 4] = radius
        f[base + 5] = alpha
    }

    /** Draws a filled circle. */
    public fun circle(x: Float, y: Float, radius: Float, color: Int, alpha: Float = 1f) {
        val i = beginPrim(Prim.CIRCLE, color) ?: return
        val base = i * FLOATS_PER_PRIM
        f[base] = x
        f[base + 1] = y
        f[base + 2] = radius
        f[base + 3] = alpha
    }

    /** Draws a straight line segment. */
    public fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float = 1f, alpha: Float = 1f) {
        val i = beginPrim(Prim.LINE, color) ?: return
        val base = i * FLOATS_PER_PRIM
        f[base] = x1
        f[base + 1] = y1
        f[base + 2] = x2
        f[base + 3] = y2
        f[base + 4] = width
        f[base + 5] = alpha
    }

    /** Draws an arc (or full circle outline segment) between two angles, in degrees. */
    public fun arc(
        x: Float,
        y: Float,
        radius: Float,
        startAngleDeg: Float,
        sweepAngleDeg: Float,
        color: Int,
        alpha: Float = 1f,
    ) {
        val i = beginPrim(Prim.ARC, color) ?: return
        val base = i * FLOATS_PER_PRIM
        f[base] = x
        f[base + 1] = y
        f[base + 2] = radius
        f[base + 3] = startAngleDeg
        f[base + 4] = sweepAngleDeg
        f[base + 5] = alpha
    }

    /** Draws a localized string identified by [textKey]. */
    public fun text(
        x: Float,
        y: Float,
        textKey: StringKey,
        color: Int,
        size: Float,
        align: Align = Align.LEFT,
    ) {
        val i = beginPrim(Prim.TEXT, color) ?: return
        val base = i * FLOATS_PER_PRIM
        f[base] = x
        f[base + 1] = y
        f[base + 2] = size
        f[base + 3] = 1f
        this.align[i] = align.ordinal.toByte()
        key[i] = textKey.ordinal
    }

    /** Draws an integer value (e.g. a score), avoiding any string formatting in the core. */
    public fun number(
        x: Float,
        y: Float,
        value: Int,
        color: Int,
        size: Float,
        align: Align = Align.LEFT,
    ) {
        val i = beginPrim(Prim.NUMBER, color) ?: return
        val base = i * FLOATS_PER_PRIM
        f[base] = x
        f[base + 1] = y
        f[base + 2] = size
        f[base + 3] = 1f
        this.align[i] = align.ordinal.toByte()
        key[i] = value
    }

    /**
     * Draws a literal, non-localized string. Must only be used with string
     * literals (never built per-frame by concatenation) to keep `render` free
     * of allocations.
     */
    public fun label(
        x: Float,
        y: Float,
        text: String,
        color: Int,
        size: Float,
        align: Align = Align.LEFT,
    ) {
        val i = beginPrim(Prim.LABEL, color) ?: return
        val base = i * FLOATS_PER_PRIM
        f[base] = x
        f[base + 1] = y
        f[base + 2] = size
        f[base + 3] = 1f
        this.align[i] = align.ordinal.toByte()
        label[i] = text
    }

    /**
     * Scales every primitive from index [from] onward by [scale] (positions, sizes,
     * radii, stroke widths and text sizes; arc angles are unchanged), then moves it by
     * ([dx], [dy]). Used by [lg.core.session.GameSession] to place the game's own
     * drawing, made in its fixed logical play zone, inside the frame on screen.
     */
    public fun transformFrom(from: Int, scale: Float, dx: Float, dy: Float) {
        for (i in from until count) {
            val base = i * FLOATS_PER_PRIM
            f[base] = f[base] * scale + dx
            f[base + 1] = f[base + 1] * scale + dy
            when (kind[i]) {
                Prim.RECT -> {
                    f[base + 2] *= scale // w
                    f[base + 3] *= scale // h
                    f[base + 4] *= scale // corner radius
                }
                Prim.CIRCLE -> f[base + 2] *= scale // radius
                Prim.LINE -> {
                    f[base + 2] = f[base + 2] * scale + dx
                    f[base + 3] = f[base + 3] * scale + dy
                    f[base + 4] *= scale // stroke width
                }
                Prim.ARC -> f[base + 2] *= scale // radius
                else -> f[base + 2] *= scale // TEXT / NUMBER / LABEL: text size
            }
        }
    }

    /** Requests a procedural sound effect for this frame. */
    public fun sound(id: SoundId) {
        soundMask = soundMask or id.bit
    }

    /** Requests a haptic pulse for this frame. */
    public fun haptic() {
        haptic = true
    }

    /** Clears any sound request for this frame. Used by [lg.core.session.GameSession] to honor Settings. */
    public fun muteSound() {
        soundMask = 0
    }

    /** Clears any haptic request for this frame. Used by [lg.core.session.GameSession] to honor Settings. */
    public fun muteHaptic() {
        haptic = false
    }

    /** Reserves a slot for a new primitive, or returns `null` and bumps [dropped] if full. */
    private fun beginPrim(kind: Byte, color: Int): Int? {
        if (count >= capacity) {
            dropped++
            return null
        }
        val i = count
        this.kind[i] = kind
        this.color[i] = color
        this.align[i] = 0
        this.key[i] = 0
        this.label[i] = null
        count++
        return i
    }

    /**
     * Stable FNV-1a 32-bit hash over the used portion (`0 until count`) of the
     * scene, plus [dropped]. Intended for tests (determinism and
     * render-idempotency checks), not for production use.
     *
     * [soundMask] and [haptic] are deliberately excluded: they are transient,
     * one-shot events for this frame (a sound to play, a haptic pulse to
     * fire), not part of the drawn scene's state. Including them would make
     * `render` look non-idempotent in [lg.core.session.GameSession] on
     * purpose (see `eventsArmed`), which would break the fuzz tests'
     * render-idempotency assertion.
     */
    public fun hash(): Int {
        var h = -2128831035 // FNV offset basis (0x811c9dc5) as Int
        h = mix(h, count)
        h = mix(h, dropped)
        for (i in 0 until count) {
            h = mix(h, kind[i].toInt())
            h = mix(h, color[i])
            h = mix(h, align[i].toInt())
            h = mix(h, key[i])
            val base = i * FLOATS_PER_PRIM
            for (j in 0 until FLOATS_PER_PRIM) {
                h = mix(h, f[base + j].toRawBits())
            }
            val lbl = label[i]
            h = if (lbl != null) mix(h, lbl.hashCode()) else mix(h, 0)
        }
        return h
    }

    private fun mix(h: Int, value: Int): Int {
        val prime = 16777619 // FNV prime
        return (h xor value) * prime
    }
}
