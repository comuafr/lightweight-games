package lg.core.session

/** Modal overlay currently drawn on top of the game by [GameSession]. */
public sealed class Overlay {
    /** No overlay: input and (if wanted) the frame loop go straight to the game. */
    public object None : Overlay()

    /** Shown before the first [GameSession.start]: "Tap to start", or a 1/2 player choice. */
    public object Start : Overlay()

    /** Shown while [GameSession.pause] is in effect: "Resume" / "Restart". */
    public object Paused : Overlay()

    /** Shown once the game reaches [lg.core.GameStatus.Over]: result + "Restart" (and the high-score table). */
    public class Over(public val winner: Int) : Overlay()

    /**
     * Shown when a finished game's [score] earned place [rank] (0 = grand champion) in the high-score table, while the
     * platform asks for the player's initials; ends with [GameSession.submitInitials].
     */
    public class NewHighScore(public val rank: Int, public val score: Int) : Overlay()
}
