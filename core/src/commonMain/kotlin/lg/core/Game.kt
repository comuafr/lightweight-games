package lg.core

import lg.core.input.Gesture
import lg.core.input.PointerPhase
import lg.core.scene.Scene

/**
 * Contract implemented by every mini-game. [lg.core.session.GameSession] is
 * the only caller: it owns the instance's lifecycle, routes input, drives
 * [update]/[render], and layers the HUD and overlays on top.
 */
public interface Game {
    public val meta: GameMeta

    /** Current lifecycle status; see [GameStatus]. */
    public val status: GameStatus

    public val score: Int

    /** True while a real-time frame loop is needed (status == Running and the game is time-driven). */
    public val wantsFrames: Boolean

    /** (Re)starts the game with a fresh, deterministic [seed]. */
    public fun reset(seed: Long, players: Int = 1)

    /** Called on layout changes (initial size, rotation); logical points (dp / pt), never a reset. */
    public fun resize(width: Float, height: Float)

    /** Raw, un-interpreted pointer event; multi-touch, called for every DOWN/MOVE/UP/CANCEL. */
    public fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float)

    /** Interpreted gesture (TAP, SWIPE_*, LONG_PRESS); delivered alongside [onPointer], not instead of it. */
    public fun onGesture(gesture: Gesture)

    /** Advances simulation by [dtMs] (already clamped by the session). @return true if the scene changed. */
    public fun update(dtMs: Int): Boolean

    /** Draws the current state into [scene]. Must not allocate. */
    public fun render(scene: Scene)

    /** Serializes resumable state as `"k=v;k=v"`, or null if this game is not resumable / has nothing to save. */
    public fun snapshot(): String? = null

    /** Restores state produced by [snapshot]. @return true on success. */
    public fun restore(data: String): Boolean = false
}
