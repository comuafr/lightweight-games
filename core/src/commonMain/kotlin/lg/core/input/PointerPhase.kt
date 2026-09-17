package lg.core.input

/** Raw pointer lifecycle phase, as forwarded by the platform without interpretation. */
public enum class PointerPhase {
    DOWN,
    MOVE,
    UP,
    CANCEL,
}
