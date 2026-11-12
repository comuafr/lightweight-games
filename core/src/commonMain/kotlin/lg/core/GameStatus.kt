package lg.core

/**
 * Lifecycle status of a [Game].
 *
 * A game starts in [Idle] right after construction (before [Game.reset] is
 * called by the session), moves to [Running] once play begins, may be
 * temporarily [Paused] by the platform or the user, and ends in [Over] once
 * a terminal condition is reached.
 */
public sealed class GameStatus {
    /** Constructed but not yet started (before the first [Game.reset]). */
    public object Idle : GameStatus()

    /** Actively playable; input and, if [Game.wantsFrames], the frame loop are live. */
    public object Running : GameStatus()

    /** Temporarily suspended (background, explicit pause); state is preserved. */
    public object Paused : GameStatus()

    /**
     * Terminal state.
     *
     * @property winner 0 = single-player result (no winner to name, shown as "Game over"), 1 or 2 = the
     *   winning player in a two-player game, [DRAW] = a tie (nobody wins; the session shows "Draw"),
     *   [SOLO_WIN] / [SOLO_LOSS] = a one-player game that is won or lost (the session shows a
     *   "Well done!" or a "Too bad!" screen, with [Game.resultText] if the game has one).
     */
    public class Over(public val winner: Int) : GameStatus()

    public companion object {
        /** [Over.winner] value for a tie in a game that has two sides. */
        public const val DRAW: Int = -1

        /** [Over.winner] value for a one-player game the player won. */
        public const val SOLO_WIN: Int = -2

        /** [Over.winner] value for a one-player game the player lost. */
        public const val SOLO_LOSS: Int = -3
    }
}
