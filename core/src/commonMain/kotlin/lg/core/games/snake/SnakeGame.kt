package lg.core.games.snake

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
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey
import lg.core.util.Rng

/** Four cardinal directions a snake head can move in. */
internal enum class Direction(public val dx: Int, public val dy: Int) {
    UP(0, -1),
    DOWN(0, 1),
    LEFT(-1, 0),
    RIGHT(1, 0),
    ;

    public fun isOppositeOf(other: Direction): Boolean =
        dx == -other.dx && dy == -other.dy
}

/**
 * Classic grid snake: fixed tick, four-direction swipes, walls kill (no wrap).
 * The board is [COLS] cells wide; the row count is derived from the current
 * aspect ratio (at least [MIN_ROWS]) so the board fills the available area.
 *
 * The body is stored in a fixed-capacity ring buffer ([bodyX]/[bodyY]) sized
 * for the largest possible board, so growth never allocates.
 */
public class SnakeGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    override var score: Int = 0
        private set

    override val wantsFrames: Boolean
        get() = status == GameStatus.Running

    // --- Board geometry, recomputed on resize -------------------------------
    private var cols: Int = COLS
    private var rows: Int = MIN_ROWS
    private var cellSize: Float = 1f
    private var boardOriginX: Float = 0f
    private var boardOriginY: Float = 0f
    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f

    // --- Body ring buffer -----------------------------------------------------
    private val capacity: Int = COLS * MAX_ROWS
    private val bodyX: IntArray = IntArray(capacity)
    private val bodyY: IntArray = IntArray(capacity)
    private var headIdx: Int = 0
    private var tailIdx: Int = 0
    private var length: Int = 0

    private var direction: Direction = Direction.RIGHT
    private var nextDirection: Direction = Direction.RIGHT

    private var foodX: Int = -1
    private var foodY: Int = -1

    private var rng: Rng = Rng(0L)
    private var tickAccumulatorMs: Int = 0

    // Events from the most recently processed tick, read (not mutated) by render().
    private var ateThisTick: Boolean = false
    private var diedThisTick: Boolean = false

    override fun reset(seed: Long, players: Int) {
        rng = Rng(seed)
        cols = COLS
        rows = gridRowsFor(viewWidth, viewHeight)
        layoutBoard()
        score = 0
        tickAccumulatorMs = 0
        direction = Direction.RIGHT
        nextDirection = Direction.RIGHT
        ateThisTick = false
        diedThisTick = false

        val startX = cols / 2
        val startY = rows / 2
        length = 3
        tailIdx = 0
        headIdx = length - 1
        // Lay the initial body out behind the head along the starting direction
        // (RIGHT), so the first step extends past the tail instead of colliding
        // with it: index 0 = tail (lowest x), index length-1 = head (highest x).
        for (i in 0 until length) {
            val offsetFromHead = length - 1 - i
            bodyX[i] = startX - offsetFromHead
            bodyY[i] = startY
        }

        spawnFood()
        status = GameStatus.Running
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        if (status != GameStatus.Running) {
            // Between games the grid follows the screen; during a game it is frozen (rotation only rescales).
            rows = gridRowsFor(width, height)
        }
        layoutBoard()
    }

    /** Rows that fit the view for a fixed column count, keeping cells square and the board on screen. */
    private fun gridRowsFor(width: Float, height: Float): Int {
        if (width <= 0f || height <= 0f) return MIN_ROWS
        val boardH = (height - HUD_TOP_PT).coerceAtLeast(1f)
        val cell = minOf(width / cols, boardH / MIN_ROWS)
        return (boardH / cell).toInt().coerceIn(MIN_ROWS, MAX_ROWS)
    }

    /** Scales the fixed grid into the current view, centred, below the HUD strip. */
    private fun layoutBoard() {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val boardH = (viewHeight - HUD_TOP_PT).coerceAtLeast(1f)
        cellSize = minOf(viewWidth / cols, boardH / rows)
        boardOriginX = (viewWidth - cols * cellSize) / 2f
        boardOriginY = HUD_TOP_PT + (boardH - rows * cellSize) / 2f
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        // Snake is swipe-driven only; raw pointer events are ignored.
    }

    override fun onGesture(gesture: Gesture) {
        if (status != GameStatus.Running) return
        val desired = when (gesture.kind) {
            GestureKind.SWIPE_UP -> Direction.UP
            GestureKind.SWIPE_DOWN -> Direction.DOWN
            GestureKind.SWIPE_LEFT -> Direction.LEFT
            GestureKind.SWIPE_RIGHT -> Direction.RIGHT
            else -> return
        }
        // Compare against the already-queued direction (not the current one) so
        // two swipes issued within the same tick window cannot combine into an
        // effective 180-degree reversal.
        if (!desired.isOppositeOf(nextDirection)) {
            nextDirection = desired
        }
    }

    override fun update(dtMs: Int): Boolean {
        if (status != GameStatus.Running) return false
        tickAccumulatorMs += dtMs
        val interval = tickIntervalMs()
        var stepped = false
        while (tickAccumulatorMs >= interval && status == GameStatus.Running) {
            tickAccumulatorMs -= interval
            step()
            stepped = true
        }
        return stepped
    }

    private fun tickIntervalMs(): Int = (BASE_TICK_MS - score * TICK_DECAY_PER_POINT).coerceIn(MIN_TICK_MS, BASE_TICK_MS)

    private fun step() {
        ateThisTick = false
        diedThisTick = false

        direction = nextDirection
        val nx = bodyX[headIdx] + direction.dx
        val ny = bodyY[headIdx] + direction.dy

        if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
            die()
            return
        }

        val willGrow = nx == foodX && ny == foodY
        if (occupiesBody(nx, ny, excludeTail = !willGrow)) {
            die()
            return
        }

        // Head advances in the same ring direction (ringNext) as the initial
        // fill lays segments out (tail at the low index, head at the high
        // index) and as tail removal below, so the active segment range is
        // always the contiguous ringNext-walk from tailIdx to headIdx.
        val newHeadIdx = ringNext(headIdx)
        bodyX[newHeadIdx] = nx
        bodyY[newHeadIdx] = ny
        headIdx = newHeadIdx
        if (willGrow) {
            length++
            score++
            ateThisTick = true
            spawnFood()
        } else {
            tailIdx = ringNext(tailIdx)
        }
    }

    private fun die() {
        status = GameStatus.Over(0)
        diedThisTick = true
    }

    private fun occupiesBody(x: Int, y: Int, excludeTail: Boolean): Boolean {
        var idx = tailIdx
        for (i in 0 until length) {
            val isTail = i == 0
            if (!(excludeTail && isTail) && bodyX[idx] == x && bodyY[idx] == y) return true
            idx = ringNext(idx)
        }
        return false
    }

    private fun ringNext(i: Int): Int = if (i + 1 == capacity) 0 else i + 1

    private fun spawnFood() {
        val totalCells = cols * rows
        if (length >= totalCells) {
            foodX = -1
            foodY = -1
            return
        }
        var attempts = 0
        while (attempts < MAX_FOOD_ATTEMPTS) {
            val fx = rng.nextInt(cols)
            val fy = rng.nextInt(rows)
            if (!occupiesBody(fx, fy, excludeTail = false)) {
                foodX = fx
                foodY = fy
                return
            }
            attempts++
        }
        // Fallback: linear scan for a free cell (board nearly full).
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                if (!occupiesBody(x, y, excludeTail = false)) {
                    foodX = x
                    foodY = y
                    return
                }
            }
        }
        foodX = -1
        foodY = -1
    }

    /** Force the food position for deterministic tests. Visible within the module only. */
    internal fun debugPlaceFood(x: Int, y: Int) {
        foodX = x
        foodY = y
    }

    internal fun debugHeadX(): Int = bodyX[headIdx]

    internal fun debugHeadY(): Int = bodyY[headIdx]

    internal fun debugLength(): Int = length

    internal fun debugRows(): Int = rows

    internal fun debugCols(): Int = cols

    override fun render(scene: Scene) {
        val bg = viewWidth.coerceAtLeast(0f)
        scene.rect(0f, 0f, bg, viewHeight.coerceAtLeast(0f), Palette.BG)
        scene.rect(boardOriginX, boardOriginY, cols * cellSize, rows * cellSize, Palette.SURFACE)

        var idx = tailIdx
        for (i in 0 until length) {
            val isHead = i == length - 1
            val px = boardOriginX + bodyX[idx] * cellSize
            val py = boardOriginY + bodyY[idx] * cellSize
            scene.rect(
                px + BODY_INSET,
                py + BODY_INSET,
                cellSize - 2 * BODY_INSET,
                cellSize - 2 * BODY_INSET,
                if (isHead) Palette.ACCENT else Palette.P1,
                radius = cellSize * 0.25f,
            )
            idx = ringNext(idx)
        }

        if (foodX >= 0 && foodY >= 0) {
            val fx = boardOriginX + foodX * cellSize + cellSize / 2f
            val fy = boardOriginY + foodY * cellSize + cellSize / 2f
            scene.circle(fx, fy, cellSize * 0.35f, Palette.DANGER)
        }


        if (ateThisTick) scene.sound(SoundId.EAT)
        if (diedThisTick) {
            scene.sound(SoundId.LOSE)
            scene.haptic()
        }
    }

    public companion object {
        public const val COLS: Int = 20
        public const val MIN_ROWS: Int = 20
        public const val MAX_ROWS: Int = 64

        private const val BASE_TICK_MS: Int = 160
        private const val MIN_TICK_MS: Int = 70
        private const val TICK_DECAY_PER_POINT: Int = 5
        private const val MAX_FOOD_ATTEMPTS: Int = 64
        private const val BODY_INSET: Float = 1f
        /** Vertical strip reserved for the session HUD (score / best). */
        private const val HUD_TOP_PT: Float = 32f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "snake",
            titleKey = StringKey.TITLE_SNAKE,
            tier = Tier.FREE,
            players = 1..1,
            orientation = Orientation.ANY,
            complexity = Complexity.S,
            resumable = false,
            factory = { SnakeGame() },
            icon = { scene, size -> drawIcon(scene, size) },
        )

        private fun drawIcon(scene: Scene, size: Float) {
            val cell = size / 6f
            scene.rect(size * 0.1f, size * 0.45f, size * 0.35f, cell, Palette.P1, radius = cell * 0.3f)
            scene.rect(size * 0.35f, size * 0.45f, size * 0.35f, cell, Palette.P1, radius = cell * 0.3f)
            scene.rect(size * 0.6f, size * 0.25f, cell, size * 0.25f, Palette.ACCENT, radius = cell * 0.3f)
            scene.circle(size * 0.75f, size * 0.2f, cell * 0.6f, Palette.DANGER)
        }
    }
}
