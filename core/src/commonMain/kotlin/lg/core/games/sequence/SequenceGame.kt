package lg.core.games.sequence

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
import lg.core.util.Layout
import lg.core.util.Rect
import lg.core.util.Rng

/**
 * Four big colored pads laid out 2x2; the game shows a growing sequence by
 * lighting one pad at a time, then the player must tap it back.
 *
 * ### Round structure
 *
 * [sequence] is a fixed `IntArray(64)` of pad indices (0..3); [sequenceLength]
 * entries are active. A round starts by appending one random pad ([growSequence])
 * and entering [Phase.WATCH]: pads light up one at a time ([ON_MS] on,
 * [OFF_MS] off) via [update]. Once the whole sequence has been shown, the
 * game enters [Phase.INPUT] and waits for taps (ignored during [Phase.WATCH]).
 * Each correct tap advances [inputIndex]; completing the whole sequence
 * scores one point (rounds completed) and starts a new, longer round. A
 * wrong tap ends the game ([GameStatus.Over] with winner 0).
 */
public class SequenceGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    override var score: Int = 0
        private set

    override val wantsFrames: Boolean
        get() = status == GameStatus.Running && phase == Phase.WATCH

    private enum class Phase { WATCH, INPUT }

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private val boardRect = Rect()

    private val sequence = IntArray(MAX_ROUNDS)
    private var sequenceLength: Int = 0

    private var rng: Rng = Rng(0L)
    private var phase: Phase = Phase.WATCH

    // Watch-phase sub-state.
    private var watchIndex: Int = 0
    private var watchLit: Boolean = false
    private var watchTimerMs: Int = 0
    private var litPad: Int = -1

    // Input-phase state.
    private var inputIndex: Int = 0

    // Events from the most recent action, read (not mutated) by render().
    private var tappedPadThisFrame: Int = -1
    private var correctTapThisFrame: Boolean = false
    private var roundCompleteThisFrame: Boolean = false
    private var wrongTapThisFrame: Boolean = false

    override fun reset(seed: Long, players: Int) {
        rng = Rng(seed)
        score = 0
        sequenceLength = 0
        phase = Phase.WATCH
        watchIndex = 0
        watchLit = false
        watchTimerMs = 0
        litPad = -1
        inputIndex = 0
        tappedPadThisFrame = -1
        correctTapThisFrame = false
        roundCompleteThisFrame = false
        wrongTapThisFrame = false
        status = GameStatus.Running
        growSequence()
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        Layout.fitSquare(viewWidth, viewHeight, HUD_TOP_PT, boardRect)
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        // Sequence is tap-driven only (via onGesture); raw pointer events are ignored.
    }

    override fun onGesture(gesture: Gesture) {
        if (status != GameStatus.Running) return
        if (gesture.kind != GestureKind.TAP) return
        if (phase != Phase.INPUT) return // taps during playback are ignored
        val pad = padAt(gesture.x, gesture.y) ?: return
        handleTap(pad)
    }

    private fun handleTap(pad: Int) {
        tappedPadThisFrame = pad
        correctTapThisFrame = false
        roundCompleteThisFrame = false
        wrongTapThisFrame = false

        if (sequence[inputIndex] != pad) {
            wrongTapThisFrame = true
            status = GameStatus.Over(0)
            return
        }
        correctTapThisFrame = true
        inputIndex++
        if (inputIndex >= sequenceLength) {
            score++
            roundCompleteThisFrame = true
            growSequence()
            phase = Phase.WATCH
            watchIndex = 0
            watchLit = false
            watchTimerMs = 0
            litPad = -1
        }
    }

    private fun growSequence() {
        if (sequenceLength >= MAX_ROUNDS) return
        sequence[sequenceLength] = rng.nextInt(PAD_COUNT)
        sequenceLength++
    }

    override fun update(dtMs: Int): Boolean {
        if (status != GameStatus.Running) return false
        tappedPadThisFrame = -1
        correctTapThisFrame = false
        roundCompleteThisFrame = false
        wrongTapThisFrame = false
        if (phase != Phase.WATCH) return false

        watchTimerMs += dtMs
        if (watchLit) {
            if (watchTimerMs >= ON_MS) {
                watchLit = false
                watchTimerMs = 0
                litPad = -1
                watchIndex++
                if (watchIndex >= sequenceLength) {
                    phase = Phase.INPUT
                    inputIndex = 0
                }
            }
            return true
        } else {
            if (watchIndex < sequenceLength && watchTimerMs >= OFF_MS) {
                watchLit = true
                watchTimerMs = 0
                litPad = sequence[watchIndex]
            }
            return true
        }
    }

    /** Maps a point to a pad index (0..3) within [boardRect], or null if outside it. */
    private fun padAt(x: Float, y: Float): Int? {
        if (x < boardRect.x || x > boardRect.x + boardRect.w) return null
        if (y < boardRect.y || y > boardRect.y + boardRect.h) return null
        val col = if (x < boardRect.x + boardRect.w / 2f) 0 else 1
        val row = if (y < boardRect.y + boardRect.h / 2f) 0 else 1
        return row * 2 + col
    }

    // --- Test-only accessors (internal: exercised by SequenceRulesTest) ----

    internal fun debugSequenceValue(i: Int): Int = sequence[i]

    internal fun debugSequenceLength(): Int = sequenceLength

    internal fun debugIsInputPhase(): Boolean = phase == Phase.INPUT

    internal fun debugInputIndex(): Int = inputIndex

    /** Directly triggers a tap on [pad], honoring the same watch/input phase rules as [onGesture]. */
    internal fun debugTap(pad: Int) {
        if (status != GameStatus.Running || phase != Phase.INPUT) return
        handleTap(pad)
    }

    /** Fast-forwards the watch animation until input is accepted (bounded to avoid an infinite loop). */
    internal fun debugSkipToInput() {
        var guard = 0
        while (phase == Phase.WATCH && status == GameStatus.Running && guard < 100_000) {
            update(16)
            guard++
        }
    }

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)
        scene.rect(boardRect.x, boardRect.y, boardRect.w, boardRect.h, Palette.SURFACE, radius = boardRect.w * 0.04f)

        val gap = boardRect.w * PAD_GAP_FRACTION
        val padSize = (boardRect.w - gap * 3f) / 2f
        for (pad in 0 until PAD_COUNT) {
            val col = pad % 2
            val row = pad / 2
            val px = boardRect.x + gap + col * (padSize + gap)
            val py = boardRect.y + gap + row * (padSize + gap)
            val lit = (phase == Phase.WATCH && litPad == pad) ||
                (correctTapThisFrame && tappedPadThisFrame == pad)
            val alpha = if (lit) 1f else 0.45f
            scene.rect(px, py, padSize, padSize, padColor(pad), radius = padSize * 0.12f, alpha = alpha)
        }

        if (correctTapThisFrame) scene.sound(SoundId.EAT)
        if (roundCompleteThisFrame) scene.sound(SoundId.WIN)
        if (wrongTapThisFrame) {
            scene.sound(SoundId.LOSE)
            scene.haptic()
        }
    }

    private fun padColor(pad: Int): Int = when (pad) {
        0 -> Palette.P1
        1 -> Palette.P2
        2 -> Palette.OK
        else -> Palette.DANGER
    }

    public companion object {
        public const val PAD_COUNT: Int = 4
        private const val MAX_ROUNDS: Int = 64
        private const val ON_MS: Int = 450
        private const val OFF_MS: Int = 150
        private const val PAD_GAP_FRACTION: Float = 0.06f

        /** Vertical strip reserved for the session HUD (score / best). */
        private const val HUD_TOP_PT: Float = 32f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "sequence",
            titleKey = StringKey.TITLE_SEQUENCE,
            tier = Tier.UNLOCKED,
            players = 1..1,
            orientation = Orientation.PORTRAIT,
            complexity = Complexity.S,
            resumable = false,
            factory = { SequenceGame() },
            icon = { scene, size -> ArcadeIcons.colorEcho(scene, size) },
        )
    }
}
