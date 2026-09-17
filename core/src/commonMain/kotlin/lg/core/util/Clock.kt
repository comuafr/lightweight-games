package lg.core.util

/** Time source injected into [lg.core.session.GameSession], implemented per platform. */
public interface Clock {
    /** Current time in milliseconds, from an arbitrary but monotonic-enough epoch. */
    public fun nowMs(): Long
}
