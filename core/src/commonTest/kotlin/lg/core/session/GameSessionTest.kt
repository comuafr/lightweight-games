package lg.core.session

import lg.core.Complexity
import lg.core.Game
import lg.core.GameMeta
import lg.core.GameStatus
import lg.core.Orientation
import lg.core.Tier
import lg.core.games.snake.SnakeGame
import lg.core.input.Gesture
import lg.core.input.PointerPhase
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey
import lg.core.store.Scores
import lg.core.store.Settings
import lg.core.testutil.FakeClock
import lg.core.testutil.FakeStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Minimal [Game] whose `render` always sets a sound flag reflecting "what
 * just happened" the way a real game would (e.g. Snake's `ateThisTick`),
 * without resetting it itself. Used to prove [GameSession] — not the game —
 * is responsible for making sure that flag reaches the platform at most once
 * per actual state change.
 */
private class SoundOnEveryRenderGame : Game {
    override val meta: GameMeta get() = FAKE_META
    override var status: GameStatus = GameStatus.Idle
        private set
    override var score: Int = 0
        private set
    override val wantsFrames: Boolean = false

    /** Test hook: makes the next [update] call report a state change. */
    public var changeOnNextUpdate: Boolean = false

    override fun reset(seed: Long, players: Int) {
        status = GameStatus.Running
    }

    override fun resize(width: Float, height: Float): Unit = Unit

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float): Unit = Unit

    override fun onGesture(gesture: Gesture): Unit = Unit

    override fun update(dtMs: Int): Boolean {
        val changed = changeOnNextUpdate
        changeOnNextUpdate = false
        return changed
    }

    override fun render(scene: Scene) {
        // Deliberately never clears this: stands in for a game that keeps its
        // "last event" flags readable across renders, as real games do.
        scene.sound(SoundId.TICK)
    }

    private companion object {
        val FAKE_META = GameMeta(
            id = "fake-sound",
            titleKey = StringKey.TITLE_SNAKE,
            tier = Tier.FREE,
            players = 1..1,
            orientation = Orientation.ANY,
            complexity = Complexity.S,
            resumable = false,
            factory = { error("factory not used in this test") },
            icon = { _, _ -> },
        )
    }
}

private class DiesOnFirstUpdateGame : Game {
    override val meta: GameMeta get() = error("not used")
    override var status: GameStatus = GameStatus.Idle
        private set
    override var score: Int = 0
        private set
    override val wantsFrames: Boolean = true

    override fun reset(seed: Long, players: Int) {
        status = GameStatus.Running
    }

    override fun resize(width: Float, height: Float): Unit = Unit

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float): Unit = Unit

    override fun onGesture(gesture: Gesture): Unit = Unit

    override fun update(dtMs: Int): Boolean {
        score = 3
        status = GameStatus.Over(0)
        return true
    }

    override fun render(scene: Scene): Unit = Unit
}

/** Records the size it is given and where pointers and gestures land, to check the fixed play zone. */
private class RecordingGame : Game {
    override val meta: GameMeta get() = error("not used")
    override var status: GameStatus = GameStatus.Idle
        private set
    override var score: Int = 0
        private set
    override val wantsFrames: Boolean = false

    var width = -1f
    var height = -1f
    var pointerX = -1f
    var pointerY = -1f
    var gestureX = -1f
    var gestureY = -1f

    override fun reset(seed: Long, players: Int) {
        status = GameStatus.Running
    }

    override fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        pointerX = x
        pointerY = y
    }

    override fun onGesture(gesture: Gesture) {
        gestureX = gesture.x
        gestureY = gesture.y
    }

    override fun update(dtMs: Int): Boolean = false

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, width, height, lg.core.scene.Palette.SURFACE)
    }
}

public class GameSessionTest {
    private val screens = listOf(360f to 640f, 412f to 915f, 768f to 1024f, 320f to 480f)

    private fun recordingSession(): Pair<GameSession, RecordingGame> {
        val store = FakeStore()
        val game = RecordingGame()
        val meta = GameMeta(
            id = "recording",
            titleKey = StringKey.TITLE_SNAKE,
            tier = Tier.FREE,
            players = 1..1,
            orientation = Orientation.PORTRAIT,
            complexity = Complexity.S,
            resumable = false,
            factory = { game },
            icon = { _, _ -> },
        )
        return GameSession(meta, Scores(store), Settings(store), FakeClock()) to game
    }

    @Test
    public fun theGameAlwaysSeesTheSameFixedPlayZone(): Unit {
        for ((w, h) in screens) {
            val (session, game) = recordingSession()
            session.resize(w, h)
            assertEquals(360f, game.width, "width on ${w}x$h")
            assertEquals(560f, game.height, "height on ${w}x$h")
        }
    }

    @Test
    public fun pointersAndGesturesAreMappedBackToZoneCoordinates(): Unit {
        for ((w, h) in screens) {
            val (session, game) = recordingSession()
            session.resize(w, h)
            session.start(players = 1, seed = 1L)
            val sx = session.playOriginX + 100f * session.playScale
            val sy = session.playOriginY + 200f * session.playScale
            session.onPointer(0, PointerPhase.DOWN, sx, sy)
            assertEquals(100f, game.pointerX, 0.05f, "pointer x on ${w}x$h")
            assertEquals(200f, game.pointerY, 0.05f, "pointer y on ${w}x$h")
            session.onPointer(0, PointerPhase.UP, sx, sy)
            assertEquals(100f, game.gestureX, 0.05f, "tap x on ${w}x$h")
            assertEquals(200f, game.gestureY, 0.05f, "tap y on ${w}x$h")
        }
    }

    @Test
    public fun thePlayZoneFitsTheScreenAboveTheButtonBarAndIsCentered(): Unit {
        for ((w, h) in screens) {
            val (session, _) = recordingSession()
            session.resize(w, h, safeTop = 24f)
            val s = session.playScale
            assertTrue(session.playOriginX >= 0f, "left edge on ${w}x$h")
            assertTrue(session.playOriginX + 360f * s <= w, "right edge on ${w}x$h")
            assertTrue(session.playOriginY >= 24f, "below the inset on ${w}x$h")
            assertTrue(session.playOriginY + 560f * s <= h - 52f, "above the button bar on ${w}x$h")
            val left = session.playOriginX
            val right = w - (session.playOriginX + 360f * s)
            assertEquals(left, right, 0.01f, "horizontally centered on ${w}x$h")
        }
    }

    private fun newSession(store: FakeStore = FakeStore(), clock: FakeClock = FakeClock()): GameSession {
        val scores = Scores(store)
        val settings = Settings(store)
        return GameSession(SnakeGame.META, scores, settings, clock)
    }

    @Test
    public fun startsWithStartOverlay(): Unit {
        val session = newSession()
        assertIs<Overlay.Start>(session.overlay)
    }

    @Test
    public fun tapOnStartButtonBeginsPlay(): Unit {
        val session = newSession()
        session.resize(400f, 400f)
        // Single centered "Tap to start" button for a 1-player game covers the centre of the framed play area.
        session.onPointer(0, PointerPhase.DOWN, 200f, 175f)
        session.onPointer(0, PointerPhase.UP, 200f, 175f)
        assertIs<Overlay.None>(session.overlay)
    }

    @Test
    public fun tapOnQuitBarCallsOnQuitAndNeverStartsTheGame(): Unit {
        val session = newSession()
        session.resize(400f, 400f)
        var quits = 0
        session.onQuit = { quits++ }
        // The Quit button sits at the bottom of the view, below the framed play area.
        session.onPointer(0, PointerPhase.DOWN, 200f, 360f)
        session.onPointer(0, PointerPhase.UP, 200f, 360f)
        assertEquals(1, quits)
        assertIs<Overlay.Start>(session.overlay)
    }

    @Test
    public fun framedPlayAreaLeavesRoomAtTheTopForTheSafeInset(): Unit {
        val session = newSession()
        session.resize(400f, 800f, safeTop = 48f)
        val scene = Scene()
        session.render(scene)
        // First primitive is the page background; the decorated frame starts below the inset.
        var minFrameY = Float.MAX_VALUE
        for (i in 1 until scene.count) {
            if (scene.kind[i] == lg.core.scene.Prim.RECT) minFrameY = minOf(minFrameY, scene.f[i * 6 + 1])
        }
        assertTrue(minFrameY >= 48f, "frame must start below the safe inset, was $minFrameY")
    }

    @Test
    public fun pauseThenResumeReturnsToPlay(): Unit {
        val session = newSession()
        session.resize(400f, 400f)
        session.start(players = 1, seed = 1L)
        assertIs<Overlay.None>(session.overlay)

        session.pause()
        assertIs<Overlay.Paused>(session.overlay)

        session.resume()
        assertIs<Overlay.None>(session.overlay)
    }

    @Test
    public fun gameOverShowsOverlayAndPersistsBestScore(): Unit {
        val store = FakeStore()
        store.putInt("score.dying", -5)
        val meta = GameMeta(
            id = "dying",
            titleKey = StringKey.TITLE_SNAKE,
            tier = Tier.FREE,
            players = 1..1,
            orientation = Orientation.ANY,
            complexity = Complexity.S,
            resumable = false,
            factory = { DiesOnFirstUpdateGame() },
            icon = { _, _ -> },
        )
        val session = GameSession(meta, Scores(store), Settings(store), FakeClock())
        session.resize(400f, 400f)
        session.start(players = 1, seed = 1L)
        session.update(16)

        assertIs<Overlay.Over>(session.overlay)
        assertEquals(3, Scores(store).best("dying"))
    }

    @Test
    public fun soundFiresOnceThenIsSuppressedOnConsecutiveRender(): Unit {
        val store = FakeStore()
        val scores = Scores(store)
        val settings = Settings(store)
        val clock = FakeClock()
        val fakeGame = SoundOnEveryRenderGame()
        val meta = GameMeta(
            id = "fake-sound",
            titleKey = StringKey.TITLE_SNAKE,
            tier = Tier.FREE,
            players = 1..1,
            orientation = Orientation.ANY,
            complexity = Complexity.S,
            resumable = false,
            factory = { fakeGame },
            icon = { _, _ -> },
        )
        val session = GameSession(meta, scores, settings, clock)
        session.resize(400f, 400f)
        session.start(players = 1, seed = 1L)

        // Consume the arm-on-start event so it doesn't interfere with the
        // assertions below, which are about an update-driven state change.
        val warmup = Scene()
        session.render(warmup)

        fakeGame.changeOnNextUpdate = true
        session.update(16)

        val first = Scene()
        session.render(first)
        assertTrue(first.soundMask and SoundId.TICK.bit != 0, "first render after a state change must carry the sound bit")

        val second = Scene()
        session.render(second)
        assertEquals(0, second.soundMask, "consecutive render without a new state change must not replay the sound")
    }

    @Test
    public fun needsFrameLoopOnlyWhilePlaying(): Unit {
        val session = newSession()
        session.resize(400f, 400f)
        assertEquals(false, session.needsFrameLoop)
        session.start(players = 1, seed = 1L)
        assertEquals(true, session.needsFrameLoop)
        session.pause()
        assertEquals(false, session.needsFrameLoop)
    }
}
