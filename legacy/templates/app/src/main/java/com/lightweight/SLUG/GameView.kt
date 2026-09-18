package com.lightweight.{{SLUG_PKG}}

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * TEMPLATE — rendering, game loop and input.
 * Reuse the single Paint; never allocate inside step()/draw().
 */
class GameView(ctx: Context) : SurfaceView(ctx), SurfaceHolder.Callback, Runnable {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    private var game = Game()
    private val sound = Sound(ctx)
    private val prefs = ctx.getSharedPreferences("{{SLUG_PKG}}", Context.MODE_PRIVATE)
    private var highScore = prefs.getLong("hi", 0L)
    private var lastFrame = System.nanoTime()

    init { holder.addCallback(this) }

    override fun surfaceCreated(h: SurfaceHolder) {
        running = true
        lastFrame = System.nanoTime()
        thread = Thread(this).also { it.start() }
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}

    override fun surfaceDestroyed(h: SurfaceHolder) {
        running = false
        thread?.join()
        thread = null
        sound.release()
    }

    /** Called from the Activity lifecycle. */
    fun onPause() { paused = true }
    fun onResume() { paused = false; lastFrame = System.nanoTime() }

    override fun run() {
        while (running) {
            if (paused) { Thread.sleep(50); continue }
            val now = System.nanoTime()
            val dtMs = (now - lastFrame) / 1_000_000L
            lastFrame = now
            step(dtMs)
            val c = holder.lockCanvas() ?: continue
            try { draw(c) } finally { holder.unlockCanvasAndPost(c) }
            Thread.sleep(16)
        }
    }

    private fun step(dtMs: Long) {
        // TODO: advance game; on game over, persist high score once via prefs.
        game.tick()
    }

    private fun draw(c: Canvas) {
        c.drawColor(0xFF101418.toInt())
        // TODO: render the game state using the reused `paint`.
        // Avoid the "unused" warnings being treated as errors by touching fields:
        if (highScore < 0) paint.color = 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // TODO: map gestures to game actions.
        return true
    }
}
