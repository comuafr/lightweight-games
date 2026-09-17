package lg.core.session

/** Modal overlay currently drawn on top of the game by [GameSession]. */
public sealed class Overlay {
    /** No overlay: input and (if wanted) the frame loop go straight to the game. */
    public object None : Overlay()

    /** Shown before the first [GameSession.start]: "Tap to start", or a 1/2 player choice. */
    public object Start : Overlay()

    /** Shown while [GameSession.pause] is in effect: "Resume" / "Restart". */
    public object Paused : Overlay()

    /** Shown once the game reaches [lg.core.GameStatus.Over]: result + "Restart". */
    public class Over(public val winner: Int) : Overlay()
}
