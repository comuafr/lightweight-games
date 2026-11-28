package lg.core.session

import lg.core.GameRegistry
import lg.core.GameStatus
import lg.core.input.PointerPhase
import lg.core.scene.Palette
import lg.core.scene.Scene
import lg.core.scene.StringKey
import lg.core.store.Scores
import lg.core.store.Settings
import lg.core.testutil.FakeStore
import lg.core.util.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The end of a sudoku seen through the session: the game's own screen, one button, a moving celebration. */
public class SudokuEndSessionTest {
    private val store = FakeStore()
    private var now = 0L
    private val clock = object : Clock {
        override fun nowMs(): Long = now
    }

    private fun GameSession.tap(x: Float, y: Float) {
        now += 1000
        onPointer(0, PointerPhase.DOWN, playOriginX + x * playScale, playOriginY + y * playScale)
        onPointer(0, PointerPhase.UP, playOriginX + x * playScale, playOriginY + y * playScale)
    }

    /** A resumed game with a single blank, the top-left cell, whose right digit is 1. */
    private fun almostSolved(): GameSession {
        val solution = (0 until 81).joinToString("") { (((it / 9) * 3 + (it / 9) / 3 + it % 9) % 9 + 1).toString() }
        val start = "0" + solution.drop(1)
        store.putString("save.sudoku", "choosing=0;difficulty=0;given=$start;solution=$solution;current=$start;selected=0;score=80;rng=1")
        val session = GameSession(GameRegistry.byId.getValue("sudoku"), Scores(store), Settings(store), clock)
        session.tryRestore(store)
        session.resize(400f, 800f)
        session.tap(180f, 272f) // "Resume"
        assertIs<Overlay.None>(session.overlay)
        return session
    }

    private fun texts(session: GameSession): List<Int> {
        val scene = Scene()
        session.render(scene)
        return (0 until scene.count).filter { scene.kind[it] == lg.core.scene.Prim.TEXT }.map { scene.key[it] }
    }

    @Test
    public fun aWinKeepsTheFramesRunningUnderASingleButton() {
        val session = almostSolved()
        session.tap(32f, 447f) // digit 1
        assertEquals(GameStatus.SOLO_WIN, assertIs<Overlay.Over>(session.overlay).winner)
        assertTrue(session.needsFrameLoop)
        assertTrue(session.update(50))
        val shown = texts(session)
        assertTrue(StringKey.WIN_TITLE.ordinal in shown)
        assertTrue(StringKey.RESTART.ordinal in shown)
        assertTrue(StringKey.GAME_OVER.ordinal !in shown, "the session's own title is left out")
        var frames = 0
        while (session.needsFrameLoop) {
            session.update(50)
            assertTrue(frames++ < 1000)
        }
        // The button is at the bottom of the zone: it starts over, at the choice of difficulty.
        session.tap(180f, 504f)
        assertIs<Overlay.None>(session.overlay)
        assertTrue(StringKey.EASY.ordinal in texts(session))
    }

    @Test
    public fun aLossShowsTheSolutionWithoutAVeil() {
        val session = almostSolved()
        session.tap(85f, 447f) // digit 2: wrong
        assertIs<Overlay.None>(session.overlay)
        session.tap(270f, 491f) // "Show solution"
        assertEquals(GameStatus.SOLO_LOSS, assertIs<Overlay.Over>(session.overlay).winner)
        assertTrue(!session.needsFrameLoop)
        val scene = Scene()
        session.render(scene)
        assertTrue((0 until scene.count).none { scene.color[it] == Palette.SURFACE && scene.f[it * 6 + 5] > 0.85f && scene.f[it * 6 + 5] < 0.95f })
        val shown = texts(session)
        assertTrue(StringKey.LOSE_TITLE.ordinal in shown && StringKey.RESTART.ordinal in shown)
        session.persist(store)
        assertEquals(null, store.getString("save.sudoku", null), "a finished game is not kept")
    }
}
