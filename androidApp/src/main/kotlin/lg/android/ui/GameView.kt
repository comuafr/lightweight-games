package lg.android.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import lg.android.audio.NoopSoundSink
import lg.android.audio.SoundSink
import lg.core.input.PointerPhase
import lg.core.scene.Scene
import lg.core.session.GameSession

/**
 * Full-screen custom [View] hosting exactly one [GameSession]. Converts
 * touch events to dp and forwards every pointer to the session, runs a
 * [Choreographer] frame loop only while [GameSession.needsFrameLoop] is
 * true, and renders through a single preallocated [Scene] + [CanvasRenderer].
 * No allocation happens in [onTouchEvent], the frame callback or [onDraw].
 */
internal class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var session: GameSession? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
            ensureFrameLoop()
        }

    var soundSink: SoundSink = NoopSoundSink

    private val density = context.resources.displayMetrics.density
    private val scene = Scene()
    private val strings = Strings(context)

    private var frameLoopRunning = false
    private var lastFrameTimeNanos = 0L

    private val frameCallback: Choreographer.FrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        val s = session
        if (s != null && s.needsFrameLoop) {
            val dtNanos = if (lastFrameTimeNanos == 0L) 0L else frameTimeNanos - lastFrameTimeNanos
            lastFrameTimeNanos = frameTimeNanos
            val dtMs = (dtNanos / 1_000_000L).toInt().coerceIn(0, MAX_DT_MS)
            val changed = s.update(dtMs)
            if (changed) invalidate()
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            frameLoopRunning = false
            lastFrameTimeNanos = 0L
        }
    }

    /** Starts the Choreographer loop if the session currently wants frames and it isn't running yet. */
    fun ensureFrameLoop() {
        val s = session ?: return
        if (s.needsFrameLoop && !frameLoopRunning) {
            frameLoopRunning = true
            lastFrameTimeNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    /** Stops the frame loop immediately (called from `onPause`); safe to call even if not running. */
    fun stopFrameLoop() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameLoopRunning = false
        lastFrameTimeNanos = 0L
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        session?.resize(w / density, h / density)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = session ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> forward(s, event, event.actionIndex, PointerPhase.DOWN)
            MotionEvent.ACTION_POINTER_DOWN -> forward(s, event, event.actionIndex, PointerPhase.DOWN)
            MotionEvent.ACTION_MOVE -> {
                for (idx in 0 until event.pointerCount) forward(s, event, idx, PointerPhase.MOVE)
            }
            MotionEvent.ACTION_POINTER_UP -> forward(s, event, event.actionIndex, PointerPhase.UP)
            MotionEvent.ACTION_UP -> forward(s, event, event.actionIndex, PointerPhase.UP)
            MotionEvent.ACTION_CANCEL -> {
                for (idx in 0 until event.pointerCount) forward(s, event, idx, PointerPhase.CANCEL)
            }
            else -> return false
        }
        invalidate()
        ensureFrameLoop()
        return true
    }

    private fun forward(session: GameSession, event: MotionEvent, index: Int, phase: PointerPhase) {
        val id = event.getPointerId(index)
        val x = event.getX(index) / density
        val y = event.getY(index) / density
        session.onPointer(id, phase, x, y)
    }

    override fun onDraw(canvas: Canvas) {
        val s = session ?: return
        s.render(scene)
        val colors = Palette.colorsFor(context)
        canvas.save()
        canvas.scale(density, density)
        CanvasRenderer.draw(canvas, scene, colors, strings)
        canvas.restore()
        if (scene.soundMask != 0) soundSink.play(scene.soundMask)
        if (scene.haptic) {
            performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        }
    }

    private companion object {
        const val MAX_DT_MS = 50
    }
}
