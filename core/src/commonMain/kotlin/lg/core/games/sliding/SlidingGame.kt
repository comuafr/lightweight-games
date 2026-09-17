package lg.core.games.sliding

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
import lg.core.scene.Palette
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey
import lg.core.util.KvCodec
import lg.core.util.Layout
import lg.core.util.Rect
import lg.core.util.Rng

/**
 * 4x4 taquin (15-puzzle): tiles 1..15 plus one blank (`0`), flat-indexed
 * `row * SIZE + col` in [tiles]. [blankIndex] is cached for O(1) move checks.
 *
 * [reset] starts from the solved layout and applies [SHUFFLE_MOVES] random
 * legal moves (avoiding the immediate reverse of the previous move for
 * better mixing) via [Rng] — every intermediate position is reachable from
 * the solved one, so the result is always solvable without any parity math.
 * A guard re-shuffles further if the result happens to already be solved.
 *
 * A move slides the tile adjacent to the blank into it, either by tapping
 * that tile directly or by swiping: a swipe in direction *d* takes the
 * neighbor of the blank that lies in direction *d* from the blank and moves
 * it into the blank (so swiping up moves the tile above the blank down).
 * [score] counts successful moves. There is no time-driven animation, so
 * [wantsFrames] is always false.
 */
public class SlidingGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    override var score: Int = 0
        private set

    override val wantsFrames: Boolean = false

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private val boardRect = Rect()

    private val tiles = IntArray(CELL_COUNT)
    private var blankIndex: Int = CELL_COUNT - 1
    private var rng: Rng = Rng(0L)

    // Events from the most recent action, read (not mutated) by render().
    private var movedThisMove: Boolean = false
    private var solvedThisMove: Boolean = false

    override fun reset(seed: Long, players: Int) {
        rng = Rng(seed)
        for (i in 0 until CELL_COUNT - 1) tiles[i] = i + 1
        tiles[CELL_COUNT - 1] = 0
        blankIndex = CELL_COUNT - 1
        score = 0
        movedThisMove = false
        solvedThisMove = false
        status = GameStatus.Running

        var lastDirection = -1
        do {
            repeat(SHUFFLE_MOVES) {
                lastDirection = shuffleStep(lastDirection)
            }
        } while (isSolved())
    }

    /** Applies one random legal move from the blank's current position, avoiding undoing [avoidDirection]. */
    private fun shuffleStep(avoidDirection: Int): Int {
        val row = blankIndex / SIZE
        val col = blankIndex % SIZE

        var count = 0
        for (d in 0 until 4) {
            if (d != avoidDirection && neighborIndex(row, col, d) >= 0) count++
        }
        val chosen = if (count > 0) {
            pickDirection(row, col, count) { d -> d != avoidDirection }
        } else {
            // Every legal direction is the one being avoided: can't happen on a 4x4 board
            // (the blank always has >= 2 neighbors), but fall back to any legal move regardless.
            var total = 0
            for (d in 0 until 4) if (neighborIndex(row, col, d) >= 0) total++
            pickDirection(row, col, total) { true }
        }
        val target = neighborIndex(row, col, chosen)
        swapWithBlank(target)
        // The move that reverses `chosen` is the opposite direction.
        return oppositeDirection(chosen)
    }

    /** Picks the [pick]-th (0-based, via [rng]) direction among [count] candidates satisfying [allowed]. */
    private inline fun pickDirection(row: Int, col: Int, count: Int, allowed: (Int) -> Boolean): Int {
        var pick = rng.nextInt(count)
        for (d in 0 until 4) {
            if (!allowed(d) || neighborIndex(row, col, d) < 0) continue
            if (pick == 0) return d
            pick--
        }
        return -1
    }

    private fun oppositeDirection(d: Int): Int = when (d) {
        DIR_UP -> DIR_DOWN
        DIR_DOWN -> DIR_UP
        DIR_LEFT -> DIR_RIGHT
        else -> DIR_LEFT
    }

    /** Index of the blank's neighbor in direction [d] (see DIR_* constants), or -1 if out of bounds. */
    private fun neighborIndex(row: Int, col: Int, d: Int): Int {
        val nr = row + when (d) { DIR_UP -> -1; DIR_DOWN -> 1; else -> 0 }
        val nc = col + when (d) { DIR_LEFT -> -1; DIR_RIGHT -> 1; else -> 0 }
        if (nr < 0 || nr >= SIZE || nc < 0 || nc >= SIZE) return -1
        return nr * SIZE + nc
    }

    private fun swapWithBlank(targetIndex: Int) {
        tiles[blankIndex] = tiles[targetIndex]
        tiles[targetIndex] = 0
        blankIndex = targetIndex
    }

    private fun isSolved(): Boolean {
        for (i in 0 until CELL_COUNT - 1) {
            if (tiles[i] != i + 1) return false
        }
        return tiles[CELL_COUNT - 1] == 0
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        Layout.fitSquare(viewWidth, viewHeight, HUD_TOP_PT, boardRect)
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        // Sliding is tap/swipe-driven only (via onGesture); raw pointer events are ignored.
    }

    override fun onGesture(gesture: Gesture) {
        if (status != GameStatus.Running) return
        movedThisMove = false
        solvedThisMove = false
        when (gesture.kind) {
            GestureKind.TAP -> {
                val idx = cellAt(gesture.x, gesture.y) ?: return
                tryMoveTile(idx)
            }
            GestureKind.SWIPE_UP -> trySwipe(DIR_UP)
            GestureKind.SWIPE_DOWN -> trySwipe(DIR_DOWN)
            GestureKind.SWIPE_LEFT -> trySwipe(DIR_LEFT)
            GestureKind.SWIPE_RIGHT -> trySwipe(DIR_RIGHT)
            else -> Unit
        }
    }

    /** Swiping in direction [d] moves the blank's neighbor in that direction into the blank. */
    private fun trySwipe(d: Int) {
        val row = blankIndex / SIZE
        val col = blankIndex % SIZE
        val target = neighborIndex(row, col, d)
        if (target >= 0) tryMoveTile(target)
    }

    private fun tryMoveTile(idx: Int) {
        if (idx < 0 || idx >= CELL_COUNT) return
        if (!isAdjacentToBlank(idx)) return
        swapWithBlank(idx)
        score++
        movedThisMove = true
        if (isSolved()) {
            status = GameStatus.Over(0)
            solvedThisMove = true
        }
    }

    private fun isAdjacentToBlank(idx: Int): Boolean {
        val br = blankIndex / SIZE
        val bc = blankIndex % SIZE
        val r = idx / SIZE
        val c = idx % SIZE
        val manhattan = kotlin.math.abs(br - r) + kotlin.math.abs(bc - c)
        return manhattan == 1
    }

    private fun cellAt(x: Float, y: Float): Int? {
        if (boardRect.w <= 0f) return null
        if (x < boardRect.x || x > boardRect.x + boardRect.w) return null
        if (y < boardRect.y || y > boardRect.y + boardRect.h) return null
        val cell = boardRect.w / SIZE
        val col = ((x - boardRect.x) / cell).toInt().coerceIn(0, SIZE - 1)
        val row = ((y - boardRect.y) / cell).toInt().coerceIn(0, SIZE - 1)
        return row * SIZE + col
    }

    override fun update(dtMs: Int): Boolean {
        movedThisMove = false
        solvedThisMove = false
        return false
    }

    // --- Save / restore ---------------------------------------------------
    // Only called from GameSession.persist() on pause, never per-frame, so
    // the map/string allocation here is fine (see docs/ARCHITECTURE.md).

    override fun snapshot(): String? {
        val map = LinkedHashMap<String, String>(CELL_COUNT + 2)
        for (i in 0 until CELL_COUNT) map["t$i"] = tiles[i].toString()
        map["score"] = score.toString()
        map["rng"] = rng.state.toString()
        return KvCodec.encode(map)
    }

    override fun restore(data: String): Boolean {
        val map = KvCodec.decode(data)
        val newTiles = IntArray(CELL_COUNT)
        val seen = BooleanArray(CELL_COUNT)
        for (i in 0 until CELL_COUNT) {
            val v = map["t$i"]?.toIntOrNull() ?: return false
            if (v < 0 || v >= CELL_COUNT) return false
            if (seen[v]) return false // every value 0..15 must appear exactly once
            seen[v] = true
            newTiles[i] = v
        }
        val newScore = map["score"]?.toIntOrNull() ?: return false
        if (newScore < 0) return false
        val rngState = map["rng"]?.toLongOrNull() ?: return false

        for (i in 0 until CELL_COUNT) {
            tiles[i] = newTiles[i]
            if (newTiles[i] == 0) blankIndex = i
        }
        score = newScore
        rng = Rng.fromState(rngState)
        status = GameStatus.Running
        movedThisMove = false
        solvedThisMove = false
        return true
    }

    // --- Test-only accessors (internal: exercised by SlidingRulesTest) ----

    internal fun debugTile(idx: Int): Int = tiles[idx]
    internal fun debugBlankIndex(): Int = blankIndex
    internal fun debugIsSolved(): Boolean = isSolved()
    internal fun debugRngState(): Long = rng.state

    /** Forces a known board arrangement for tests; [grid] must be a permutation of 0..15. */
    internal fun debugSetGrid(grid: IntArray) {
        for (i in 0 until CELL_COUNT) {
            tiles[i] = grid[i]
            if (grid[i] == 0) blankIndex = i
        }
    }

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)
        scene.rect(boardRect.x, boardRect.y, boardRect.w, boardRect.h, Palette.SURFACE, radius = boardRect.w * 0.02f)

        val cell = boardRect.w / SIZE
        val pad = cell * TILE_PADDING_FRACTION
        for (row in 0 until SIZE) {
            for (col in 0 until SIZE) {
                val idx = row * SIZE + col
                val value = tiles[idx]
                val cx = boardRect.x + col * cell
                val cy = boardRect.y + row * cell
                if (value == 0) continue
                scene.rect(cx + pad, cy + pad, cell - 2 * pad, cell - 2 * pad, Palette.TILE_1, radius = pad)
                scene.number(cx + cell / 2f, cy + cell / 2f, value, Palette.FG, cell * 0.4f, Align.CENTER)
            }
        }

        if (movedThisMove) scene.sound(SoundId.TICK)
        if (solvedThisMove) {
            scene.sound(SoundId.WIN)
            scene.haptic()
        }
    }

    public companion object {
        public const val SIZE: Int = 4
        public const val CELL_COUNT: Int = SIZE * SIZE

        private const val SHUFFLE_MOVES: Int = 200
        private const val TILE_PADDING_FRACTION: Float = 0.05f

        private const val DIR_UP: Int = 0
        private const val DIR_DOWN: Int = 1
        private const val DIR_LEFT: Int = 2
        private const val DIR_RIGHT: Int = 3

        /** Vertical strip reserved for the session HUD (score / best). */
        private const val HUD_TOP_PT: Float = 32f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "sliding",
            titleKey = StringKey.TITLE_SLIDING,
            tier = Tier.UNLOCKED,
            players = 1..1,
            orientation = Orientation.ANY,
            complexity = Complexity.S,
            resumable = true,
            factory = { SlidingGame() },
            icon = { scene, size -> drawIcon(scene, size) },
        )

        private fun drawIcon(scene: Scene, size: Float) {
            val gap = size * 0.08f
            val cell = (size - gap * 3f) / 2f
            scene.rect(gap, gap, cell, cell, Palette.TILE_1, radius = cell * 0.1f)
            scene.rect(gap * 2 + cell, gap, cell, cell, Palette.TILE_1, radius = cell * 0.1f)
            scene.rect(gap, gap * 2 + cell, cell, cell, Palette.TILE_1, radius = cell * 0.1f)
            scene.number(gap + cell / 2f, gap + cell / 2f, 1, Palette.FG, cell * 0.5f, Align.CENTER)
            scene.number(gap * 2 + cell + cell / 2f, gap + cell / 2f, 2, Palette.FG, cell * 0.5f, Align.CENTER)
            scene.number(gap + cell / 2f, gap * 2 + cell + cell / 2f, 3, Palette.FG, cell * 0.5f, Align.CENTER)
        }
    }
}
