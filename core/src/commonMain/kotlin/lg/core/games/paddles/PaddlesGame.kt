package lg.core.games.paddles

import lg.core.Complexity
import lg.core.Game
import lg.core.GameMeta
import lg.core.GameStatus
import lg.core.Orientation
import lg.core.Tier
import lg.core.input.Gesture
import lg.core.input.PointerPhase
import lg.core.scene.Align
import lg.core.scene.Palette
import lg.core.scene.ArcadeIcons
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey
import lg.core.util.Layout
import lg.core.util.Rect
import lg.core.util.Rng
import kotlin.math.sqrt

/**
 * Pong-like game for a shared screen: two paddles sit at the two short ends
 * (top/bottom in portrait, left/right in landscape, matching [Layout.splitTwoPlayers]),
 * each dragged by a raw pointer confined to its own half. First to
 * [WIN_SCORE_TARGET] points wins.
 *
 * ### Axis abstraction
 *
 * To avoid duplicating the physics for both orientations, ball and paddle
 * positions are tracked along two logical axes rather than raw x/y:
 * - **main**: the scoring axis, `0` at paddle A's edge and [mainLength] at
 *   paddle B's edge (screen `y` in portrait, screen `x` in landscape).
 * - **cross**: the paddle-movement axis, `0..crossLength` (screen `x` in
 *   portrait, screen `y` in landscape).
 *
 * [toScreenX]/[toScreenY] convert a (main, cross) pair to screen coordinates
 * for rendering; [portrait] (recomputed on [resize]) selects the mapping.
 *
 * ### Input
 *
 * Paddle A is always human (its own pointer id, confined to zone A). In
 * 2-player mode paddle B is human too; in 1-player mode it is driven by a
 * simple bounded-speed AI that chases the ball's cross coordinate, run from
 * [update] so it reacts at the same fixed-timestep cadence as the physics.
 */
public class PaddlesGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    /** Total points played so far (scoreA + scoreB); see the class KDoc for the per-side scores. */
    override var score: Int = 0
        private set

    override val wantsFrames: Boolean
        get() = status == GameStatus.Running

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private var portrait: Boolean = true
    private var mainLength: Float = 1f
    private var crossLength: Float = 1f
    private var playfieldTop: Float = HUD_TOP_PT
    private val zoneA = Rect()
    private val zoneB = Rect()

    private var playerCount: Int = 1
    private var rng: Rng = Rng(0L)

    private var paddleACross: Float = 0f
    private var paddleBCross: Float = 0f
    private var pointerIdForA: Int = NO_POINTER
    private var pointerIdForB: Int = NO_POINTER

    private var ballMain: Float = 0f
    private var ballCross: Float = 0f
    private var ballVMain: Float = 0f
    private var ballVCross: Float = 0f
    private var speed: Float = BASE_SPEED_PT_PER_S
    private var physicsAccumulatorMs: Int = 0

    private var scoreA: Int = 0
    private var scoreB: Int = 0

    // Events from the most recently processed update() call, read (not mutated) by render().
    private var wallHitThisUpdate: Boolean = false
    private var paddleHitThisUpdate: Boolean = false
    private var pointScoredThisUpdate: Boolean = false
    private var wonThisUpdate: Boolean = false

    override fun reset(seed: Long, players: Int) {
        rng = Rng(seed)
        playerCount = players.coerceIn(1, 2)
        scoreA = 0
        scoreB = 0
        score = 0
        pointerIdForA = NO_POINTER
        pointerIdForB = NO_POINTER
        physicsAccumulatorMs = 0
        wallHitThisUpdate = false
        paddleHitThisUpdate = false
        pointScoredThisUpdate = false
        wonThisUpdate = false
        status = GameStatus.Running

        // Geometry may already be known (resize() called before reset(), the
        // realistic platform ordering: the Start overlay needs real bounds
        // to lay out its button before the player can even tap it). Use it
        // immediately for a properly centered serve; otherwise fall back to
        // safe zeroes and let the next resize() clamp them into range.
        layoutGeometry()
        if (viewWidth > 0f && viewHeight > 0f) {
            paddleACross = crossLength / 2f
            paddleBCross = crossLength / 2f
            serve(towardA = rng.nextBoolean())
        } else {
            paddleACross = 0f
            paddleBCross = 0f
            ballMain = 0f
            ballCross = 0f
            ballVMain = 0f
            ballVCross = 0f
        }
    }

    /** Recomputes [portrait]/[mainLength]/[crossLength]/[zoneA]/[zoneB] from the current view size. */
    private fun layoutGeometry() {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        playfieldTop = HUD_TOP_PT
        val pfW = viewWidth
        val pfH = (viewHeight - playfieldTop).coerceAtLeast(1f)
        portrait = pfH >= pfW
        mainLength = if (portrait) pfH else pfW
        crossLength = if (portrait) pfW else pfH

        Layout.splitTwoPlayers(pfW, pfH, zoneA, zoneB)
        zoneA.y += playfieldTop
        zoneB.y += playfieldTop
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        layoutGeometry()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val half = paddleLength() / 2f
        paddleACross = paddleACross.coerceIn(half, crossLength - half)
        paddleBCross = paddleBCross.coerceIn(half, crossLength - half)
    }

    private fun paddleLength(): Float = crossLength * PADDLE_LENGTH_FRACTION

    private fun serve(towardA: Boolean) {
        ballMain = mainLength / 2f
        ballCross = crossLength / 2f
        speed = BASE_SPEED_PT_PER_S
        val crossFrac = (rng.nextFloat() - 0.5f) * 2f * MAX_SERVE_CROSS_FRACTION
        val vCross = crossFrac * speed
        val vMainMag = sqrt((speed * speed - vCross * vCross).coerceAtLeast(0f))
        ballVCross = vCross
        ballVMain = if (towardA) -vMainMag else vMainMag
        physicsAccumulatorMs = 0
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        if (status != GameStatus.Running || viewWidth <= 0f) return
        when (phase) {
            PointerPhase.DOWN -> {
                if (inZone(zoneA, x, y)) {
                    pointerIdForA = id
                    paddleACross = clampCross(crossOf(x, y))
                } else if (playerCount == 2 && inZone(zoneB, x, y)) {
                    pointerIdForB = id
                    paddleBCross = clampCross(crossOf(x, y))
                }
            }
            PointerPhase.MOVE -> {
                if (id == pointerIdForA) {
                    paddleACross = clampCross(crossOf(x, y))
                } else if (id == pointerIdForB) {
                    paddleBCross = clampCross(crossOf(x, y))
                }
            }
            PointerPhase.UP, PointerPhase.CANCEL -> {
                if (id == pointerIdForA) pointerIdForA = NO_POINTER
                if (id == pointerIdForB) pointerIdForB = NO_POINTER
            }
        }
    }

    override fun onGesture(gesture: Gesture) {
        // Paddles is drag-driven only (see onPointer); gestures are ignored.
    }

    private fun inZone(zone: Rect, x: Float, y: Float): Boolean =
        x >= zone.x && x <= zone.x + zone.w && y >= zone.y && y <= zone.y + zone.h

    /** Converts a screen point into its cross-axis coordinate (see the class KDoc). */
    private fun crossOf(x: Float, y: Float): Float = if (portrait) x else (y - playfieldTop)

    private fun clampCross(v: Float): Float {
        val half = paddleLength() / 2f
        return v.coerceIn(half, crossLength - half)
    }

    override fun update(dtMs: Int): Boolean {
        wallHitThisUpdate = false
        paddleHitThisUpdate = false
        pointScoredThisUpdate = false
        wonThisUpdate = false
        if (status != GameStatus.Running || viewWidth <= 0f) return false

        physicsAccumulatorMs += dtMs
        var steps = 0
        var changed = false
        while (physicsAccumulatorMs >= PHYSICS_STEP_MS && steps < MAX_STEPS_PER_UPDATE) {
            physicsAccumulatorMs -= PHYSICS_STEP_MS
            stepPhysics()
            steps++
            changed = true
            if (status != GameStatus.Running) break
        }
        return changed
    }

    private fun stepPhysics() {
        val dtSec = PHYSICS_STEP_MS / 1000f

        if (playerCount == 1) {
            val target = ballCross
            val maxStep = AI_MAX_SPEED_PT_PER_S * dtSec
            paddleBCross = if (target > paddleBCross) {
                (paddleBCross + maxStep).coerceAtMost(target)
            } else {
                (paddleBCross - maxStep).coerceAtLeast(target)
            }
            paddleBCross = clampCross(paddleBCross)
        }

        ballMain += ballVMain * dtSec
        ballCross += ballVCross * dtSec

        if (ballCross - BALL_RADIUS_PT < 0f) {
            ballCross = BALL_RADIUS_PT
            ballVCross = -ballVCross
            wallHitThisUpdate = true
        } else if (ballCross + BALL_RADIUS_PT > crossLength) {
            ballCross = crossLength - BALL_RADIUS_PT
            ballVCross = -ballVCross
            wallHitThisUpdate = true
        }

        val half = paddleLength() / 2f
        if (ballVMain < 0f && ballMain - BALL_RADIUS_PT <= PADDLE_INSET_PT &&
            ballMain - BALL_RADIUS_PT >= PADDLE_INSET_PT - PADDLE_CATCH_DEPTH_PT &&
            ballCross >= paddleACross - half - BALL_RADIUS_PT && ballCross <= paddleACross + half + BALL_RADIUS_PT
        ) {
            bounceOffPaddle(paddleACross, atA = true)
        } else if (ballVMain > 0f && ballMain + BALL_RADIUS_PT >= mainLength - PADDLE_INSET_PT &&
            ballMain + BALL_RADIUS_PT <= mainLength - PADDLE_INSET_PT + PADDLE_CATCH_DEPTH_PT &&
            ballCross >= paddleBCross - half - BALL_RADIUS_PT && ballCross <= paddleBCross + half + BALL_RADIUS_PT
        ) {
            bounceOffPaddle(paddleBCross, atA = false)
        }

        if (ballMain < -BALL_MISS_MARGIN_PT) {
            awardPoint(toB = true)
        } else if (ballMain > mainLength + BALL_MISS_MARGIN_PT) {
            awardPoint(toB = false)
        }
    }

    private fun bounceOffPaddle(paddleCross: Float, atA: Boolean) {
        speed = (speed + SPEED_INCREMENT_PER_HIT).coerceAtMost(MAX_SPEED_PT_PER_S)
        val offset = ((ballCross - paddleCross) / (paddleLength() / 2f)).coerceIn(-1f, 1f)
        val vCross = offset * speed * MAX_BOUNCE_FRACTION
        val vMainMag = sqrt((speed * speed - vCross * vCross).coerceAtLeast(0f))
        ballVCross = vCross
        ballVMain = if (atA) vMainMag else -vMainMag
        ballMain = if (atA) PADDLE_INSET_PT + BALL_RADIUS_PT else mainLength - PADDLE_INSET_PT - BALL_RADIUS_PT
        paddleHitThisUpdate = true
    }

    private fun awardPoint(toB: Boolean) {
        if (toB) scoreB++ else scoreA++
        score = scoreA + scoreB
        pointScoredThisUpdate = true
        if (scoreA >= WIN_SCORE_TARGET || scoreB >= WIN_SCORE_TARGET) {
            status = GameStatus.Over(if (scoreA > scoreB) 1 else 2)
            wonThisUpdate = true
        } else {
            serve(towardA = toB)
        }
    }

    // --- Test-only accessors (internal: exercised by PaddlesRulesTest) ----

    internal fun debugPaddleACross(): Float = paddleACross
    internal fun debugPaddleBCross(): Float = paddleBCross
    internal fun debugBallMain(): Float = ballMain
    internal fun debugBallCross(): Float = ballCross
    internal fun debugScoreA(): Int = scoreA
    internal fun debugScoreB(): Int = scoreB
    internal fun debugZoneA(): Rect = zoneA
    internal fun debugZoneB(): Rect = zoneB
    internal fun debugPortrait(): Boolean = portrait

    internal fun debugSetBall(main: Float, cross: Float, vMain: Float, vCross: Float) {
        ballMain = main
        ballCross = cross
        ballVMain = vMain
        ballVCross = vCross
        physicsAccumulatorMs = 0
    }

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)

        val half = paddleLength() / 2f
        drawPaddle(scene, PADDLE_INSET_PT, paddleACross, half, Palette.P1)
        drawPaddle(scene, mainLength - PADDLE_INSET_PT, paddleBCross, half, Palette.P2)

        val bx = toScreenX(ballMain, ballCross)
        val by = toScreenY(ballMain, ballCross)
        scene.circle(bx, by, BALL_RADIUS_PT, Palette.FG)

        scene.circle(toScreenX(mainLength / 2f, crossLength / 2f), toScreenY(mainLength / 2f, crossLength / 2f), 1.5f, Palette.GRID)
        scene.number(
            toScreenX(mainLength * 0.2f, crossLength / 2f),
            toScreenY(mainLength * 0.2f, crossLength / 2f),
            scoreA,
            Palette.P1,
            SCORE_TEXT_SIZE_PT,
            Align.CENTER,
        )
        scene.number(
            toScreenX(mainLength * 0.8f, crossLength / 2f),
            toScreenY(mainLength * 0.8f, crossLength / 2f),
            scoreB,
            Palette.P2,
            SCORE_TEXT_SIZE_PT,
            Align.CENTER,
        )

        if (paddleHitThisUpdate) scene.sound(SoundId.HIT)
        if (wallHitThisUpdate) scene.sound(SoundId.TICK)
        if (pointScoredThisUpdate && !wonThisUpdate) scene.sound(SoundId.HIT)
        if (wonThisUpdate) {
            scene.sound(SoundId.WIN)
            scene.haptic()
        }
    }

    private fun drawPaddle(scene: Scene, main: Float, cross: Float, half: Float, color: Int) {
        val x1 = toScreenX(main, cross - half)
        val y1 = toScreenY(main, cross - half)
        val x2 = toScreenX(main, cross + half)
        val y2 = toScreenY(main, cross + half)
        scene.line(x1, y1, x2, y2, color, width = PADDLE_THICKNESS_PT)
    }

    private fun toScreenX(main: Float, cross: Float): Float = if (portrait) cross else main
    private fun toScreenY(main: Float, cross: Float): Float = if (portrait) playfieldTop + main else playfieldTop + cross

    public companion object {
        public const val WIN_SCORE_TARGET: Int = 7

        private const val PHYSICS_STEP_MS: Int = 4
        private const val MAX_STEPS_PER_UPDATE: Int = 32

        private const val BASE_SPEED_PT_PER_S: Float = 220f
        private const val MAX_SPEED_PT_PER_S: Float = 420f
        private const val SPEED_INCREMENT_PER_HIT: Float = 14f
        private const val MAX_BOUNCE_FRACTION: Float = 0.85f
        private const val MAX_SERVE_CROSS_FRACTION: Float = 0.5f

        private const val AI_MAX_SPEED_PT_PER_S: Float = 180f

        private const val BALL_RADIUS_PT: Float = 6f
        private const val BALL_MISS_MARGIN_PT: Float = 24f
        private const val PADDLE_LENGTH_FRACTION: Float = 0.22f
        private const val PADDLE_INSET_PT: Float = 14f
        private const val PADDLE_CATCH_DEPTH_PT: Float = 10f
        private const val PADDLE_THICKNESS_PT: Float = 8f

        private const val SCORE_TEXT_SIZE_PT: Float = 28f
        private const val NO_POINTER: Int = -1

        /** Vertical strip reserved for the session HUD (score / best). */
        private const val HUD_TOP_PT: Float = 32f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "paddles",
            titleKey = StringKey.TITLE_PADDLES,
            tier = Tier.UNLOCKED,
            players = 1..2,
            orientation = Orientation.PORTRAIT,
            complexity = Complexity.S,
            resumable = false,
            factory = { PaddlesGame() },
            icon = { scene, size -> ArcadeIcons.paddles(scene, size) },
        )
    }
}
