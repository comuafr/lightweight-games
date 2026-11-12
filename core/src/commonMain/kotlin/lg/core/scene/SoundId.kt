package lg.core.scene

/**
 * Procedural sound effects a game can request via [Scene.sound]. Each value
 * carries a single-bit [bit] mask so a whole frame's worth of requested
 * sounds can be OR-combined into [Scene.soundMask] without allocation.
 */
public enum class SoundId(public val bit: Int) {
    TICK(1 shl 0),
    EAT(1 shl 1),
    HIT(1 shl 2),
    WIN(1 shl 3),
    LOSE(1 shl 4),
    BONUS(1 shl 5),
    EXPLOSION(1 shl 6),
}
