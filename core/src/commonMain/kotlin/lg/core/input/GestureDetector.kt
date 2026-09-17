package lg.core.input

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns raw, per-pointer [PointerPhase] events into interpreted [Gesture]s.
 *
 * Recognition thresholds:
 * - **Tap**: total movement stays below [TAP_MAX_DIST_PT] and the press lasts
 *   less than [TAP_MAX_MS].
 * - **Swipe**: total movement reaches at least [SWIPE_MIN_DIST_PT]; direction
 *   is the dominant axis (horizontal vs. vertical) of the displacement.
 * - **Long press**: the pointer stays down for at least [LONG_PRESS_MS]
 *   without moving more than [TAP_MAX_DIST_PT]; fired from [update], not from
 *   an `UP` event, since the pointer is still down when the threshold is
 *   crossed.
 *
 * Up to [MAX_POINTERS] simultaneous pointers are tracked using fixed,
 * preallocated arrays — no per-pointer allocation. A single [Gesture]
 * instance is reused and mutated in place before invoking [out].
 *
 * Both the raw pointer stream and the interpreted gesture stream are always
 * available to callers; this detector only adds the latter on top of the
 * former (see PLAN.md §4).
 */
public class GestureDetector(private val out: (Gesture) -> Unit) {
    public companion object {
        public const val MAX_POINTERS: Int = 10
        public const val TAP_MAX_DIST_PT: Float = 12f
        public const val TAP_MAX_MS: Long = 250L
        public const val SWIPE_MIN_DIST_PT: Float = 24f
        public const val LONG_PRESS_MS: Long = 450L
        private const val FREE: Int = -1
    }

    private val slotPointerId = IntArray(MAX_POINTERS) { FREE }
    private val startX = FloatArray(MAX_POINTERS)
    private val startY = FloatArray(MAX_POINTERS)
    private val lastX = FloatArray(MAX_POINTERS)
    private val lastY = FloatArray(MAX_POINTERS)
    private val startTimeMs = LongArray(MAX_POINTERS)
    private val longPressFired = BooleanArray(MAX_POINTERS)
    private val movedBeyondTap = BooleanArray(MAX_POINTERS)

    private val reusedGesture = Gesture()

    /** Feeds one raw pointer event. Must be called for every DOWN/MOVE/UP/CANCEL. */
    public fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float, timeMs: Long) {
        when (phase) {
            PointerPhase.DOWN -> onDown(id, x, y, timeMs)
            PointerPhase.MOVE -> onMove(id, x, y)
            PointerPhase.UP -> onUp(id, x, y, timeMs)
            PointerPhase.CANCEL -> onCancel(id)
        }
    }

    /** Advances time without pointer input, used to detect long-press while a pointer stays still. */
    public fun update(timeMs: Long) {
        for (slot in 0 until MAX_POINTERS) {
            if (slotPointerId[slot] == FREE) continue
            if (longPressFired[slot] || movedBeyondTap[slot]) continue
            if (timeMs - startTimeMs[slot] >= LONG_PRESS_MS) {
                longPressFired[slot] = true
                emit(GestureKind.LONG_PRESS, lastX[slot], lastY[slot], slotPointerId[slot])
            }
        }
    }

    private fun onDown(id: Int, x: Float, y: Float, timeMs: Long) {
        val slot = findSlot(id)
        val target = slot ?: findFreeSlot() ?: return
        slotPointerId[target] = id
        startX[target] = x
        startY[target] = y
        lastX[target] = x
        lastY[target] = y
        startTimeMs[target] = timeMs
        longPressFired[target] = false
        movedBeyondTap[target] = false
    }

    private fun onMove(id: Int, x: Float, y: Float) {
        val slot = findSlot(id) ?: return
        lastX[slot] = x
        lastY[slot] = y
        if (distance(x - startX[slot], y - startY[slot]) > TAP_MAX_DIST_PT) {
            movedBeyondTap[slot] = true
        }
    }

    private fun onUp(id: Int, x: Float, y: Float, timeMs: Long) {
        val slot = findSlot(id) ?: return
        if (!longPressFired[slot]) {
            val dx = x - startX[slot]
            val dy = y - startY[slot]
            val dt = timeMs - startTimeMs[slot]
            val dist = distance(dx, dy)
            if (!movedBeyondTap[slot] && dist <= TAP_MAX_DIST_PT && dt < TAP_MAX_MS) {
                emit(GestureKind.TAP, x, y, id)
            } else if (dist >= SWIPE_MIN_DIST_PT) {
                val kind = if (abs(dx) >= abs(dy)) {
                    if (dx >= 0f) GestureKind.SWIPE_RIGHT else GestureKind.SWIPE_LEFT
                } else {
                    if (dy >= 0f) GestureKind.SWIPE_DOWN else GestureKind.SWIPE_UP
                }
                emit(kind, x, y, id)
            }
        }
        freeSlot(slot)
    }

    private fun onCancel(id: Int) {
        val slot = findSlot(id) ?: return
        freeSlot(slot)
    }

    private fun emit(kind: GestureKind, x: Float, y: Float, pointerId: Int) {
        reusedGesture.kind = kind
        reusedGesture.x = x
        reusedGesture.y = y
        reusedGesture.pointerId = pointerId
        out(reusedGesture)
    }

    private fun findSlot(id: Int): Int? {
        for (slot in 0 until MAX_POINTERS) {
            if (slotPointerId[slot] == id) return slot
        }
        return null
    }

    private fun findFreeSlot(): Int? {
        for (slot in 0 until MAX_POINTERS) {
            if (slotPointerId[slot] == FREE) return slot
        }
        return null
    }

    private fun freeSlot(slot: Int) {
        slotPointerId[slot] = FREE
    }

    private fun distance(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)
}
