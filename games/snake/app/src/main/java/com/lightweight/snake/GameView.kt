package com.lightweight.snake

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.min

/**
 * Rendering, fixed-step game loop and touch input for Snake.
 *
 * All game state lives in [Game] (pure logic) and is only ever mutated on the
 * render thread; touch handlers (UI thread) merely queue a direction or a
 * restart, which the loop consumes. The single [paint] and [rect] are reused —
 * no allocation in step()/draw().
 */
class GameView(ctx: Context) : SurfaceView(ctx), SurfaceHolder.Callback, Runnable {

    private companion object {
        const val STEP_MS = 130L          // snake advances this often
        const val FRAME_MS = 16L
        const val SWIPE_MIN = 24f         // px before a drag counts as a swipe
        // Colours are plain `val` (not const): `.toInt()` is a call, which
        // const-val initializers don't allow.
        val BG = 0xFF101418.toInt()
        val BOARD = 0xFF181E24.toInt()
        val BODY = 0xFF5FD068.toInt()
        val HEAD = 0xFF9BE7A6.toInt()
        val FOOD = 0xFFE85D5D.toInt()
        val TEXT = 0xFFE8EEF2.toInt()
        val DIM = 0xC0000000.toInt()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false

    private val game = Game()
    private val sound = Sound(ctx)
    private val prefs = ctx.getSharedPreferences("snake", Context.MODE_PRIVATE)
    private var highScore = prefs.getLong("hi", 0L)
    private var savedThisGame = false

    // Layout, computed in surfaceChanged.
    private var cellPx = 0f
    private var boardLeft = 0f
    private var boardTop = 0f
    private var topBar = 0f

    // Cached HUD strings (rebuilt only when values change).
    private var scoreText = "0"
    private var hiText = "BEST 0"

    // Queued input from the UI thread.
    @Volatile private var queuedDir: Game.Dir? = null
    @Volatile private var restartRequested = false
    private var downX = 0f
    private var downY = 0f

    private var lastFrame = System.nanoTime()
    private var acc = 0L

    init {
        holder.addCallback(this)
        hiText = "BEST $highScore"
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        running = true
        lastFrame = System.nanoTime()
        thread = Thread(this).also { it.start() }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, ht: Int) {
        topBar = ht * 0.09f
        cellPx = min(w / game.cols.toFloat(), (ht - topBar) / game.rows.toFloat())
        val boardW = cellPx * game.cols
        val boardH = cellPx * game.rows
        boardLeft = (w - boardW) / 2f
        boardTop = topBar + (ht - topBar - boardH) / 2f
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        running = false
        thread?.join()
        thread = null
        sound.release()
    }

    fun onPause() { paused = true }
    fun onResume() { paused = false; lastFrame = System.nanoTime() }

    override fun run() {
        while (running) {
            if (paused) { Thread.sleep(FRAME_MS); continue }
            val now = System.nanoTime()
            val dtMs = (now - lastFrame) / 1_000_000L
            lastFrame = now
            step(dtMs)
            val c = holder.lockCanvas() ?: continue
            try { draw(c) } finally { holder.unlockCanvasAndPost(c) }
            Thread.sleep(FRAME_MS)
        }
    }

    private fun step(dtMs: Long) {
        if (restartRequested) {
            restartRequested = false
            game.reset()
            savedThisGame = false
            acc = 0
            scoreText = "0"
            return
        }
        queuedDir?.let { game.turn(it); queuedDir = null }
        if (game.over) return

        acc += dtMs
        while (acc >= STEP_MS && !game.over) {
            acc -= STEP_MS
            val ate = game.tick()
            if (ate) { sound.eat(); scoreText = game.score.toString() }
            if (game.over) {
                sound.over()
                if (!savedThisGame) {
                    savedThisGame = true
                    if (game.score > highScore) {
                        highScore = game.score
                        prefs.edit().putLong("hi", highScore).apply()
                        hiText = "BEST $highScore"
                    }
                }
            }
        }
    }

    private fun draw(c: Canvas) {
        c.drawColor(BG)
        if (cellPx <= 0f) return

        val boardW = cellPx * game.cols
        val boardH = cellPx * game.rows
        paint.color = BOARD
        rect.set(boardLeft, boardTop, boardLeft + boardW, boardTop + boardH)
        c.drawRect(rect, paint)

        val r = cellPx * 0.22f
        paint.color = FOOD
        cellRect(game.foodX, game.foodY)
        c.drawRoundRect(rect, r, r, paint)

        for (i in 0 until game.length) {
            paint.color = if (i == game.length - 1) HEAD else BODY
            cellRect(game.segmentX(i), game.segmentY(i))
            c.drawRoundRect(rect, r, r, paint)
        }

        // HUD
        paint.color = TEXT
        paint.textSize = topBar * 0.5f
        paint.textAlign = Paint.Align.LEFT
        c.drawText(scoreText, boardLeft, topBar * 0.62f, paint)
        paint.textAlign = Paint.Align.RIGHT
        c.drawText(hiText, boardLeft + boardW, topBar * 0.62f, paint)

        if (game.over) {
            paint.color = DIM
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = TEXT
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = cellPx * 1.1f
            val cx = width / 2f
            c.drawText(if (game.won) "You win!" else "Game over", cx, height / 2f, paint)
            paint.textSize = cellPx * 0.7f
            c.drawText("Score ${game.score}", cx, height / 2f + cellPx * 1.6f, paint)
            c.drawText("Tap to play again", cx, height / 2f + cellPx * 3.0f, paint)
        }
    }

    private fun cellRect(cx: Int, cy: Int) {
        val gap = cellPx * 0.08f
        val l = boardLeft + cx * cellPx
        val t = boardTop + cy * cellPx
        rect.set(l + gap, t + gap, l + cellPx - gap, t + cellPx - gap)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (game.over) { restartRequested = true; return true }
                downX = e.x; downY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (game.over) return true
                val dx = e.x - downX
                val dy = e.y - downY
                if (abs(dx) < SWIPE_MIN && abs(dy) < SWIPE_MIN) return true
                queuedDir = if (abs(dx) > abs(dy)) {
                    if (dx > 0) Game.Dir.RIGHT else Game.Dir.LEFT
                } else {
                    if (dy > 0) Game.Dir.DOWN else Game.Dir.UP
                }
            }
        }
        return true
    }
}
