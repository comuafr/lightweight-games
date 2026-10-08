package lg.core.games.pairs

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
 * 4x4 memory-matching board: 8 symbol pairs, shuffled with [Rng].
 *
 * [cardPairId] holds each card's pair id (0..7, each value appears twice);
 * [cardState] holds [STATE_HIDDEN]/[STATE_FACE_UP]/[STATE_MATCHED] per card,
 * flat-indexed `row * COLS + col`.
 *
 * A tap on a hidden card flips it. With one card face up, a second tap flips
 * a second card and immediately resolves: a match keeps both face up (score
 * for the current player, turn stays); a mismatch enters [waitingMismatch]
 * for [MISMATCH_MS] (both stay visible, `wantsFrames` true), after which
 * they flip back and the turn passes in 2-player mode. Any tap while
 * [waitingMismatch] resolves it immediately instead of flipping a new card.
 *
 * `score` (the HUD field) means different things depending on player count,
 * documented at [score]'s assignment sites: for 1 player it is
 * `pairsFound * 10 - moves` (clamped to >= 0, lower moves is better); for 2
 * players it is the total pairs found by both players combined.
 */
public class PairsGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    override var score: Int = 0
        private set

    override val wantsFrames: Boolean
        get() = status == GameStatus.Running && waitingMismatch

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private val boardRect = Rect()

    private val cardPairId = IntArray(CARD_COUNT)
    private val cardState = IntArray(CARD_COUNT)

    private var rng: Rng = Rng(0L)
    private var playerCount: Int = 1
    private var currentPlayer: Int = 1
    private val pairsFound = IntArray(3) // index 1 = player 1, index 2 = player 2

    private var flippedA: Int = -1
    private var flippedB: Int = -1
    private var waitingMismatch: Boolean = false
    private var mismatchTimerMs: Int = 0
    private var movesCount: Int = 0
    private var matchedPairsTotal: Int = 0

    // Events from the most recent action, read (not mutated) by render().
    private var flipThisFrame: Boolean = false
    private var matchThisFrame: Boolean = false
    private var mismatchThisFrame: Boolean = false
    private var wonThisFrame: Boolean = false

    override fun reset(seed: Long, players: Int) {
        rng = Rng(seed)
        playerCount = players.coerceIn(1, 2)
        currentPlayer = 1
        pairsFound.fill(0)
        movesCount = 0
        matchedPairsTotal = 0
        flippedA = -1
        flippedB = -1
        waitingMismatch = false
        mismatchTimerMs = 0
        flipThisFrame = false
        matchThisFrame = false
        mismatchThisFrame = false
        wonThisFrame = false
        score = 0
        status = GameStatus.Running

        val ids = IntArray(CARD_COUNT)
        for (i in 0 until CARD_COUNT) ids[i] = i / 2
        rng.shuffle(ids)
        for (i in 0 until CARD_COUNT) {
            cardPairId[i] = ids[i]
            cardState[i] = STATE_HIDDEN
        }
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        Layout.fitSquare(viewWidth, viewHeight, HUD_TOP_PT, boardRect)
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        // Pairs is tap-driven only (via onGesture); raw pointer events are ignored.
    }

    override fun onGesture(gesture: Gesture) {
        if (status != GameStatus.Running) return
        if (gesture.kind != GestureKind.TAP) return
        val idx = cellAt(gesture.x, gesture.y) ?: return
        handleTap(idx)
    }

    private fun handleTap(idx: Int) {
        flipThisFrame = false
        matchThisFrame = false
        mismatchThisFrame = false
        wonThisFrame = false

        if (waitingMismatch) {
            resolveMismatch()
            return
        }
        if (cardState[idx] != STATE_HIDDEN) return

        if (flippedA == -1) {
            flippedA = idx
            cardState[idx] = STATE_FACE_UP
            flipThisFrame = true
            return
        }
        if (idx == flippedA) return

        flippedB = idx
        cardState[idx] = STATE_FACE_UP
        flipThisFrame = true
        movesCount++

        if (cardPairId[flippedA] == cardPairId[flippedB]) {
            cardState[flippedA] = STATE_MATCHED
            cardState[flippedB] = STATE_MATCHED
            pairsFound[currentPlayer]++
            matchedPairsTotal++
            matchThisFrame = true
            flippedA = -1
            flippedB = -1
            updateScore()
            if (matchedPairsTotal >= PAIR_COUNT) finishGame()
        } else {
            mismatchThisFrame = true
            waitingMismatch = true
            mismatchTimerMs = 0
        }
    }

    private fun resolveMismatch() {
        if (flippedA != -1) cardState[flippedA] = STATE_HIDDEN
        if (flippedB != -1) cardState[flippedB] = STATE_HIDDEN
        flippedA = -1
        flippedB = -1
        waitingMismatch = false
        mismatchTimerMs = 0
        if (playerCount == 2) currentPlayer = if (currentPlayer == 1) 2 else 1
    }

    private fun finishGame() {
        status = if (playerCount == 1) {
            GameStatus.Over(0)
        } else {
            val winner = when {
                pairsFound[1] > pairsFound[2] -> 1
                pairsFound[2] > pairsFound[1] -> 2
                else -> 0
            }
            GameStatus.Over(winner)
        }
        wonThisFrame = true
    }

    private fun updateScore() {
        score = if (playerCount == 1) {
            (pairsFound[1] * 10 - movesCount).coerceAtLeast(0)
        } else {
            pairsFound[1] + pairsFound[2]
        }
    }

    override fun update(dtMs: Int): Boolean {
        if (status != GameStatus.Running) return false
        flipThisFrame = false
        matchThisFrame = false
        mismatchThisFrame = false
        wonThisFrame = false
        if (!waitingMismatch) return false

        mismatchTimerMs += dtMs
        if (mismatchTimerMs >= MISMATCH_MS) {
            resolveMismatch()
            return true
        }
        return false
    }

    private fun cellAt(x: Float, y: Float): Int? {
        if (x < boardRect.x || x > boardRect.x + boardRect.w) return null
        if (y < boardRect.y || y > boardRect.y + boardRect.h) return null
        val cell = boardRect.w / COLS
        val col = ((x - boardRect.x) / cell).toInt().coerceIn(0, COLS - 1)
        val row = ((y - boardRect.y) / cell).toInt().coerceIn(0, ROWS - 1)
        return row * COLS + col
    }

    // --- Test-only accessors (internal: exercised by PairsRulesTest) ----

    internal fun debugCardPairId(idx: Int): Int = cardPairId[idx]
    internal fun debugCardState(idx: Int): Int = cardState[idx]
    internal fun debugTap(idx: Int): Unit = handleTap(idx)
    internal fun debugMovesCount(): Int = movesCount
    internal fun debugPairsFound(player: Int): Int = pairsFound[player]
    internal fun debugCurrentPlayer(): Int = currentPlayer
    internal fun debugWaitingMismatch(): Boolean = waitingMismatch

    /** Forces a known, deterministic card arrangement for tests (bypasses the shuffle). */
    internal fun debugSetPairIds(ids: IntArray) {
        for (i in 0 until CARD_COUNT) cardPairId[i] = ids[i]
    }

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)
        scene.rect(boardRect.x, boardRect.y, boardRect.w, boardRect.h, Palette.SURFACE, radius = boardRect.w * 0.03f)

        val cell = boardRect.w / COLS
        val pad = cell * CARD_PADDING_FRACTION
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val idx = row * COLS + col
                val cx = boardRect.x + col * cell
                val cy = boardRect.y + row * cell
                val faceUp = cardState[idx] != STATE_HIDDEN
                val bg = if (cardState[idx] == STATE_MATCHED) Palette.OK else Palette.GRID
                scene.rect(cx + pad, cy + pad, cell - 2 * pad, cell - 2 * pad, bg, radius = pad)
                if (faceUp) {
                    drawSymbol(scene, cardPairId[idx], cx + cell / 2f, cy + cell / 2f, cell - 4 * pad)
                }
            }
        }

        if (playerCount == 2) {
            val dotY = boardRect.y - HUD_TOP_PT / 2f
            val dotColor = if (currentPlayer == 1) Palette.P1 else Palette.P2
            scene.circle(boardRect.x + boardRect.w / 2f, dotY.coerceAtLeast(6f), 5f, dotColor)
        }

        if (flipThisFrame) scene.sound(SoundId.TICK)
        if (matchThisFrame) scene.sound(SoundId.EAT)
        if (mismatchThisFrame) scene.sound(SoundId.HIT)
        if (wonThisFrame) {
            scene.sound(SoundId.WIN)
            scene.haptic()
        }
    }

    private fun drawSymbol(scene: Scene, pairId: Int, cx: Float, cy: Float, size: Float) {
        val color = Palette.TILE_0 + (pairId % TILE_COLOR_COUNT)
        val r = size / 2f
        when (pairId % 8) {
            0 -> scene.circle(cx, cy, r * 0.8f, color)
            1 -> scene.rect(cx - r * 0.7f, cy - r * 0.7f, r * 1.4f, r * 1.4f, color, radius = r * 0.12f)
            2 -> {
                scene.line(cx, cy - r, cx - r, cy + r * 0.7f, color, width = size * 0.1f)
                scene.line(cx, cy - r, cx + r, cy + r * 0.7f, color, width = size * 0.1f)
                scene.line(cx - r, cy + r * 0.7f, cx + r, cy + r * 0.7f, color, width = size * 0.1f)
            }
            3 -> scene.arc(cx, cy, r * 0.75f, 0f, 359f, color)
            4 -> {
                scene.line(cx, cy - r, cx + r, cy, color, width = size * 0.1f)
                scene.line(cx + r, cy, cx, cy + r, color, width = size * 0.1f)
                scene.line(cx, cy + r, cx - r, cy, color, width = size * 0.1f)
                scene.line(cx - r, cy, cx, cy - r, color, width = size * 0.1f)
            }
            5 -> {
                scene.line(cx - r, cy, cx + r, cy, color, width = size * 0.16f)
                scene.line(cx, cy - r, cx, cy + r, color, width = size * 0.16f)
            }
            6 -> {
                scene.rect(cx - r * 0.8f, cy - r * 0.5f, r * 1.6f, size * 0.16f, color)
                scene.rect(cx - r * 0.8f, cy + r * 0.05f, r * 1.6f, size * 0.16f, color)
                scene.rect(cx - r * 0.8f, cy + r * 0.6f - size * 0.16f, r * 1.6f, size * 0.16f, color)
            }
            else -> {
                scene.circle(cx - r * 0.4f, cy - r * 0.4f, r * 0.22f, color)
                scene.circle(cx + r * 0.4f, cy - r * 0.4f, r * 0.22f, color)
                scene.circle(cx - r * 0.4f, cy + r * 0.4f, r * 0.22f, color)
                scene.circle(cx + r * 0.4f, cy + r * 0.4f, r * 0.22f, color)
                scene.circle(cx, cy, r * 0.22f, color)
            }
        }
    }

    public companion object {
        public const val COLS: Int = 4
        public const val ROWS: Int = 4
        public const val CARD_COUNT: Int = COLS * ROWS
        public const val PAIR_COUNT: Int = CARD_COUNT / 2

        public const val STATE_HIDDEN: Int = 0
        public const val STATE_FACE_UP: Int = 1
        public const val STATE_MATCHED: Int = 2

        private const val MISMATCH_MS: Int = 700
        private const val CARD_PADDING_FRACTION: Float = 0.08f
        private const val TILE_COLOR_COUNT: Int = 8

        /** Vertical strip reserved for the session HUD (score / best). */
        private const val HUD_TOP_PT: Float = 32f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "pairs",
            titleKey = StringKey.TITLE_PAIRS,
            tier = Tier.UNLOCKED,
            players = 1..2,
            orientation = Orientation.ANY,
            complexity = Complexity.S,
            resumable = false,
            factory = { PairsGame() },
            icon = { scene, size -> ArcadeIcons.twins(scene, size) },
        )
    }
}
