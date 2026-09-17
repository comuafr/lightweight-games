package lg.core.games.breakout

import lg.core.Complexity
import lg.core.Game
import lg.core.GameMeta
import lg.core.GameStatus
import lg.core.Orientation
import lg.core.Tier
import lg.core.input.Gesture
import lg.core.input.PointerPhase
import lg.core.scene.Palette
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey
import kotlin.math.sqrt

/**
 * Classic paddle-and-ball brick breaker.
 *
 * ### Data layout
 *
 * [hitPoints] is a fixed `BRICK_COLS * BRICK_ROWS` array of remaining hit
 * points per brick (0 = destroyed); every brick starts with 1 hit point.
 * Ball, paddle and per-tick event flags are plain preallocated `Float`/`Int`/
 * `Boolean` fields — nothing is allocated by [onPointer], [update] or
 * [render].
 *
 * ### Physics
 *
 * [update] accumulates `dtMs` (already clamped to 50 ms by
 * [lg.core.session.GameSession]) and advances the simulation in fixed
 * [PHYSICS_STEP_MS] substeps (capped at [MAX_STEPS_PER_UPDATE] per call, so
 * a stall never causes a catch-up spiral). Each substep moves the ball,
 * resolves wall/paddle/brick collisions as AABB tests, and checks whether
 * the ball fell past the paddle. Brick collisions pick the reflection axis
 * from whichever overlap (`x` or `y`) is smaller ("penetration depth").
 * [wantsFrames] is true only while [status] is Running **and** the ball is
 * actually in flight — while it rests on the paddle waiting for the launch
 * tap, no frame loop is needed.
 *
 * ### Paddle input
 *
 * The paddle is driven directly by raw pointer events, not gestures: any
 * `DOWN`/`MOVE` sets the paddle center to the pointer's `x`, clamped to stay
 * on screen. The first `DOWN` received while the ball is not in flight (a
 * fresh [reset] or right after losing a life) also launches it.
 */
public class BreakoutGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    override var score: Int = 0
        private set

    override val wantsFrames: Boolean
        get() = status == GameStatus.Running && ballInFlight

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f

    private var level: Int = 1
    private var lives: Int = 3

    private var paddleCenterX: Float = 0f

    private var ballInFlight: Boolean = false
    private var ballX: Float = 0f
    private var ballY: Float = 0f
    private var ballVX: Float = 0f
    private var ballVY: Float = 0f
    private var physicsAccumulatorMs: Int = 0

    private val hitPoints = IntArray(BRICK_COLS * BRICK_ROWS)
    private var bricksRemaining: Int = 0

    // Events from the most recently processed update() call, read (not mutated) by render().
    private var wallHitThisUpdate: Boolean = false
    private var paddleHitThisUpdate: Boolean = false
    private var brickHitThisUpdate: Boolean = false
    private var lifeLostThisUpdate: Boolean = false
    private var levelClearedThisUpdate: Boolean = false

    override fun reset(seed: Long, players: Int) {
        level = 1
        lives = 3
        score = 0
        resetBricks()
        paddleCenterX = 0f
        ballInFlight = false
        ballX = 0f
        ballY = 0f
        ballVX = 0f
        ballVY = 0f
        physicsAccumulatorMs = 0
        resetEventFlags()
        status = GameStatus.Running
        // Real paddle/ball placement happens in resize(), which is always
        // called before the first render (viewWidth/Height are still 0 here).
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        if (viewWidth <= 0f || viewHeight <= 0f) return
        if (!ballInFlight) {
            paddleCenterX = viewWidth / 2f
            ballX = paddleCenterX
            ballY = paddleTop() - BALL_RADIUS_PT
        } else {
            paddleCenterX = paddleCenterX.coerceIn(paddleHalfWidth(), viewWidth - paddleHalfWidth())
        }
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        if (status != GameStatus.Running || viewWidth <= 0f) return
        if (phase != PointerPhase.DOWN && phase != PointerPhase.MOVE) return
        paddleCenterX = x.coerceIn(paddleHalfWidth(), viewWidth - paddleHalfWidth())
        if (!ballInFlight) {
            ballX = paddleCenterX
            ballY = paddleTop() - BALL_RADIUS_PT
            if (phase == PointerPhase.DOWN) launchBall()
        }
    }

    override fun onGesture(gesture: Gesture) {
        // Breakout is drag-driven only (see onPointer); gestures are ignored.
    }

    override fun update(dtMs: Int): Boolean {
        if (status != GameStatus.Running) return false
        resetEventFlags()
        if (!ballInFlight) return false

        physicsAccumulatorMs += dtMs
        var steps = 0
        var changed = false
        while (physicsAccumulatorMs >= PHYSICS_STEP_MS && steps < MAX_STEPS_PER_UPDATE) {
            physicsAccumulatorMs -= PHYSICS_STEP_MS
            stepPhysics()
            steps++
            changed = true
            if (!ballInFlight || status != GameStatus.Running) break
        }
        return changed
    }

    private fun resetEventFlags() {
        wallHitThisUpdate = false
        paddleHitThisUpdate = false
        brickHitThisUpdate = false
        lifeLostThisUpdate = false
        levelClearedThisUpdate = false
    }

    // --- Physics ------------------------------------------------------------

    private fun stepPhysics() {
        val dtSec = PHYSICS_STEP_MS / 1000f
        ballX += ballVX * dtSec
        ballY += ballVY * dtSec

        if (ballX - BALL_RADIUS_PT < 0f) {
            ballX = BALL_RADIUS_PT
            ballVX = -ballVX
            wallHitThisUpdate = true
        } else if (ballX + BALL_RADIUS_PT > viewWidth) {
            ballX = viewWidth - BALL_RADIUS_PT
            ballVX = -ballVX
            wallHitThisUpdate = true
        }
        if (ballY - BALL_RADIUS_PT < HUD_TOP_PT) {
            ballY = HUD_TOP_PT + BALL_RADIUS_PT
            ballVY = -ballVY
            wallHitThisUpdate = true
        }

        if (ballVY > 0f && intersectsPaddle()) {
            ballY = paddleTop() - BALL_RADIUS_PT
            val offset = (ballX - paddleCenterX) / paddleHalfWidth()
            setVelocityFromOffset(offset)
            paddleHitThisUpdate = true
        }

        handleBrickCollisions()

        if (ballY - BALL_RADIUS_PT > viewHeight) {
            loseLife()
        }
    }

    private fun intersectsPaddle(): Boolean {
        val pTop = paddleTop()
        val pBottom = pTop + PADDLE_HEIGHT_PT
        val pLeft = paddleCenterX - paddleHalfWidth()
        val pRight = paddleCenterX + paddleHalfWidth()
        return ballY + BALL_RADIUS_PT >= pTop && ballY - BALL_RADIUS_PT <= pBottom &&
            ballX + BALL_RADIUS_PT >= pLeft && ballX - BALL_RADIUS_PT <= pRight
    }

    /** Resolves at most one brick collision per substep, reflecting on the smaller-penetration axis. */
    private fun handleBrickCollisions() {
        val left = ballX - BALL_RADIUS_PT
        val right = ballX + BALL_RADIUS_PT
        val top = ballY - BALL_RADIUS_PT
        val bottom = ballY + BALL_RADIUS_PT
        val bw = brickWidth()
        for (row in 0 until BRICK_ROWS) {
            val by = brickY(row)
            if (bottom <= by || top >= by + BRICK_ROW_HEIGHT_PT) continue
            for (col in 0 until BRICK_COLS) {
                val idx = row * BRICK_COLS + col
                if (hitPoints[idx] <= 0) continue
                val bx = brickX(col)
                if (right <= bx || left >= bx + bw) continue

                val overlapX = minOf(right, bx + bw) - maxOf(left, bx)
                val overlapY = minOf(bottom, by + BRICK_ROW_HEIGHT_PT) - maxOf(top, by)
                if (overlapX < overlapY) ballVX = -ballVX else ballVY = -ballVY
                destroyBrick(idx)
                return
            }
        }
    }

    private fun destroyBrick(idx: Int) {
        hitPoints[idx] = 0
        bricksRemaining--
        score += POINTS_PER_BRICK * level
        brickHitThisUpdate = true
        if (bricksRemaining <= 0) nextLevel()
    }

    private fun loseLife() {
        lives--
        lifeLostThisUpdate = true
        if (lives <= 0) {
            status = GameStatus.Over(0)
        }
        ballInFlight = false
        ballVX = 0f
        ballVY = 0f
        ballX = paddleCenterX
        ballY = paddleTop() - BALL_RADIUS_PT
    }

    private fun nextLevel() {
        level++
        resetBricks()
        score += LEVEL_CLEAR_BONUS * level
        ballInFlight = false
        ballVX = 0f
        ballVY = 0f
        ballX = paddleCenterX
        ballY = paddleTop() - BALL_RADIUS_PT
        levelClearedThisUpdate = true
    }

    private fun resetBricks() {
        hitPoints.fill(1)
        bricksRemaining = BRICK_COLS * BRICK_ROWS
    }

    private fun launchBall() {
        ballInFlight = true
        physicsAccumulatorMs = 0
        setVelocityFromOffset(0f)
    }

    /** Sets ball velocity to the current level's fixed speed, angled by [offset] in `[-1, 1]` (paddle-relative x). */
    private fun setVelocityFromOffset(offset: Float) {
        val speed = speedForLevel()
        val vx = offset.coerceIn(-1f, 1f) * speed * MAX_BOUNCE_FRACTION
        ballVX = vx
        ballVY = -sqrt(speed * speed - vx * vx)
    }

    private fun speedForLevel(): Float = BASE_SPEED_PT_PER_S + (level - 1) * SPEED_INCREMENT_PER_LEVEL

    // --- Layout helpers (pure functions of current viewWidth/viewHeight/level) --------------------

    private fun paddleWidth(): Float = viewWidth * PADDLE_WIDTH_FRACTION
    private fun paddleHalfWidth(): Float = paddleWidth() / 2f
    private fun paddleTop(): Float = viewHeight - PADDLE_BOTTOM_MARGIN_PT - PADDLE_HEIGHT_PT
    private fun brickWidth(): Float = (viewWidth - (BRICK_COLS + 1) * BRICK_GAP_PT) / BRICK_COLS
    private fun brickX(col: Int): Float = BRICK_GAP_PT + col * (brickWidth() + BRICK_GAP_PT)
    private fun brickY(row: Int): Float = HUD_TOP_PT + BRICK_TOP_MARGIN_PT + row * (BRICK_ROW_HEIGHT_PT + BRICK_GAP_PT)

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)

        val bw = brickWidth()
        for (row in 0 until BRICK_ROWS) {
            val by = brickY(row)
            val color = Palette.TILE_0 + row
            for (col in 0 until BRICK_COLS) {
                if (hitPoints[row * BRICK_COLS + col] <= 0) continue
                scene.rect(brickX(col), by, bw, BRICK_ROW_HEIGHT_PT, color, radius = BRICK_RADIUS_PT)
            }
        }

        scene.rect(
            paddleCenterX - paddleHalfWidth(),
            paddleTop(),
            paddleWidth(),
            PADDLE_HEIGHT_PT,
            Palette.ACCENT,
            radius = PADDLE_RADIUS_PT,
        )

        scene.circle(ballX, ballY, BALL_RADIUS_PT, Palette.FG)

        val dotY = HUD_TOP_PT + LIFE_DOT_RADIUS_PT + LIFE_DOT_TOP_MARGIN_PT
        for (i in 0 until lives) {
            val dotX = viewWidth - LIFE_DOT_RIGHT_MARGIN_PT - LIFE_DOT_RADIUS_PT - i * (LIFE_DOT_RADIUS_PT * 2f + LIFE_DOT_GAP_PT)
            scene.circle(dotX, dotY, LIFE_DOT_RADIUS_PT, Palette.FG)
        }

        if (wallHitThisUpdate || paddleHitThisUpdate) scene.sound(SoundId.HIT)
        if (brickHitThisUpdate) scene.sound(SoundId.EAT)
        if (levelClearedThisUpdate) scene.sound(SoundId.WIN)
        if (lifeLostThisUpdate) {
            scene.sound(SoundId.LOSE)
            scene.haptic()
        }
    }

    // --- Test-only accessors (internal: exercised by BreakoutRulesTest) ----

    internal fun debugBallInFlight(): Boolean = ballInFlight
    internal fun debugBallX(): Float = ballX
    internal fun debugBallY(): Float = ballY
    internal fun debugBallVX(): Float = ballVX
    internal fun debugBallVY(): Float = ballVY
    internal fun debugPaddleX(): Float = paddleCenterX
    internal fun debugLives(): Int = lives
    internal fun debugLevel(): Int = level
    internal fun debugBricksRemaining(): Int = bricksRemaining
    internal fun debugHitPoints(row: Int, col: Int): Int = hitPoints[row * BRICK_COLS + col]
    internal fun debugPaddleTop(): Float = paddleTop()
    internal fun debugBrickLeft(col: Int): Float = brickX(col)
    internal fun debugBrickTop(row: Int): Float = brickY(row)
    internal fun debugBrickWidth(): Float = brickWidth()
    internal fun debugBrickHeight(): Float = BRICK_ROW_HEIGHT_PT

    internal fun debugSetBall(x: Float, y: Float, vx: Float, vy: Float, inFlight: Boolean = true) {
        ballX = x
        ballY = y
        ballVX = vx
        ballVY = vy
        ballInFlight = inFlight
        physicsAccumulatorMs = 0
    }

    /** Destroys the brick at ([row], [col]) directly, exercising the same scoring/level-clear path as physics collisions. */
    internal fun debugDestroyBrick(row: Int, col: Int) {
        val idx = row * BRICK_COLS + col
        if (hitPoints[idx] > 0) destroyBrick(idx)
    }

    internal fun debugSetLives(n: Int) {
        lives = n
    }

    public companion object {
        public const val BRICK_COLS: Int = 8
        public const val BRICK_ROWS: Int = 6

        private const val PHYSICS_STEP_MS: Int = 4
        private const val MAX_STEPS_PER_UPDATE: Int = 32

        private const val BASE_SPEED_PT_PER_S: Float = 220f
        private const val SPEED_INCREMENT_PER_LEVEL: Float = 18f
        private const val MAX_BOUNCE_FRACTION: Float = 0.9f

        private const val POINTS_PER_BRICK: Int = 10
        private const val LEVEL_CLEAR_BONUS: Int = 50

        private const val BALL_RADIUS_PT: Float = 6f
        private const val PADDLE_WIDTH_FRACTION: Float = 0.22f
        private const val PADDLE_HEIGHT_PT: Float = 10f
        private const val PADDLE_BOTTOM_MARGIN_PT: Float = 24f
        private const val PADDLE_RADIUS_PT: Float = 5f

        private const val BRICK_GAP_PT: Float = 3f
        private const val BRICK_TOP_MARGIN_PT: Float = 8f
        private const val BRICK_ROW_HEIGHT_PT: Float = 18f
        private const val BRICK_RADIUS_PT: Float = 3f

        private const val LIFE_DOT_RADIUS_PT: Float = 4f
        private const val LIFE_DOT_GAP_PT: Float = 6f
        private const val LIFE_DOT_TOP_MARGIN_PT: Float = 4f
        private const val LIFE_DOT_RIGHT_MARGIN_PT: Float = 8f

        /** Vertical strip reserved for the session HUD (score / best). */
        private const val HUD_TOP_PT: Float = 32f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "breakout",
            titleKey = StringKey.TITLE_BREAKOUT,
            tier = Tier.FREE,
            players = 1..1,
            orientation = Orientation.PORTRAIT,
            complexity = Complexity.M,
            resumable = false,
            factory = { BreakoutGame() },
            icon = { scene, size -> drawIcon(scene, size) },
        )

        private fun drawIcon(scene: Scene, size: Float) {
            val rowH = size * 0.14f
            scene.rect(size * 0.08f, size * 0.12f, size * 0.35f, rowH, Palette.TILE_1, radius = rowH * 0.3f)
            scene.rect(size * 0.48f, size * 0.12f, size * 0.35f, rowH, Palette.TILE_2, radius = rowH * 0.3f)
            scene.rect(size * 0.08f, size * 0.30f, size * 0.35f, rowH, Palette.TILE_3, radius = rowH * 0.3f)
            scene.rect(size * 0.48f, size * 0.30f, size * 0.35f, rowH, Palette.TILE_4, radius = rowH * 0.3f)
            scene.circle(size * 0.5f, size * 0.62f, size * 0.05f, Palette.FG)
            scene.rect(size * 0.35f, size * 0.82f, size * 0.3f, size * 0.06f, Palette.ACCENT, radius = size * 0.02f)
        }
    }
}
