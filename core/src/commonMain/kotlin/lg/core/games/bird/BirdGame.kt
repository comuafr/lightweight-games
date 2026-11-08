package lg.core.games.bird

import lg.core.Complexity
import lg.core.Game
import lg.core.GameMeta
import lg.core.GameStatus
import lg.core.Orientation
import lg.core.Tier
import lg.core.input.Gesture
import lg.core.input.GestureKind
import lg.core.input.PointerPhase
import lg.core.scene.Palette
import lg.core.scene.ArcadeIcons
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey
import lg.core.util.Rng

/**
 * Minimal "flappy" game: the bird falls under gravity, a TAP gives an
 * upward impulse, and pairs of pipes with a gap scroll in from the right at
 * a constant speed.
 *
 * ### Data layout
 *
 * A fixed pool of [PIPE_COUNT] pipe pairs is kept in parallel arrays
 * ([pipeX], [pipeGapCenterY], [pipeScored]); once a pipe scrolls fully off
 * the left edge it is recycled to just past the rightmost pipe with a new
 * random gap center (via [rng]), so the pool never grows and nothing is
 * allocated in [update].
 *
 * ### Launch
 *
 * The bird starts at rest ([launched] false): no gravity, no pipe motion,
 * [wantsFrames] false. The first TAP sets [launched] and applies the same
 * upward impulse every subsequent TAP does.
 *
 * ### Difficulty
 *
 * It starts gentle (wide gaps, slow scrolling, for young kids) and tightens with the score,
 * linearly over the first [RAMP_SCORE] pipes, towards the narrower gaps and faster scroll of the
 * classic game: [difficulty] goes from 0 to 1 and drives [pipeSpeed] and [gapFraction]. Each pipe
 * keeps the gap height it was spawned with ([pipeGapHalf]), so the pipes already on screen do not
 * change shape under the player. A new gap centre is never more than [MAX_GAP_SHIFT_FRACTION]
 * of the playfield away from the previous one, so every pipe stays reachable at top speed.
 */
public class BirdGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    override var score: Int = 0
        private set

    override val wantsFrames: Boolean
        get() = status == GameStatus.Running && launched

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f

    private var rng: Rng = Rng(0L)
    private var launched: Boolean = false

    private var birdX: Float = 0f
    private var birdY: Float = 0f
    private var birdVY: Float = 0f
    private var physicsAccumulatorMs: Int = 0

    private val pipeX = FloatArray(PIPE_COUNT)
    private val pipeGapCenterY = FloatArray(PIPE_COUNT)
    private val pipeGapHalf = FloatArray(PIPE_COUNT)
    private val pipeScored = BooleanArray(PIPE_COUNT)

    // Events from the most recently processed update() call, read (not mutated) by render().
    private var scoredThisUpdate: Boolean = false
    private var diedThisUpdate: Boolean = false

    override fun reset(seed: Long, players: Int) {
        rng = Rng(seed)
        launched = false
        birdVY = 0f
        physicsAccumulatorMs = 0
        score = 0
        scoredThisUpdate = false
        diedThisUpdate = false
        status = GameStatus.Running
        // Real placement happens in resize(), which is always called before
        // the first render (viewWidth/Height are still possibly 0 here).
        if (viewWidth > 0f && viewHeight > 0f) layoutRestingState()
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        if (viewWidth <= 0f || viewHeight <= 0f) return
        birdX = viewWidth * BIRD_X_FRACTION
        if (!launched) {
            layoutRestingState()
        } else {
            birdY = birdY.coerceIn(ceilingY(), groundY())
        }
    }

    private fun layoutRestingState() {
        birdX = viewWidth * BIRD_X_FRACTION
        birdY = (ceilingY() + groundY()) / 2f
        for (i in 0 until PIPE_COUNT) {
            pipeX[i] = viewWidth + PIPE_WIDTH_PT + i * PIPE_SPACING_PT
            pipeGapHalf[i] = gapHalfHeight()
            pipeGapCenterY[i] = randomGapCenter(pipeGapHalf[i], if (i == 0) birdY else pipeGapCenterY[i - 1])
            pipeScored[i] = false
        }
    }

    /** A gap centre that fits the field for a gap of half-height [half], within reach of [previous]. */
    private fun randomGapCenter(half: Float, previous: Float): Float {
        val playableTop = ceilingY() + half + GAP_MARGIN_PT
        val playableBottom = groundY() - half - GAP_MARGIN_PT
        val maxShift = (groundY() - ceilingY()) * MAX_GAP_SHIFT_FRACTION
        val lo = maxOf(playableTop, previous - maxShift)
        val hi = minOf(playableBottom, previous + maxShift)
        if (hi <= lo) return ((playableTop + playableBottom) / 2f).coerceAtLeast(playableTop)
        return lo + rng.nextFloat() * (hi - lo)
    }

    /** 0 at the start, 1 once [RAMP_SCORE] pipes have been passed. */
    private fun difficulty(): Float = (score.toFloat() / RAMP_SCORE).coerceAtMost(1f)
    private fun gapFraction(): Float = GAP_FRACTION + (GAP_FRACTION_MIN - GAP_FRACTION) * difficulty()
    private fun pipeSpeed(): Float = PIPE_SPEED_PT_PER_S + (PIPE_SPEED_MAX_PT_PER_S - PIPE_SPEED_PT_PER_S) * difficulty()

    private fun ceilingY(): Float = HUD_TOP_PT
    private fun groundY(): Float = (viewHeight - GROUND_HEIGHT_PT).coerceAtLeast(ceilingY() + 1f)
    private fun gapHalfHeight(): Float {
        val playable = (groundY() - ceilingY()).coerceAtLeast(1f)
        return (playable * gapFraction() / 2f).coerceAtLeast(MIN_GAP_HALF_PT)
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        // Bird is tap-driven only (via onGesture); raw pointer events are ignored.
    }

    override fun onGesture(gesture: Gesture) {
        if (status != GameStatus.Running) return
        if (gesture.kind != GestureKind.TAP) return
        launched = true
        birdVY = IMPULSE_VY_PT_PER_S
    }

    override fun update(dtMs: Int): Boolean {
        scoredThisUpdate = false
        diedThisUpdate = false
        if (status != GameStatus.Running || !launched) return false

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
        birdVY += GRAVITY_PT_PER_S2 * dtSec
        birdY += birdVY * dtSec

        for (i in 0 until PIPE_COUNT) {
            pipeX[i] -= pipeSpeed() * dtSec
            if (pipeX[i] + PIPE_WIDTH_PT < 0f) recyclePipe(i)
            if (!pipeScored[i] && pipeX[i] + PIPE_WIDTH_PT < birdX) {
                pipeScored[i] = true
                score++
                scoredThisUpdate = true
            }
        }

        if (birdY - BIRD_RADIUS_PT <= ceilingY() || birdY + BIRD_RADIUS_PT >= groundY()) {
            die()
            return
        }
        for (i in 0 until PIPE_COUNT) {
            if (hitsPipe(i)) {
                die()
                return
            }
        }
    }

    private fun hitsPipe(i: Int): Boolean {
        val left = birdX + BIRD_RADIUS_PT
        val right = birdX - BIRD_RADIUS_PT
        if (right > pipeX[i] + PIPE_WIDTH_PT || left < pipeX[i]) return false
        val gapTop = pipeGapCenterY[i] - pipeGapHalf[i]
        val gapBottom = pipeGapCenterY[i] + pipeGapHalf[i]
        return birdY - BIRD_RADIUS_PT < gapTop || birdY + BIRD_RADIUS_PT > gapBottom
    }

    private fun recyclePipe(i: Int): Unit {
        var maxX = pipeX[i]
        for (j in 0 until PIPE_COUNT) if (pipeX[j] > maxX) maxX = pipeX[j]
        var previous = birdY
        for (j in 0 until PIPE_COUNT) if (pipeX[j] == maxX && j != i) previous = pipeGapCenterY[j]
        pipeX[i] = maxX + PIPE_SPACING_PT
        pipeGapHalf[i] = gapHalfHeight()
        pipeGapCenterY[i] = randomGapCenter(pipeGapHalf[i], previous)
        pipeScored[i] = false
    }

    private fun die() {
        status = GameStatus.Over(0)
        diedThisUpdate = true
    }

    // --- Test-only accessors (internal: exercised by BirdRulesTest) ----

    internal fun debugLaunched(): Boolean = launched
    internal fun debugBirdY(): Float = birdY
    internal fun debugBirdVY(): Float = birdVY
    internal fun debugBirdX(): Float = birdX
    internal fun debugCeilingY(): Float = ceilingY()
    internal fun debugGroundY(): Float = groundY()
    internal fun debugPipeX(i: Int): Float = pipeX[i]
    internal fun debugPipeGapCenterY(i: Int): Float = pipeGapCenterY[i]
    internal fun debugGapHalfHeight(): Float = gapHalfHeight()
    internal fun debugSetScore(n: Int) {
        score = n
    }

    internal fun debugSetBird(x: Float, y: Float, vy: Float, launched: Boolean = true) {
        birdX = x
        birdY = y
        birdVY = vy
        this.launched = launched
        physicsAccumulatorMs = 0
    }

    internal fun debugForcePipe(i: Int, x: Float, gapCenterY: Float, scored: Boolean = false) {
        pipeX[i] = x
        pipeGapCenterY[i] = gapCenterY
        pipeGapHalf[i] = gapHalfHeight()
        pipeScored[i] = scored
    }

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)

        for (i in 0 until PIPE_COUNT) {
            val x = pipeX[i]
            if (x + PIPE_WIDTH_PT < 0f || x > viewWidth) continue
            val gapTop = pipeGapCenterY[i] - pipeGapHalf[i]
            val gapBottom = pipeGapCenterY[i] + pipeGapHalf[i]
            scene.rect(x, ceilingY(), PIPE_WIDTH_PT, (gapTop - ceilingY()).coerceAtLeast(0f), Palette.TILE_3)
            scene.rect(x, gapBottom, PIPE_WIDTH_PT, (groundY() - gapBottom).coerceAtLeast(0f), Palette.TILE_3)
        }

        scene.rect(0f, groundY(), viewWidth, GROUND_HEIGHT_PT, Palette.SURFACE)

        scene.circle(birdX, birdY, BIRD_RADIUS_PT, Palette.ACCENT)
        scene.circle(birdX + BIRD_RADIUS_PT * 0.4f, birdY - BIRD_RADIUS_PT * 0.3f, BIRD_RADIUS_PT * 0.18f, Palette.BG)

        if (scoredThisUpdate) scene.sound(SoundId.EAT)
        if (diedThisUpdate) {
            scene.sound(SoundId.LOSE)
            scene.haptic()
        }
    }

    public companion object {
        public const val PIPE_COUNT: Int = 4

        private const val PHYSICS_STEP_MS: Int = 4
        private const val MAX_STEPS_PER_UPDATE: Int = 32

        private const val GRAVITY_PT_PER_S2: Float = 620f
        private const val IMPULSE_VY_PT_PER_S: Float = -230f
        private const val PIPE_SPEED_PT_PER_S: Float = 85f
        private const val PIPE_SPACING_PT: Float = 220f
        private const val PIPE_WIDTH_PT: Float = 46f
        private const val GAP_FRACTION: Float = 0.46f
        private const val GAP_FRACTION_MIN: Float = 0.30f
        private const val PIPE_SPEED_MAX_PT_PER_S: Float = 135f
        private const val RAMP_SCORE: Int = 30
        private const val MAX_GAP_SHIFT_FRACTION: Float = 0.45f
        private const val MIN_GAP_HALF_PT: Float = 55f
        private const val GAP_MARGIN_PT: Float = 10f

        private const val BIRD_X_FRACTION: Float = 0.28f
        private const val BIRD_RADIUS_PT: Float = 12f
        private const val GROUND_HEIGHT_PT: Float = 24f

        /** Vertical strip reserved for the session HUD (score / best); doubles as the ceiling. */
        private const val HUD_TOP_PT: Float = 32f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "bird",
            titleKey = StringKey.TITLE_BIRD,
            tier = Tier.UNLOCKED,
            players = 1..1,
            orientation = Orientation.PORTRAIT,
            complexity = Complexity.M,
            resumable = false,
            factory = { BirdGame() },
            icon = { scene, size -> ArcadeIcons.chirp(scene, size) },
        )
    }
}
