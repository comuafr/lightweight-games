package lg.core.session

import lg.core.Complexity
import lg.core.Game
import lg.core.GameMeta
import lg.core.GameStatus
import lg.core.Orientation
import lg.core.Tier
import lg.core.input.Gesture
import lg.core.input.PointerPhase
import lg.core.scene.Prim
import lg.core.scene.Scene
import lg.core.scene.StringKey
import lg.core.store.Scores
import lg.core.store.Settings
import lg.core.testutil.FakeClock
import lg.core.testutil.FakeStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A game that ends with [finalScore] on its first update. */
private class EndsWithScoreGame(private val finalScore: Int) : Game {
    override val meta: GameMeta get() = error("not used")
    override var status: GameStatus = GameStatus.Idle
        private set
    override var score: Int = 0
        private set
    override val wantsFrames: Boolean = true

    override fun reset(seed: Long, players: Int) {
        status = GameStatus.Running
        score = 0
    }

    override fun resize(width: Float, height: Float): Unit = Unit

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float): Unit = Unit

    override fun onGesture(gesture: Gesture): Unit = Unit

    override fun update(dtMs: Int): Boolean {
        score = finalScore
        status = GameStatus.Over(0)
        return true
    }

    override fun render(scene: Scene): Unit = Unit
}

public class HighScoreSessionTest {
    private val defaults = intArrayOf(100, 60, 40, 20, 10)

    private fun session(
        store: FakeStore,
        finalScore: Int,
        withTable: Boolean = true,
    ): GameSession {
        val meta = GameMeta(
            id = "arcade",
            titleKey = StringKey.TITLE_SNAKE,
            tier = Tier.FREE,
            players = 1..1,
            orientation = Orientation.PORTRAIT,
            complexity = Complexity.S,
            resumable = false,
            factory = { EndsWithScoreGame(finalScore) },
            icon = { _, _ -> },
            defaultScores = if (withTable) defaults else null,
        )
        val s = GameSession(meta, Scores(store), Settings(store), FakeClock())
        s.resize(400f, 800f)
        s.start(players = 1, seed = 1L)
        return s
    }

    private fun labels(scene: Scene): List<String> =
        (0 until scene.count).filter { scene.kind[it] == Prim.LABEL }.mapNotNull { scene.label[it] }

    private fun keys(scene: Scene): List<Int> =
        (0 until scene.count).filter { scene.kind[it] == Prim.TEXT }.map { scene.key[it] }

    @Test
    public fun aScoreThatEarnsAPlaceAsksForInitialsOnceThenShowsTheTable(): Unit {
        val store = FakeStore()
        val s = session(store, finalScore = 50)
        var asked = 0
        var askedScore = -1
        var askedRank = -1
        s.onEnterInitials = { score, rank ->
            asked++
            askedScore = score
            askedRank = rank
        }
        s.update(16)
        s.update(16)

        assertEquals(1, asked)
        assertEquals(50, askedScore)
        assertEquals(2, askedRank)
        assertIs<Overlay.NewHighScore>(s.overlay)
        assertTrue(s.isAwaitingInitials)

        s.submitInitials("abc")
        assertIs<Overlay.Over>(s.overlay)
        assertFalse(s.isAwaitingInitials)
        val lines = Scores(store).table("arcade", defaults)
        assertEquals(listOf(100, 60, 50, 40, 20), lines.map { it.score })
        assertEquals("ABC", lines[2].name)
        assertEquals("ABC", Scores(store).lastInitials())
        assertEquals(50, Scores(store).best("arcade"))
    }

    @Test
    public fun aScoreBelowTheTableGoesStraightToGameOver(): Unit {
        val store = FakeStore()
        val s = session(store, finalScore = 7)
        var asked = 0
        s.onEnterInitials = { _, _ -> asked++ }
        s.update(16)
        assertEquals(0, asked)
        assertIs<Overlay.Over>(s.overlay)
        assertEquals(7, Scores(store).best("arcade"), "the best score is still recorded")
    }

    @Test
    public fun withoutAHandlerTheScoreIsEnteredAtOnceUnderTheLastInitials(): Unit {
        val store = FakeStore()
        Scores(store).saveLastInitials("zoe")
        val s = session(store, finalScore = 70)
        s.update(16)
        assertIs<Overlay.Over>(s.overlay)
        val lines = Scores(store).table("arcade", defaults)
        assertEquals("ZOE", lines[1].name)
        assertEquals(70, lines[1].score)
    }

    @Test
    public fun blankInitialsFallBackToTheLastOnesOrDashes(): Unit {
        val store = FakeStore()
        val s = session(store, finalScore = 70)
        s.onEnterInitials = { _, _ -> }
        s.update(16)
        s.submitInitials("  ")
        assertEquals("---", Scores(store).table("arcade", defaults)[1].name)
    }

    @Test
    public fun submittingInitialsWhenNoneAreAwaitedDoesNothing(): Unit {
        val store = FakeStore()
        val s = session(store, finalScore = 50)
        s.submitInitials("abc")
        assertTrue(Scores(store).table("arcade", defaults).all { it.isDefault })
    }

    @Test
    public fun aGameWithoutATableKeepsTheOldBehaviour(): Unit {
        val store = FakeStore()
        val s = session(store, finalScore = 500, withTable = false)
        var asked = 0
        s.onEnterInitials = { _, _ -> asked++ }
        s.update(16)
        assertEquals(0, asked)
        assertIs<Overlay.Over>(s.overlay)
        assertEquals(500, Scores(store).best("arcade"))
        assertEquals(null, store.getString("hs.arcade", null))
    }

    @Test
    public fun theStartAndEndScreensDrawTheTableWithoutDroppingAnything(): Unit {
        val store = FakeStore()
        val s = session(store, finalScore = 50)
        val scene = Scene()

        // A restart shows the start screen again.
        val meta = s.meta
        val start = GameSession(meta, Scores(store), Settings(store), FakeClock())
        start.resize(400f, 800f)
        start.render(scene)
        assertEquals(0, scene.dropped)
        assertTrue(labels(scene).containsAll(listOf("COM", "REG", "ZOE", "BOB", "AXL")), "the defaults are on the start screen")
        assertTrue(StringKey.HIGH_SCORES.ordinal in keys(scene))
        assertTrue(StringKey.GRAND_CHAMPION.ordinal in keys(scene))

        s.onEnterInitials = { _, _ -> }
        s.update(16)
        s.render(scene)
        assertEquals(0, scene.dropped)
        assertTrue(StringKey.NEW_HIGH_SCORE.ordinal in keys(scene))
        s.submitInitials("moi")
        s.render(scene)
        assertEquals(0, scene.dropped)
        assertTrue("MOI" in labels(scene))
    }

    @Test
    public fun theSavedRecordOfAnOlderVersionIsKeptInTheTable(): Unit {
        val store = FakeStore()
        store.putInt("score.arcade", 33)
        val s = session(store, finalScore = 5)
        s.update(16)
        val lines = Scores(store).table("arcade", defaults)
        assertEquals(33, lines.first { !it.isDefault }.score)
        assertEquals("---", lines.first { !it.isDefault }.name)
    }
}
