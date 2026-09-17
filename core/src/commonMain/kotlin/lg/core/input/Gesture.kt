package lg.core.input

/** Interpreted gesture kinds produced by [GestureDetector]. */
public enum class GestureKind {
    TAP,
    SWIPE_UP,
    SWIPE_DOWN,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    LONG_PRESS,
}

/**
 * A single reused, mutable gesture event.
 *
 * [GestureDetector] owns exactly one instance of this class and mutates it in
 * place before invoking its output callback, so recognizing a gesture never
 * allocates. Consumers must read the fields synchronously within the
 * callback; the instance's contents are undefined afterwards.
 */
public class Gesture {
    public var kind: GestureKind = GestureKind.TAP
    public var x: Float = 0f
    public var y: Float = 0f
    public var pointerId: Int = 0
}
