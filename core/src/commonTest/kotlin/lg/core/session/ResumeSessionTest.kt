package lg.core.session

import lg.core.GameMeta
import lg.core.GameRegistry
import lg.core.input.PointerPhase
import lg.core.store.Scores
import lg.core.store.Settings
import lg.core.testutil.FakeStore
import lg.core.util.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Leaving a saved game and coming back to it, on the six real resumable games. */
public class ResumeSessionTest {
    private val store = FakeStore()
    private var now = 0L
    private val clock = object : Clock {
        override fun nowMs(): Long = now
    }

    private fun open(meta: GameMeta): GameSession {
        val session = GameSession(meta, Scores(store), Settings(store), clock)
        session.tryRestore(store)
        session.resize(VIEW_W, VIEW_H)
        return session
    }

    private fun saved(meta: GameMeta): String? = store.getString("save.${meta.id}", null)

    /** Taps a point given in the 360 x 560 play zone. */
    private fun GameSession.tap(x: Float, y: Float) {
        val vx = playOriginX + x * playScale
        val vy = playOriginY + y * playScale
        now += 1000
        onPointer(0, PointerPhase.DOWN, vx, vy)
        onPointer(0, PointerPhase.UP, vx, vy)
    }

    /** First or second of the two buttons of the start screen offering to resume. */
    private fun GameSession.tapButton(row: Int) = tap(180f, 240f + row * 80f)

    /**
     * A state worth resuming for each game: games with a level chooser need a level picked, and the minesweeper a first
     * cell opened. They are reached by trying taps over the play zone until the game says it has something to save.
     */
    private fun begin(meta: GameMeta): GameSession {
        val session = open(meta)
        session.start(players = 1, seed = 42)
        session.persist(store)
        if (meta.id == "mixup") makeOneMove(session, meta)
        var y = 40f
        while (saved(meta) == null && y < 540f) {
            var x = 20f
            while (saved(meta) == null && x < 340f) {
                session.tap(x, y)
                session.persist(store)
                x += 40f
            }
            y += 30f
        }
        assertNotNull(saved(meta), "${meta.id}: no resumable state reached")
        return session
    }

    /** Mix-Up needs a precise move, a swap that lines something up: picks level 1, then tries neighbours in turn. */
    private fun makeOneMove(session: GameSession, meta: GameMeta) {
        session.tap(44f, 148f)
        for (row in 0 until 9) {
            for (col in 0 until 6) {
                session.tap(30f + col * 50f, 125f + row * 50f)
                session.tap(80f + col * 50f, 125f + row * 50f)
                session.persist(store)
                if (saved(meta) != null) return
                repeat(10) { session.update(50) }
            }
        }
    }

    private fun resumable(): List<GameMeta> = GameRegistry.byId.values.filter { it.resumable }

    @Test
    public fun theSixSavedGamesAreStillTheResumableOnes() {
        assertEquals(setOf("g2048", "sliding", "mines", "sudoku", "nonogram", "mixup"), resumable().map { it.id }.toSet())
    }

    @Test
    public fun resumingBringsBackTheGameLeftBehind() {
        for (meta in resumable()) {
            begin(meta).persist(store)
            val left = saved(meta)

            val again = open(meta)
            assertIs<Overlay.Start>(again.overlay)
            again.tapButton(0)
            assertIs<Overlay.None>(again.overlay, "${meta.id}: Resume did not enter the game")
            again.persist(store)
            assertEquals(left, saved(meta), "${meta.id}: the resumed game differs from the one left")
        }
    }

    @Test
    public fun leavingFromTheStartScreenKeepsTheSavedGame() {
        for (meta in resumable()) {
            begin(meta)
            val left = saved(meta)
            open(meta).persist(store)
            assertEquals(left, saved(meta), "${meta.id}: just looking changed the save")
        }
    }

    @Test
    public fun restartDropsTheSavedGame() {
        for (meta in resumable()) {
            begin(meta)
            val left = saved(meta)
            val again = open(meta)
            again.tapButton(1)
            assertIs<Overlay.None>(again.overlay, "${meta.id}: Restart did not start a game")
            again.persist(store)
            assertTrue(saved(meta) != left, "${meta.id}: the old game is still saved after Restart")
        }
    }

    @Test
    public fun aGameNeverStartedIsNotSaved() {
        for (meta in resumable()) {
            open(meta).persist(store)
            assertNull(saved(meta), "${meta.id}: saved before the start")
        }
    }

    @Test
    public fun savesWithNothingToResumeAreNotOffered() {
        // What earlier versions could write: an empty 2048 grid, and a blocked (finished) one.
        val empty = (0 until 16).joinToString(";") { "c$it=0" } + ";score=0;won=0;rng=1"
        val blocked = (0 until 16).joinToString(";") { "c$it=${1 + (it + it / 4) % 2}" } + ";score=8;won=0;rng=1"
        val meta = GameRegistry.byId.getValue("g2048")
        for (data in listOf(empty, blocked)) {
            store.putString("save.g2048", data)
            val session = open(meta)
            // No "Resume": the single start button starts a new game, with its two first tiles.
            session.tap(180f, 560f - 24f - 32f)
            assertIs<Overlay.None>(session.overlay)
            session.persist(store)
            assertTrue(saved(meta) != data)
        }
    }

    private companion object {
        const val VIEW_W = 400f
        const val VIEW_H = 800f
    }
}
