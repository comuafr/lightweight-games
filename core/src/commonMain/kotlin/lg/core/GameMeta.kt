package lg.core

import lg.core.scene.Scene
import lg.core.scene.StringKey

/** Purchase tier a game belongs to. */
public enum class Tier {
    FREE,
    UNLOCKED,
}

/** Orientation(s) a game supports; the platform locks the screen accordingly. */
public enum class Orientation {
    ANY,
    PORTRAIT,
    LANDSCAPE,
}

/** Coarse complexity bucket, used to order the catalogue (PLAN.md §1). */
public enum class Complexity {
    S,
    M,
    C,
}

/**
 * Static description of a game, independent of any running instance.
 *
 * @property factory Creates a fresh [Game] instance; called once per play session.
 * @property icon Draws a small representative tile icon into [Scene] at the given size (points, square).
 * @property defaultScores The built-in high-score table of a game whose score is a real arcade score (higher is
 *   better): [lg.core.store.Scores.TABLE_SIZE] values in strictly descending order, grand champion first, from a
 *   very hard score down to an easy one. `null` for a game without a high-score table.
 */
public class GameMeta(
    public val id: String,
    public val titleKey: StringKey,
    public val tier: Tier,
    public val players: IntRange,
    public val orientation: Orientation,
    public val complexity: Complexity,
    public val resumable: Boolean,
    public val factory: () -> Game,
    public val icon: (Scene, Float) -> Unit,
    public val defaultScores: IntArray? = null,
)
