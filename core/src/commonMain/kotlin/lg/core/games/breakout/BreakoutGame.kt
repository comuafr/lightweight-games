package lg.core.games.breakout

import lg.core.Complexity
import lg.core.Game
import lg.core.GameMeta
import lg.core.GameStatus
import lg.core.Orientation
import lg.core.Tier
import lg.core.input.Gesture
import lg.core.input.GestureKind
import lg.core.input.PointerPhase
import lg.core.scene.Align
import lg.core.scene.ArcadeIcons
import lg.core.scene.Palette
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey
import lg.core.util.Rng
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Classic paddle-and-ball brick breaker.
 *
 * ### Data layout
 *
 * [hitPoints] is a fixed `BRICK_COLS * BRICK_ROWS` array of remaining hit
 * points per brick: 0 = no brick (never there, or destroyed), 1 to 3 = hits
 * still needed, [STEEL] = indestructible. [LEVELS] holds ten hand-made layouts
 * (shapes, tougher bricks, steel blocks); after the tenth they repeat, ever faster.
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
 * The paddle is driven directly by raw pointer events, not gestures: a
 * finger drag moves the paddle by the finger's horizontal displacement (never
 * jumping to the touch point), clamped to stay on screen. The first `DOWN` received while the ball is not in flight (a
 * fresh [reset] or right after losing a life) also launches it.
 *
 * ### Capsules (bonus and malus)
 *
 * Destroyed bricks sometimes release a falling capsule (about one in five, and
 * at least one every [PITY_BRICKS] bricks). Catching it with the paddle applies
 * its effect: more balls, a bigger ball, a slower ball, an extra life or a
 * bigger paddle (bonus), or a faster ball or a smaller paddle (malus). Timed
 * effects live in [effectLeftMs]; a "bigger" and its "smaller" cancel each other.
 * Balls, capsules and timers are fixed-size arrays advanced in the fixed physics
 * substeps, so the game stays deterministic and allocation-free. Losing the last
 * ball or clearing the level drops every effect and capsule.
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

    /** Furthest level ever reached; survives restarts and app launches via [progress]. */
    private var furthestLevel: Int = 1

    /** True while the level-choice screen is shown: always at the start of a game, all ten levels visible. */
    private var choosing: Boolean = false

    override val progress: Int get() = furthestLevel

    override fun restoreProgress(value: Int) {
        furthestLevel = maxOf(furthestLevel, value)
    }

    private var paddleCenterX: Float = 0f

    private var ballInFlight: Boolean = false

    // Up to MAX_BALLS balls (index 0 is the one that rests on the paddle).
    private val ballX = FloatArray(MAX_BALLS)
    private val ballY = FloatArray(MAX_BALLS)
    private val ballVX = FloatArray(MAX_BALLS)
    private val ballVY = FloatArray(MAX_BALLS)
    private val ballActive = BooleanArray(MAX_BALLS)
    private var physicsAccumulatorMs: Int = 0

    // Falling capsules.
    private val capX = FloatArray(MAX_CAPSULES)
    private val capY = FloatArray(MAX_CAPSULES)
    private val capType = IntArray(MAX_CAPSULES)
    private val capActive = BooleanArray(MAX_CAPSULES)
    private var bricksSinceDrop: Int = 0

    /** Remaining milliseconds of each timed effect, indexed by capsule type (0 = not active). */
    private val effectLeftMs = IntArray(TYPE_COUNT)

    /** Speed factor the balls' velocities currently include (1, [FAST_FACTOR] or [SLOW_FACTOR]). */
    private var appliedSpeedFactor: Float = 1f

    private var rng: Rng = Rng(0L)

    private val hitPoints = IntArray(BRICK_COLS * BRICK_ROWS)
    private var bricksRemaining: Int = 0

    // Events from the most recently processed update() call, read (not mutated) by render().
    private var wallHitThisUpdate: Boolean = false
    private var paddleHitThisUpdate: Boolean = false
    private var brickHitThisUpdate: Boolean = false
    private var lifeLostThisUpdate: Boolean = false
    private var levelClearedThisUpdate: Boolean = false
    private var bonusCaughtThisUpdate: Boolean = false
    private var malusCaughtThisUpdate: Boolean = false

    override fun reset(seed: Long, players: Int) {
        rng = Rng(seed)
        choosing = true
        startLevel(1)
        status = GameStatus.Running
        // The play zone has a fixed size known before the first game starts: put the paddle in
        // the middle and the ball on it right away (resize() does the same if the size changes).
        if (viewWidth > 0f && viewHeight > 0f) placeAtRest()
    }

    /** Begins a fresh game (3 lives, score 0) at [startLevel]. */
    private fun startLevel(startLevel: Int) {
        level = startLevel
        lives = 3
        score = 0
        loadLevelBricks()
        clearTransients()
        paddleCenterX = 0f
        ballInFlight = false
        physicsAccumulatorMs = 0
        resetEventFlags()
        if (viewWidth > 0f && viewHeight > 0f) placeAtRest()
    }

    /** Paddle in the middle of the zone, ball resting on it. */
    private fun placeAtRest() {
        paddleCenterX = viewWidth / 2f
        restBallOnPaddle()
    }

    private fun restBallOnPaddle() {
        for (i in 0 until MAX_BALLS) ballActive[i] = false
        ballActive[0] = true
        ballX[0] = paddleCenterX
        ballY[0] = paddleTop() - ballRadius()
        ballVX[0] = 0f
        ballVY[0] = 0f
    }

    /** Drops every effect, capsule and extra ball (new life, new level, new game). */
    private fun clearTransients() {
        effectLeftMs.fill(0)
        capActive.fill(false)
        appliedSpeedFactor = 1f
        bricksSinceDrop = 0
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        if (viewWidth <= 0f || viewHeight <= 0f) return
        if (!ballInFlight) {
            placeAtRest()
        } else {
            clampPaddle()
        }
    }

    private fun clampPaddle() {
        paddleCenterX = paddleCenterX.coerceIn(paddleHalfWidth(), viewWidth - paddleHalfWidth())
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        if (status != GameStatus.Running || viewWidth <= 0f || choosing) return
        if (phase == PointerPhase.UP || phase == PointerPhase.CANCEL) {
            if (id == dragPointerId) dragPointerId = NO_POINTER
            return
        }
        // Relative drag: the paddle moves by how far the finger moves, so a tap
        // elsewhere never teleports it. Only the first finger down steers.
        if (phase == PointerPhase.DOWN) {
            if (dragPointerId != NO_POINTER) return
            dragPointerId = id
            lastPointerX = x
        } else {
            if (id != dragPointerId) return
            paddleCenterX += x - lastPointerX
            clampPaddle()
            lastPointerX = x
        }
        if (!ballInFlight) {
            ballX[0] = paddleCenterX
            ballY[0] = paddleTop() - ballRadius()
            if (phase == PointerPhase.DOWN) launchBall()
        }
    }

    private var dragPointerId: Int = NO_POINTER
    private var lastPointerX: Float = 0f

    override fun onGesture(gesture: Gesture) {
        // Breakout is drag-driven (see onPointer); the only gestures are taps on the level-choice screen.
        if (!choosing || gesture.kind != GestureKind.TAP) return
        val n = levelAt(gesture.x, gesture.y)
        if (n in 1..minOf(furthestLevel, LEVELS.size)) {
            choosing = false
            startLevel(n)
        }
    }

    /** The level number whose tile contains ([x], [y]) on the choice screen, or 0. */
    private fun levelAt(x: Float, y: Float): Int {
        val col = ((x - CHOICE_X_PT) / (CHOICE_TILE_PT + CHOICE_GAP_PT)).toInt()
        val row = ((y - CHOICE_Y_PT) / (CHOICE_TILE_PT + CHOICE_GAP_PT)).toInt()
        if (x < CHOICE_X_PT || y < CHOICE_Y_PT || col !in 0 until CHOICE_COLS || row !in 0 until CHOICE_ROWS) return 0
        val localX = x - CHOICE_X_PT - col * (CHOICE_TILE_PT + CHOICE_GAP_PT)
        val localY = y - CHOICE_Y_PT - row * (CHOICE_TILE_PT + CHOICE_GAP_PT)
        if (localX > CHOICE_TILE_PT || localY > CHOICE_TILE_PT) return 0
        return row * CHOICE_COLS + col + 1
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
        bonusCaughtThisUpdate = false
        malusCaughtThisUpdate = false
    }

    // --- Physics ------------------------------------------------------------

    private fun stepPhysics() {
        val dtSec = PHYSICS_STEP_MS / 1000f
        moveCapsules(dtSec)
        tickEffects(PHYSICS_STEP_MS)
        for (i in 0 until MAX_BALLS) {
            if (ballActive[i]) stepBall(i, dtSec)
        }
        if (ballInFlight && status == GameStatus.Running && activeBalls() == 0) loseLife()
    }

    private fun activeBalls(): Int {
        var n = 0
        for (i in 0 until MAX_BALLS) if (ballActive[i]) n++
        return n
    }

    private fun stepBall(i: Int, dtSec: Float) {
        val r = ballRadius()
        ballX[i] += ballVX[i] * dtSec
        ballY[i] += ballVY[i] * dtSec

        if (ballX[i] - r < 0f) {
            ballX[i] = r
            ballVX[i] = -ballVX[i]
            wallHitThisUpdate = true
        } else if (ballX[i] + r > viewWidth) {
            ballX[i] = viewWidth - r
            ballVX[i] = -ballVX[i]
            wallHitThisUpdate = true
        }
        if (ballY[i] - r < HUD_TOP_PT) {
            ballY[i] = HUD_TOP_PT + r
            ballVY[i] = -ballVY[i]
            wallHitThisUpdate = true
        }

        if (ballVY[i] > 0f && intersectsPaddle(i, r)) {
            ballY[i] = paddleTop() - r
            val offset = (ballX[i] - paddleCenterX) / paddleHalfWidth()
            setVelocityFromOffset(i, offset)
            paddleHitThisUpdate = true
        }

        handleBrickCollisions(i, r)

        if (ballActive[i] && ballY[i] - r > viewHeight) ballActive[i] = false
    }

    private fun intersectsPaddle(i: Int, r: Float): Boolean {
        val pTop = paddleTop()
        val pBottom = pTop + PADDLE_HEIGHT_PT
        val pLeft = paddleCenterX - paddleHalfWidth()
        val pRight = paddleCenterX + paddleHalfWidth()
        return ballY[i] + r >= pTop && ballY[i] - r <= pBottom &&
            ballX[i] + r >= pLeft && ballX[i] - r <= pRight
    }

    /** Resolves at most one brick collision per substep for ball [i], reflecting on the smaller-penetration axis. */
    private fun handleBrickCollisions(i: Int, r: Float) {
        val left = ballX[i] - r
        val right = ballX[i] + r
        val top = ballY[i] - r
        val bottom = ballY[i] + r
        val bw = brickWidth()
        for (row in 0 until BRICK_ROWS) {
            val by = brickY(row)
            if (bottom <= by || top >= by + BRICK_ROW_HEIGHT_PT) continue
            for (col in 0 until BRICK_COLS) {
                val idx = row * BRICK_COLS + col
                if (hitPoints[idx] == 0) continue
                val bx = brickX(col)
                if (right <= bx || left >= bx + bw) continue

                val overlapX = minOf(right, bx + bw) - maxOf(left, bx)
                val overlapY = minOf(bottom, by + BRICK_ROW_HEIGHT_PT) - maxOf(top, by)
                if (overlapX < overlapY) {
                    ballVX[i] = -ballVX[i]
                } else {
                    bounceOffBrickFace(i, bx + bw / 2f, bw / 2f)
                }
                damageBrick(idx)
                return
            }
        }
    }

    /** A hit on brick [idx]: steel only clanks, other bricks lose a hit point and break at zero. */
    private fun damageBrick(idx: Int) {
        if (hitPoints[idx] == STEEL) {
            wallHitThisUpdate = true
            return
        }
        hitPoints[idx]--
        if (hitPoints[idx] <= 0) destroyBrick(idx) else wallHitThisUpdate = true
    }

    /**
     * Bounce on the top or bottom face of a brick: the vertical direction flips and, like on the
     * paddle, the side of the brick that was hit steers the ball (left part -> to the left, right
     * part -> to the right, centre -> unchanged), at the same speed. Without this a ball launched
     * straight up would keep drilling the same column.
     */
    private fun bounceOffBrickFace(i: Int, brickCenterX: Float, brickHalfWidth: Float) {
        val speed = sqrt(ballVX[i] * ballVX[i] + ballVY[i] * ballVY[i])
        if (speed <= 0f) return
        val offset = ((ballX[i] - brickCenterX) / brickHalfWidth).coerceIn(-1f, 1f)
        val limit = speed * MAX_BOUNCE_FRACTION
        val vx = (ballVX[i] + offset * speed * BRICK_DEFLECT).coerceIn(-limit, limit)
        val vyMagnitude = sqrt(speed * speed - vx * vx)
        ballVY[i] = if (ballVY[i] < 0f) vyMagnitude else -vyMagnitude
        ballVX[i] = vx
    }

    private fun destroyBrick(idx: Int) {
        hitPoints[idx] = 0
        bricksRemaining--
        score += POINTS_PER_BRICK * level
        brickHitThisUpdate = true
        maybeDropCapsule(idx)
        if (bricksRemaining <= 0) nextLevel()
    }

    private fun loseLife() {
        lives--
        lifeLostThisUpdate = true
        if (lives <= 0) {
            status = GameStatus.Over(0)
        }
        ballInFlight = false
        clearTransients()
        restBallOnPaddle()
    }

    private fun nextLevel() {
        level++
        if (level > furthestLevel) furthestLevel = level
        loadLevelBricks()
        score += LEVEL_CLEAR_BONUS * level
        ballInFlight = false
        clearTransients()
        restBallOnPaddle()
        levelClearedThisUpdate = true
    }

    /** Fills the brick grid from the layout of the current [level] (layouts repeat after the last one). */
    private fun loadLevelBricks() {
        val layout = LEVELS[(level - 1) % LEVELS.size]
        var destructible = 0
        for (row in 0 until BRICK_ROWS) {
            for (col in 0 until BRICK_COLS) {
                val hp = when (val c = layout[row][col]) {
                    '.' -> 0
                    '#' -> STEEL
                    else -> c - '0'
                }
                hitPoints[row * BRICK_COLS + col] = hp
                if (hp in 1..3) destructible++
            }
        }
        bricksRemaining = destructible
    }

    private fun launchBall() {
        ballInFlight = true
        physicsAccumulatorMs = 0
        setVelocityFromOffset(0, 0f)
    }

    /** Sets ball [i]'s velocity to the current fixed speed, angled by [offset] in `[-1, 1]` (paddle-relative x). */
    private fun setVelocityFromOffset(i: Int, offset: Float) {
        val speed = speedForLevel() * appliedSpeedFactor
        val vx = offset.coerceIn(-1f, 1f) * speed * MAX_BOUNCE_FRACTION
        ballVX[i] = vx
        ballVY[i] = -sqrt(speed * speed - vx * vx)
    }

    private fun speedForLevel(): Float =
        minOf(BASE_SPEED_PT_PER_S + (level - 1) * SPEED_INCREMENT_PER_LEVEL, MAX_SPEED_PT_PER_S)

    // --- Capsules and effects ---------------------------------------------------------------

    private fun maybeDropCapsule(brickIdx: Int) {
        bricksSinceDrop++
        if (bricksSinceDrop < PITY_BRICKS && rng.nextFloat() >= DROP_CHANCE) return
        var slot = -1
        for (k in 0 until MAX_CAPSULES) if (!capActive[k]) { slot = k; break }
        if (slot < 0) return
        bricksSinceDrop = 0
        val row = brickIdx / BRICK_COLS
        val col = brickIdx % BRICK_COLS
        capX[slot] = brickX(col) + brickWidth() / 2f
        capY[slot] = brickY(row) + BRICK_ROW_HEIGHT_PT / 2f
        capType[slot] = pickType()
        capActive[slot] = true
    }

    private fun pickType(): Int {
        var roll = rng.nextInt(TYPE_WEIGHT_TOTAL)
        for (t in 0 until TYPE_COUNT) {
            roll -= TYPE_WEIGHTS[t]
            if (roll < 0) return t
        }
        return TYPE_MULTI
    }

    private fun moveCapsules(dtSec: Float) {
        for (k in 0 until MAX_CAPSULES) {
            if (!capActive[k]) continue
            capY[k] += CAPSULE_SPEED_PT_PER_S * dtSec
            val pTop = paddleTop()
            val caught = capY[k] + CAPSULE_H_PT / 2f >= pTop && capY[k] - CAPSULE_H_PT / 2f <= pTop + PADDLE_HEIGHT_PT &&
                capX[k] + CAPSULE_W_PT / 2f >= paddleCenterX - paddleHalfWidth() &&
                capX[k] - CAPSULE_W_PT / 2f <= paddleCenterX + paddleHalfWidth()
            if (caught) {
                capActive[k] = false
                applyEffect(capType[k])
            } else if (capY[k] - CAPSULE_H_PT / 2f > viewHeight) {
                capActive[k] = false
            }
        }
    }

    private fun applyEffect(type: Int) {
        if (isBonus(type)) bonusCaughtThisUpdate = true else malusCaughtThisUpdate = true
        when (type) {
            TYPE_MULTI -> spawnExtraBalls()
            TYPE_LIFE -> if (lives < MAX_LIVES) lives++
            TYPE_BIG_BALL -> effectLeftMs[TYPE_BIG_BALL] = BIG_BALL_MS
            TYPE_SLOW -> {
                effectLeftMs[TYPE_SLOW] = SLOW_MS
                effectLeftMs[TYPE_FAST] = 0
            }
            TYPE_FAST -> {
                effectLeftMs[TYPE_FAST] = FAST_MS
                effectLeftMs[TYPE_SLOW] = 0
            }
            TYPE_BIG_PADDLE -> {
                effectLeftMs[TYPE_BIG_PADDLE] = BIG_PADDLE_MS
                effectLeftMs[TYPE_SMALL_PADDLE] = 0
            }
            TYPE_SMALL_PADDLE -> {
                effectLeftMs[TYPE_SMALL_PADDLE] = SMALL_PADDLE_MS
                effectLeftMs[TYPE_BIG_PADDLE] = 0
            }
        }
        refreshSpeed()
        clampPaddle()
    }

    /** Adds two balls (when there is room) leaving the first active ball at +/- [SPLIT_ANGLE] rad. */
    private fun spawnExtraBalls() {
        var src = -1
        for (i in 0 until MAX_BALLS) if (ballActive[i]) { src = i; break }
        if (src < 0) return
        var added = 0
        for (i in 0 until MAX_BALLS) {
            if (added >= 2) break
            if (ballActive[i]) continue
            val angle = if (added == 0) SPLIT_ANGLE else -SPLIT_ANGLE
            val c = cos(angle)
            val sn = sin(angle)
            ballX[i] = ballX[src]
            ballY[i] = ballY[src]
            ballVX[i] = ballVX[src] * c - ballVY[src] * sn
            ballVY[i] = ballVX[src] * sn + ballVY[src] * c
            ballActive[i] = true
            added++
        }
    }

    private fun tickEffects(dtMs: Int) {
        var expired = false
        for (t in 0 until TYPE_COUNT) {
            if (effectLeftMs[t] > 0) {
                effectLeftMs[t] -= dtMs
                if (effectLeftMs[t] <= 0) {
                    effectLeftMs[t] = 0
                    expired = true
                }
            }
        }
        if (expired) {
            refreshSpeed()
            clampPaddle()
        }
    }

    /** Rescales every ball's velocity when the speed effect in force changes. */
    private fun refreshSpeed() {
        val factor = speedFactor()
        if (factor == appliedSpeedFactor) return
        val ratio = factor / appliedSpeedFactor
        for (i in 0 until MAX_BALLS) {
            if (!ballActive[i]) continue
            ballVX[i] *= ratio
            ballVY[i] *= ratio
        }
        appliedSpeedFactor = factor
    }

    private fun isBonus(type: Int): Boolean = type != TYPE_FAST && type != TYPE_SMALL_PADDLE

    private fun speedFactor(): Float = when {
        effectLeftMs[TYPE_FAST] > 0 -> FAST_FACTOR
        effectLeftMs[TYPE_SLOW] > 0 -> SLOW_FACTOR
        else -> 1f
    }

    private fun ballRadius(): Float =
        if (effectLeftMs[TYPE_BIG_BALL] > 0) BALL_RADIUS_PT * BIG_BALL_FACTOR else BALL_RADIUS_PT

    // --- Layout helpers (pure functions of current viewWidth/viewHeight/level) --------------------

    private fun paddleWidth(): Float = viewWidth * PADDLE_WIDTH_FRACTION * when {
        effectLeftMs[TYPE_BIG_PADDLE] > 0 -> BIG_PADDLE_FACTOR
        effectLeftMs[TYPE_SMALL_PADDLE] > 0 -> SMALL_PADDLE_FACTOR
        else -> 1f
    }
    private fun paddleHalfWidth(): Float = paddleWidth() / 2f
    private fun paddleTop(): Float = viewHeight - PADDLE_BOTTOM_MARGIN_PT - PADDLE_HEIGHT_PT
    private fun brickWidth(): Float = (viewWidth - (BRICK_COLS + 1) * BRICK_GAP_PT) / BRICK_COLS
    private fun brickX(col: Int): Float = BRICK_GAP_PT + col * (brickWidth() + BRICK_GAP_PT)
    private fun brickY(row: Int): Float = HUD_TOP_PT + BRICK_TOP_MARGIN_PT + row * (BRICK_ROW_HEIGHT_PT + BRICK_GAP_PT)

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)
        if (choosing) {
            renderLevelChoice(scene)
            return
        }

        val bw = brickWidth()
        for (row in 0 until BRICK_ROWS) {
            val by = brickY(row)
            for (col in 0 until BRICK_COLS) {
                val hp = hitPoints[row * BRICK_COLS + col]
                if (hp == 0) continue
                val color = when (hp) {
                    STEEL -> Palette.FG_DIM
                    3 -> Palette.TILE_10
                    2 -> Palette.TILE_9
                    else -> Palette.TILE_0 + row
                }
                scene.rect(brickX(col), by, bw, BRICK_ROW_HEIGHT_PT, color, radius = BRICK_RADIUS_PT)
                if (hp == STEEL) {
                    scene.rect(brickX(col) + 3f, by + 3f, bw - 6f, BRICK_ROW_HEIGHT_PT - 6f, Palette.GRID, radius = 2f)
                }
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

        val r = ballRadius()
        for (i in 0 until MAX_BALLS) {
            if (ballActive[i]) scene.circle(ballX[i], ballY[i], r, Palette.FG)
        }
        renderCapsules(scene)
        renderEffects(scene)

        val dotY = HUD_TOP_PT + LIFE_DOT_RADIUS_PT + LIFE_DOT_TOP_MARGIN_PT
        for (i in 0 until lives) {
            val dotX = viewWidth - LIFE_DOT_RIGHT_MARGIN_PT - LIFE_DOT_RADIUS_PT - i * (LIFE_DOT_RADIUS_PT * 2f + LIFE_DOT_GAP_PT)
            scene.circle(dotX, dotY, LIFE_DOT_RADIUS_PT, Palette.FG)
        }

        if (wallHitThisUpdate || paddleHitThisUpdate) scene.sound(SoundId.HIT)
        scene.text(LEVEL_LABEL_X_PT, LEVEL_Y_PT, StringKey.LEVEL, Palette.FG_DIM, LEVEL_TEXT_PT)
        scene.number(LEVEL_LABEL_X_PT + LEVEL_NUMBER_OFFSET_PT, LEVEL_Y_PT, level, Palette.FG, LEVEL_TEXT_PT)

        if (bonusCaughtThisUpdate) scene.sound(SoundId.BONUS)
        if (malusCaughtThisUpdate) scene.sound(SoundId.HIT)
        if (brickHitThisUpdate) scene.sound(SoundId.EAT)
        if (levelClearedThisUpdate) scene.sound(SoundId.WIN)
        if (lifeLostThisUpdate) {
            scene.sound(SoundId.LOSE)
            scene.haptic()
        }
    }

    /** One tile per level: the ones reached are tappable (finished ones in green, the furthest in orange). */
    private fun renderLevelChoice(scene: Scene) {
        scene.text(viewWidth / 2f, CHOICE_TITLE_Y_PT, StringKey.LEVEL, Palette.FG, CHOICE_TITLE_PT, Align.CENTER)
        val reachable = minOf(furthestLevel, LEVELS.size)
        for (n in 1..LEVELS.size) {
            val col = (n - 1) % CHOICE_COLS
            val row = (n - 1) / CHOICE_COLS
            val x = CHOICE_X_PT + col * (CHOICE_TILE_PT + CHOICE_GAP_PT)
            val y = CHOICE_Y_PT + row * (CHOICE_TILE_PT + CHOICE_GAP_PT)
            val color = when {
                n > reachable -> Palette.GRID
                n < furthestLevel -> Palette.OK
                else -> Palette.ACCENT
            }
            scene.rect(x, y, CHOICE_TILE_PT, CHOICE_TILE_PT, color, radius = 8f)
            if (n > reachable) {
                // Locked: small number on top, a padlock below.
                scene.number(x + CHOICE_TILE_PT / 2f, y + 11f, n, Palette.FG_DIM, CHOICE_LOCKED_NUMBER_PT, Align.CENTER)
                renderPadlock(scene, x + CHOICE_TILE_PT / 2f, y + 31f, color)
            } else {
                scene.number(x + CHOICE_TILE_PT / 2f, y + CHOICE_TILE_PT / 2f, n, Palette.BG, CHOICE_NUMBER_PT, Align.CENTER)
            }
        }
    }

    /** A small padlock whose body top-centre is at ([cx], [top]); [tileColor] punches the shackle's hole. */
    private fun renderPadlock(scene: Scene, cx: Float, top: Float, tileColor: Int) {
        scene.arc(cx, top, 7.5f, 180f, 180f, Palette.FG_DIM)
        scene.arc(cx, top, 4.5f, 180f, 180f, tileColor)
        scene.rect(cx - 10f, top, 20f, 14f, Palette.FG_DIM, radius = 3f)
        scene.circle(cx, top + 6f, 1.8f, tileColor)
    }

    private fun renderCapsules(scene: Scene) {
        for (k in 0 until MAX_CAPSULES) {
            if (!capActive[k]) continue
            val color = if (isBonus(capType[k])) Palette.NEON_GREEN else Palette.NEON_RED
            scene.rect(capX[k] - CAPSULE_W_PT / 2f, capY[k] - CAPSULE_H_PT / 2f, CAPSULE_W_PT, CAPSULE_H_PT, color, radius = CAPSULE_H_PT / 2f)
            scene.label(capX[k], capY[k], TYPE_LABELS[capType[k]], Palette.BEZEL, CAPSULE_TEXT_PT, Align.CENTER)
        }
    }

    /** One small bar per timed effect in force, along the bottom edge, shrinking as it runs out. */
    private fun renderEffects(scene: Scene) {
        var slot = 0
        for (t in 0 until TYPE_COUNT) {
            val left = effectLeftMs[t]
            if (left <= 0) continue
            val color = if (isBonus(t)) Palette.NEON_GREEN else Palette.NEON_RED
            val x = EFFECT_BAR_MARGIN_PT + slot * (EFFECT_BAR_W_PT + EFFECT_BAR_GAP_PT)
            val y = viewHeight - EFFECT_BAR_BOTTOM_PT
            scene.label(x + EFFECT_BAR_W_PT / 2f, y - 7f, TYPE_LABELS[t], Palette.FG_DIM, EFFECT_TEXT_PT, Align.CENTER)
            scene.rect(x, y, EFFECT_BAR_W_PT * left / TYPE_DURATIONS[t], EFFECT_BAR_H_PT, color, radius = 2f)
            slot++
        }
    }

    // --- Test-only accessors (internal: exercised by BreakoutRulesTest) ----

    internal fun debugBallInFlight(): Boolean = ballInFlight
    internal fun debugBallX(): Float = ballX[0]
    internal fun debugBallY(): Float = ballY[0]
    internal fun debugBallVX(): Float = ballVX[0]
    internal fun debugBallVY(): Float = ballVY[0]
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
        ballActive[0] = true
        ballX[0] = x
        ballY[0] = y
        ballVX[0] = vx
        ballVY[0] = vy
        ballInFlight = inFlight
        physicsAccumulatorMs = 0
    }

    internal fun debugChoosing(): Boolean = choosing
    internal fun debugFurthestLevel(): Int = furthestLevel
    internal fun debugChoiceTileCenterX(n: Int): Float = CHOICE_X_PT + ((n - 1) % CHOICE_COLS) * (CHOICE_TILE_PT + CHOICE_GAP_PT) + CHOICE_TILE_PT / 2f
    internal fun debugChoiceTileCenterY(n: Int): Float = CHOICE_Y_PT + ((n - 1) / CHOICE_COLS) * (CHOICE_TILE_PT + CHOICE_GAP_PT) + CHOICE_TILE_PT / 2f
    internal fun debugActiveBalls(): Int = activeBalls()
    internal fun debugBallSpeed(): Float = sqrt(ballVX[0] * ballVX[0] + ballVY[0] * ballVY[0])
    internal fun debugBallRadius(): Float = ballRadius()
    internal fun debugPaddleWidth(): Float = paddleWidth()
    internal fun debugEffectLeftMs(type: Int): Int = effectLeftMs[type]
    internal fun debugApplyEffect(type: Int) = applyEffect(type)
    internal fun debugCapsuleCount(): Int = capActive.count { it }
    internal fun debugBricksSinceDrop(): Int = bricksSinceDrop

    internal fun debugSpawnCapsule(type: Int, x: Float, y: Float) {
        for (k in 0 until MAX_CAPSULES) {
            if (capActive[k]) continue
            capActive[k] = true
            capType[k] = type
            capX[k] = x
            capY[k] = y
            return
        }
    }

    /** Destroys the brick at ([row], [col]) directly, exercising the same scoring/level-clear path as physics collisions. */
    internal fun debugDestroyBrick(row: Int, col: Int) {
        val idx = row * BRICK_COLS + col
        if (hitPoints[idx] in 1..3) destroyBrick(idx)
    }

    internal fun debugSetLives(n: Int) {
        lives = n
    }

    public companion object {
        private const val NO_POINTER: Int = -1
        public const val BRICK_COLS: Int = 8
        public const val BRICK_ROWS: Int = 8

        /** Hit-point value of an indestructible brick. */
        internal const val STEEL: Int = 99

        /**
         * Ten layouts, top row first, 8 columns each: `.` empty, `1`-`3` hits to break, `#` steel.
         * The lowest rows are the ones the ball meets first, so steel and tough bricks higher up
         * shield what lies behind them.
         */
        private val LEVELS: Array<Array<String>> = arrayOf(
            arrayOf("11111111", "11111111", "11111111", "11111111", "11111111", "11111111", "........", "........"),
            arrayOf("1.1.1.1.", ".1.1.1.1", "1.1.1.1.", ".1.1.1.1", "1.1.1.1.", ".1.1.1.1", "........", "........"),
            arrayOf("...11...", "..1111..", ".111111.", "11111111", "11111111", "11111111", "........", "........"),
            arrayOf("22222222", "11111111", "11111111", "11111111", "22222222", "11111111", "........", "........"),
            arrayOf("11111111", "1#1111#1", "1#1111#1", "1#1111#1", "1#1111#1", "11111111", "........", "........"),
            arrayOf("...22...", "..1111..", ".111111.", "11111111", "11111111", ".111111.", "..1111..", "...11..."),
            arrayOf("22222222", "2......2", "2.1111.2", "2.1##1.2", "2.1111.2", "2......2", "22222222", "........"),
            arrayOf("1......1", ".1....1.", "..1111..", ".111111.", "11.11.11", "11111111", "1.1..1.1", "1......1"),
            arrayOf("11111111", "######.#", "11111111", "#.######", "11111111", "........", "........", "........"),
            arrayOf("#......#", ".222222.", ".233332.", ".233332.", ".222222.", "11111111", "........", "........"),
        )

        internal const val TYPE_MULTI: Int = 0
        internal const val TYPE_BIG_BALL: Int = 1
        internal const val TYPE_SLOW: Int = 2
        internal const val TYPE_LIFE: Int = 3
        internal const val TYPE_BIG_PADDLE: Int = 4
        internal const val TYPE_FAST: Int = 5
        internal const val TYPE_SMALL_PADDLE: Int = 6
        private const val TYPE_COUNT: Int = 7

        /** Relative drop odds per type: bonus 10, malus 6 out of 16. */
        private val TYPE_WEIGHTS: IntArray = intArrayOf(3, 2, 2, 1, 2, 3, 3)
        private val TYPE_WEIGHT_TOTAL: Int = TYPE_WEIGHTS.sum()
        private val TYPE_LABELS: Array<String> = arrayOf("x3", "O+", "<<", "+1", "<->", ">>", ">-<")

        private const val BIG_BALL_MS: Int = 12000
        private const val SLOW_MS: Int = 10000
        private const val FAST_MS: Int = 8000
        private const val BIG_PADDLE_MS: Int = 14000
        private const val SMALL_PADDLE_MS: Int = 10000

        /** Durations by type, for the shrinking bars (instant effects have none). */
        private val TYPE_DURATIONS: FloatArray = floatArrayOf(
            1f, BIG_BALL_MS.toFloat(), SLOW_MS.toFloat(), 1f,
            BIG_PADDLE_MS.toFloat(), FAST_MS.toFloat(), SMALL_PADDLE_MS.toFloat(),
        )

        private const val MAX_BALLS: Int = 5
        private const val MAX_CAPSULES: Int = 4
        private const val MAX_LIVES: Int = 5
        private const val DROP_CHANCE: Float = 0.2f
        private const val PITY_BRICKS: Int = 7
        private const val CAPSULE_SPEED_PT_PER_S: Float = 110f
        private const val CAPSULE_W_PT: Float = 34f
        private const val CAPSULE_H_PT: Float = 16f
        private const val CAPSULE_TEXT_PT: Float = 11f
        private const val SPLIT_ANGLE: Float = 0.35f
        private const val BIG_BALL_FACTOR: Float = 1.7f
        private const val SLOW_FACTOR: Float = 0.7f
        private const val FAST_FACTOR: Float = 1.4f
        private const val BIG_PADDLE_FACTOR: Float = 1.5f
        private const val SMALL_PADDLE_FACTOR: Float = 0.62f

        private const val EFFECT_BAR_MARGIN_PT: Float = 8f
        private const val EFFECT_BAR_W_PT: Float = 44f
        private const val EFFECT_BAR_H_PT: Float = 4f
        private const val EFFECT_BAR_GAP_PT: Float = 8f
        private const val EFFECT_BAR_BOTTOM_PT: Float = 10f
        private const val EFFECT_TEXT_PT: Float = 10f

        private const val PHYSICS_STEP_MS: Int = 4
        private const val MAX_STEPS_PER_UPDATE: Int = 32

        private const val BASE_SPEED_PT_PER_S: Float = 220f
        private const val SPEED_INCREMENT_PER_LEVEL: Float = 18f
        private const val MAX_SPEED_PT_PER_S: Float = 460f
        private const val MAX_BOUNCE_FRACTION: Float = 0.9f

        /** How strongly the hit position on a brick steers the ball (1 = like the paddle edge). */
        private const val BRICK_DEFLECT: Float = 0.6f

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

        private const val CHOICE_COLS: Int = 5
        private const val CHOICE_ROWS: Int = 2
        private const val CHOICE_TILE_PT: Float = 56f
        private const val CHOICE_GAP_PT: Float = 10f
        private const val CHOICE_X_PT: Float = 20f
        private const val CHOICE_Y_PT: Float = 210f
        private const val CHOICE_TITLE_Y_PT: Float = 150f
        private const val CHOICE_TITLE_PT: Float = 28f
        private const val CHOICE_NUMBER_PT: Float = 24f
        private const val CHOICE_LOCKED_NUMBER_PT: Float = 14f

        private const val LEVEL_LABEL_X_PT: Float = 132f
        private const val LEVEL_NUMBER_OFFSET_PT: Float = 62f
        private const val LEVEL_Y_PT: Float = 8f
        private const val LEVEL_TEXT_PT: Float = 16f

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
            icon = { scene, size -> ArcadeIcons.wallSmash(scene, size) },
        )
    }
}
