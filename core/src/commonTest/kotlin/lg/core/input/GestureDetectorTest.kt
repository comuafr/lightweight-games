package lg.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

public class GestureDetectorTest {
    private class Recorder {
        public var kind: GestureKind? = null
        public var x: Float = 0f
        public var y: Float = 0f
        public var pointerId: Int = -1
        public var count: Int = 0

        public fun reset(): Unit {
            kind = null
            count = 0
        }
    }

    private fun newDetector(recorder: Recorder): GestureDetector = GestureDetector { g ->
        recorder.kind = g.kind
        recorder.x = g.x
        recorder.y = g.y
        recorder.pointerId = g.pointerId
        recorder.count++
    }

    @Test
    public fun recognizesTap(): Unit {
        val recorder = Recorder()
        val detector = newDetector(recorder)
        detector.onPointer(0, PointerPhase.DOWN, 100f, 100f, 0L)
        detector.onPointer(0, PointerPhase.UP, 102f, 101f, 100L)
        assertEquals(GestureKind.TAP, recorder.kind)
        assertEquals(1, recorder.count)
    }

    @Test
    public fun tapTooSlowIsNotRecognizedAsTap(): Unit {
        val recorder = Recorder()
        val detector = newDetector(recorder)
        detector.onPointer(0, PointerPhase.DOWN, 100f, 100f, 0L)
        detector.onPointer(0, PointerPhase.UP, 100f, 100f, 300L)
        // Not enough movement for a swipe either: no gesture emitted.
        assertNull(recorder.kind)
    }

    @Test
    public fun recognizesSwipeUp(): Unit {
        val recorder = Recorder()
        val detector = newDetector(recorder)
        detector.onPointer(0, PointerPhase.DOWN, 100f, 100f, 0L)
        detector.onPointer(0, PointerPhase.MOVE, 100f, 50f, 50L)
        detector.onPointer(0, PointerPhase.UP, 100f, 40f, 80L)
        assertEquals(GestureKind.SWIPE_UP, recorder.kind)
    }

    @Test
    public fun recognizesSwipeDown(): Unit {
        val recorder = Recorder()
        val detector = newDetector(recorder)
        detector.onPointer(0, PointerPhase.DOWN, 100f, 100f, 0L)
        detector.onPointer(0, PointerPhase.UP, 100f, 160f, 80L)
        assertEquals(GestureKind.SWIPE_DOWN, recorder.kind)
    }

    @Test
    public fun recognizesSwipeLeft(): Unit {
        val recorder = Recorder()
        val detector = newDetector(recorder)
        detector.onPointer(0, PointerPhase.DOWN, 100f, 100f, 0L)
        detector.onPointer(0, PointerPhase.UP, 40f, 100f, 80L)
        assertEquals(GestureKind.SWIPE_LEFT, recorder.kind)
    }

    @Test
    public fun recognizesSwipeRight(): Unit {
        val recorder = Recorder()
        val detector = newDetector(recorder)
        detector.onPointer(0, PointerPhase.DOWN, 100f, 100f, 0L)
        detector.onPointer(0, PointerPhase.UP, 160f, 100f, 80L)
        assertEquals(GestureKind.SWIPE_RIGHT, recorder.kind)
    }

    @Test
    public fun recognizesLongPressViaUpdate(): Unit {
        val recorder = Recorder()
        val detector = newDetector(recorder)
        detector.onPointer(0, PointerPhase.DOWN, 100f, 100f, 0L)
        detector.update(200L)
        assertNull(recorder.kind)
        detector.update(500L)
        assertEquals(GestureKind.LONG_PRESS, recorder.kind)
    }

    @Test
    public fun longPressIsNotFollowedByTapOnUp(): Unit {
        val recorder = Recorder()
        val detector = newDetector(recorder)
        detector.onPointer(0, PointerPhase.DOWN, 100f, 100f, 0L)
        detector.update(500L)
        assertEquals(GestureKind.LONG_PRESS, recorder.kind)
        recorder.reset()
        detector.onPointer(0, PointerPhase.UP, 100f, 100f, 520L)
        assertNull(recorder.kind)
        assertEquals(0, recorder.count)
    }

    @Test
    public fun cancelSuppressesGesture(): Unit {
        val recorder = Recorder()
        val detector = newDetector(recorder)
        detector.onPointer(0, PointerPhase.DOWN, 100f, 100f, 0L)
        detector.onPointer(0, PointerPhase.CANCEL, 100f, 100f, 50L)
        assertNull(recorder.kind)
        // After cancel, a further update() must not emit a stale long-press.
        detector.update(600L)
        assertNull(recorder.kind)
    }

    @Test
    public fun twoSimultaneousPointersAreTrackedIndependently(): Unit {
        val recorder = Recorder()
        val detector = newDetector(recorder)
        detector.onPointer(1, PointerPhase.DOWN, 10f, 10f, 0L)
        detector.onPointer(2, PointerPhase.DOWN, 200f, 200f, 0L)

        detector.onPointer(1, PointerPhase.UP, 12f, 11f, 50L)
        assertEquals(GestureKind.TAP, recorder.kind)
        assertEquals(1, recorder.pointerId)

        detector.onPointer(2, PointerPhase.UP, 260f, 200f, 60L)
        assertEquals(GestureKind.SWIPE_RIGHT, recorder.kind)
        assertEquals(2, recorder.pointerId)
    }
}
