package lg.core.games.g2048

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
import lg.core.scene.ArcadeIcons
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey
import lg.core.util.IntGrid
import lg.core.util.KvCodec
import lg.core.util.Layout
import lg.core.util.Rect
import lg.core.util.Rng

/** The four directions a swipe can slide the board in. */
internal enum class Direction {
    LEFT,
    RIGHT,
    UP,
    DOWN,
}

/**
 * Classic 4x4 tile-merging puzzle.
 *
 * ### Data layout
 *
 * [grid] stores tile *exponents* (0 = empty, `n` = value `2^n`), never the
 * displayed value itself, so merges are plain integer increments. A swipe
 * slides every row (LEFT/RIGHT) or column (UP/DOWN) toward one edge; tiles
 * are compacted and adjacent equal tiles merge, each tile merging **at most
 * once** per move (`lineMerged` tracks which destination slots already
 * absorbed a merge within the line currently being processed). A move that
 * does not change [grid] is ignored: no score, no spawn, no animation.
 *
 * After a valid move, one new tile is spawned on a random empty cell
 * (exponent 1 i.e. value 2 with 90% probability, exponent 2 i.e. value 4
 * with 10%) using [rng], and the game ends ([GameStatus.Over]) once no
 * empty cell and no adjacent equal pair remain. Reaching a `2048` tile
 * (exponent 11) sets [reached2048] once, for a one-time WIN sound, but does
 * not end the game.
 *
 * ### Animation without allocation
 *
 * A move mutates [grid] to its final, logical post-move state immediately
 * (so game-over/spawn/score logic never depends on animation timing).
 * Separately, [attemptMove] records up to [MAX_ANIM_TILES] "from cell -> to
 * cell" entries (parallel fixed arrays `animFrom{X,Y}`/`animTo{X,Y}` plus
 * the pre-merge exponent `animExp`, capped at 16 = every cell) into
 * preallocated arrays, and [animT] eases from 0 to 1 over
 * [ANIM_DURATION_MS]. `render` interpolates tile positions from those
 * arrays while animating, then falls back to drawing straight from [grid]
 * once the animation completes. A swipe received while still animating does
 * not interrupt it: it is queued in [pendingDirection] (a single slot - a
 * second swipe before the first is even applied simply replaces the queued
 * one) and applied on the [update] call that finishes the current
 * animation. This keeps replay fully deterministic regardless of how often
 * `render` is called mid-animation.
 */
public class Game2048 : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    override var score: Int = 0
        private set

    override val wantsFrames: Boolean
        get() = status == GameStatus.Running && animT < 1f

    private val grid = IntGrid(SIZE, SIZE)
    private var rng: Rng = Rng(0L)
    private var reached2048: Boolean = false

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private val boardRect = Rect()

    private var pendingDirection: Direction? = null

    // Reused scratch for one row/column while sliding; refilled every line.
    private val lineVal = IntArray(SIZE)
    private val lineMerged = BooleanArray(SIZE)

    // Fixed-capacity "from cell -> to cell" slide animation, at most one
    // entry per pre-move tile (16 cells max on a 4x4 board).
    private val animFromX = IntArray(MAX_ANIM_TILES)
    private val animFromY = IntArray(MAX_ANIM_TILES)
    private val animToX = IntArray(MAX_ANIM_TILES)
    private val animToY = IntArray(MAX_ANIM_TILES)
    private val animExp = IntArray(MAX_ANIM_TILES)
    private var animCount: Int = 0
    private var animT: Float = 1f

    // Events from the most recently attempted move, read (not mutated) by render().
    private var validMoveThisMove: Boolean = false
    private var mergedThisMove: Boolean = false
    private var wonThisMove: Boolean = false
    private var gameOverThisMove: Boolean = false
    private var scoreGainThisMove: Int = 0

    override fun reset(seed: Long, players: Int) {
        rng = Rng(seed)
        grid.fill(0)
        score = 0
        reached2048 = false
        pendingDirection = null
        animCount = 0
        animT = 1f
        validMoveThisMove = false
        mergedThisMove = false
        wonThisMove = false
        gameOverThisMove = false
        status = GameStatus.Running
        spawnRandomTile()
        spawnRandomTile()
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        // 2048 is swipe-driven only; raw pointer events are ignored.
    }

    override fun onGesture(gesture: Gesture) {
        if (status != GameStatus.Running) return
        val dir = when (gesture.kind) {
            GestureKind.SWIPE_UP -> Direction.UP
            GestureKind.SWIPE_DOWN -> Direction.DOWN
            GestureKind.SWIPE_LEFT -> Direction.LEFT
            GestureKind.SWIPE_RIGHT -> Direction.RIGHT
            else -> return
        }
        if (animT < 1f) {
            pendingDirection = dir
        } else {
            attemptMove(dir)
        }
    }

    override fun update(dtMs: Int): Boolean {
        if (status != GameStatus.Running) return false
        if (animT < 1f) {
            animT = (animT + dtMs / ANIM_DURATION_MS).coerceAtMost(1f)
            return true
        }
        val queued = pendingDirection
        if (queued != null) {
            pendingDirection = null
            return attemptMove(queued)
        }
        return false
    }

    // --- Move logic -----------------------------------------------------------

    private fun attemptMove(dir: Direction): Boolean {
        animCount = 0
        mergedThisMove = false
        validMoveThisMove = false
        wonThisMove = false
        gameOverThisMove = false
        scoreGainThisMove = 0

        val changed = when (dir) {
            Direction.LEFT -> moveLeft()
            Direction.RIGHT -> moveRight()
            Direction.UP -> moveUp()
            Direction.DOWN -> moveDown()
        }
        if (!changed) {
            animCount = 0
            return false
        }

        validMoveThisMove = true
        score += scoreGainThisMove
        spawnRandomTile()

        if (!reached2048 && hasTileAtLeast(WIN_EXPONENT)) {
            reached2048 = true
            wonThisMove = true
        }
        if (!hasAnyMove()) {
            status = GameStatus.Over(0)
            gameOverThisMove = true
        }
        // Skip the slide animation for the game-ending move: the session
        // stops calling update() once it sees Over, so an in-progress
        // animation would otherwise freeze at t=0 forever.
        animT = if (gameOverThisMove) 1f else 0f
        return true
    }

    private fun moveLeft(): Boolean {
        var changed = false
        for (y in 0 until SIZE) {
            lineVal.fill(0)
            lineMerged.fill(false)
            var writeX = 0
            for (x in 0 until SIZE) {
                val v = grid.get(x, y)
                if (v == 0) continue
                if (writeX > 0 && lineVal[writeX - 1] == v && !lineMerged[writeX - 1]) {
                    lineVal[writeX - 1] = v + 1
                    lineMerged[writeX - 1] = true
                    recordAnim(x, y, writeX - 1, y, v)
                    onMerged(v + 1)
                } else {
                    lineVal[writeX] = v
                    recordAnim(x, y, writeX, y, v)
                    writeX++
                }
            }
            for (x in 0 until SIZE) {
                if (grid.get(x, y) != lineVal[x]) changed = true
                grid.set(x, y, lineVal[x])
            }
        }
        return changed
    }

    private fun moveRight(): Boolean {
        var changed = false
        for (y in 0 until SIZE) {
            lineVal.fill(0)
            lineMerged.fill(false)
            var writeX = SIZE - 1
            for (x in SIZE - 1 downTo 0) {
                val v = grid.get(x, y)
                if (v == 0) continue
                if (writeX < SIZE - 1 && lineVal[writeX + 1] == v && !lineMerged[writeX + 1]) {
                    lineVal[writeX + 1] = v + 1
                    lineMerged[writeX + 1] = true
                    recordAnim(x, y, writeX + 1, y, v)
                    onMerged(v + 1)
                } else {
                    lineVal[writeX] = v
                    recordAnim(x, y, writeX, y, v)
                    writeX--
                }
            }
            for (x in 0 until SIZE) {
                if (grid.get(x, y) != lineVal[x]) changed = true
                grid.set(x, y, lineVal[x])
            }
        }
        return changed
    }

    private fun moveUp(): Boolean {
        var changed = false
        for (x in 0 until SIZE) {
            lineVal.fill(0)
            lineMerged.fill(false)
            var writeY = 0
            for (y in 0 until SIZE) {
                val v = grid.get(x, y)
                if (v == 0) continue
                if (writeY > 0 && lineVal[writeY - 1] == v && !lineMerged[writeY - 1]) {
                    lineVal[writeY - 1] = v + 1
                    lineMerged[writeY - 1] = true
                    recordAnim(x, y, x, writeY - 1, v)
                    onMerged(v + 1)
                } else {
                    lineVal[writeY] = v
                    recordAnim(x, y, x, writeY, v)
                    writeY++
                }
            }
            for (y in 0 until SIZE) {
                if (grid.get(x, y) != lineVal[y]) changed = true
                grid.set(x, y, lineVal[y])
            }
        }
        return changed
    }

    private fun moveDown(): Boolean {
        var changed = false
        for (x in 0 until SIZE) {
            lineVal.fill(0)
            lineMerged.fill(false)
            var writeY = SIZE - 1
            for (y in SIZE - 1 downTo 0) {
                val v = grid.get(x, y)
                if (v == 0) continue
                if (writeY < SIZE - 1 && lineVal[writeY + 1] == v && !lineMerged[writeY + 1]) {
                    lineVal[writeY + 1] = v + 1
                    lineMerged[writeY + 1] = true
                    recordAnim(x, y, x, writeY + 1, v)
                    onMerged(v + 1)
                } else {
                    lineVal[writeY] = v
                    recordAnim(x, y, x, writeY, v)
                    writeY--
                }
            }
            for (y in 0 until SIZE) {
                if (grid.get(x, y) != lineVal[y]) changed = true
                grid.set(x, y, lineVal[y])
            }
        }
        return changed
    }

    private fun onMerged(resultExponent: Int) {
        mergedThisMove = true
        scoreGainThisMove += pow2(resultExponent)
    }

    private fun recordAnim(fromX: Int, fromY: Int, toX: Int, toY: Int, exponent: Int) {
        if (animCount >= MAX_ANIM_TILES) return
        animFromX[animCount] = fromX
        animFromY[animCount] = fromY
        animToX[animCount] = toX
        animToY[animCount] = toY
        animExp[animCount] = exponent
        animCount++
    }

    private fun spawnRandomTile() {
        var emptyCount = 0
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                if (grid.get(x, y) == 0) emptyCount++
            }
        }
        if (emptyCount == 0) return
        var target = rng.nextInt(emptyCount)
        val exponent = if (rng.nextInt(10) < 9) 1 else 2
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                if (grid.get(x, y) != 0) continue
                if (target == 0) {
                    grid.set(x, y, exponent)
                    return
                }
                target--
            }
        }
    }

    private fun hasAnyMove(): Boolean {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val v = grid.get(x, y)
                if (v == 0) return true
                if (x < SIZE - 1 && grid.get(x + 1, y) == v) return true
                if (y < SIZE - 1 && grid.get(x, y + 1) == v) return true
            }
        }
        return false
    }

    private fun hasTileAtLeast(exponent: Int): Boolean {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                if (grid.get(x, y) >= exponent) return true
            }
        }
        return false
    }

    private fun pow2(exponent: Int): Int = 1 shl exponent

    // --- Rendering --------------------------------------------------------

    override fun render(scene: Scene) {
        Layout.fitSquare(viewWidth, viewHeight, HUD_TOP_PT, boardRect)
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)
        scene.rect(
            boardRect.x,
            boardRect.y,
            boardRect.w,
            boardRect.h,
            Palette.SURFACE,
            radius = boardRect.w * BOARD_RADIUS_FRACTION,
        )

        val cell = boardRect.w / SIZE
        val pad = cell * CELL_PADDING_FRACTION
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val cx = boardRect.x + x * cell
                val cy = boardRect.y + y * cell
                scene.rect(cx + pad, cy + pad, cell - 2 * pad, cell - 2 * pad, Palette.GRID, radius = pad)
            }
        }

        if (animT < 1f) {
            for (i in 0 until animCount) {
                val fx = boardRect.x + lerp(animFromX[i] * cell, animToX[i] * cell, animT)
                val fy = boardRect.y + lerp(animFromY[i] * cell, animToY[i] * cell, animT)
                drawTile(scene, fx + pad, fy + pad, cell - 2 * pad, animExp[i])
            }
        } else {
            for (y in 0 until SIZE) {
                for (x in 0 until SIZE) {
                    val v = grid.get(x, y)
                    if (v == 0) continue
                    val cx = boardRect.x + x * cell
                    val cy = boardRect.y + y * cell
                    drawTile(scene, cx + pad, cy + pad, cell - 2 * pad, v)
                }
            }
        }

        if (validMoveThisMove) scene.sound(SoundId.TICK)
        if (mergedThisMove) scene.sound(SoundId.EAT)
        if (wonThisMove) scene.sound(SoundId.WIN)
        if (gameOverThisMove) {
            scene.sound(SoundId.LOSE)
            scene.haptic()
        }
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    private fun drawTile(scene: Scene, x: Float, y: Float, size: Float, exponent: Int) {
        val colorIndex = Palette.TILE_0 + (exponent - 1).coerceIn(0, 11)
        scene.rect(x, y, size, size, colorIndex, radius = size * TILE_RADIUS_FRACTION)
        val value = pow2(exponent)
        val textColor = if (exponent <= LIGHT_TILE_MAX_EXPONENT) Palette.FG else Palette.BG
        val fontSize = size * fontSizeFraction(value)
        scene.number(x + size / 2f, y + size / 2f, value, textColor, fontSize, Align.CENTER)
    }

    private fun fontSizeFraction(value: Int): Float = when {
        value < 10 -> 0.5f
        value < 100 -> 0.42f
        value < 1000 -> 0.34f
        value < 10000 -> 0.28f
        else -> 0.22f
    }

    // --- Save / restore (see class KDoc) -----------------------------------

    /**
     * Encodes [grid], [score], [reached2048] and the exact [rng] state via
     * [KvCodec]. Only called from [lg.core.session.GameSession.persist] on
     * pause, not from any per-frame path, so the map/string allocation here
     * is fine despite the zero-allocation rule for `update`/`render`.
     */
    override fun snapshot(): String? {
        val map = LinkedHashMap<String, String>(SIZE * SIZE + 4)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                map["c${y * SIZE + x}"] = grid.get(x, y).toString()
            }
        }
        map["score"] = score.toString()
        map["won"] = if (reached2048) "1" else "0"
        map["rng"] = rng.state.toString()
        return KvCodec.encode(map)
    }

    override fun restore(data: String): Boolean {
        val map = KvCodec.decode(data)
        val newGrid = IntArray(SIZE * SIZE)
        for (i in 0 until SIZE * SIZE) {
            val v = map["c$i"]?.toIntOrNull() ?: return false
            if (v < 0) return false
            newGrid[i] = v
        }
        val newScore = map["score"]?.toIntOrNull() ?: return false
        if (newScore < 0) return false
        val wonStr = map["won"]
        if (wonStr != "0" && wonStr != "1") return false
        val rngState = map["rng"]?.toLongOrNull() ?: return false

        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                grid.set(x, y, newGrid[y * SIZE + x])
            }
        }
        score = newScore
        reached2048 = wonStr == "1"
        rng = Rng.fromState(rngState)
        status = GameStatus.Running
        pendingDirection = null
        animCount = 0
        animT = 1f
        validMoveThisMove = false
        mergedThisMove = false
        wonThisMove = false
        gameOverThisMove = false
        return true
    }

    // --- Test-only accessors (internal: exercised by Game2048RulesTest) ----

    internal fun debugSetCell(x: Int, y: Int, exponent: Int): Unit = grid.set(x, y, exponent)

    internal fun debugGetCell(x: Int, y: Int): Int = grid.get(x, y)

    internal fun debugReached2048(): Boolean = reached2048

    internal fun debugRngState(): Long = rng.state

    public companion object {
        public const val SIZE: Int = 4
        private const val MAX_ANIM_TILES: Int = SIZE * SIZE
        private const val ANIM_DURATION_MS: Float = 120f
        private const val WIN_EXPONENT: Int = 11
        private const val LIGHT_TILE_MAX_EXPONENT: Int = 2

        /** Vertical strip reserved for the session HUD (score / best). */
        private const val HUD_TOP_PT: Float = 32f
        private const val BOARD_RADIUS_FRACTION: Float = 0.04f
        private const val CELL_PADDING_FRACTION: Float = 0.06f
        private const val TILE_RADIUS_FRACTION: Float = 0.12f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "g2048",
            titleKey = StringKey.TITLE_2048,
            tier = Tier.FREE,
            players = 1..1,
            orientation = Orientation.PORTRAIT,
            complexity = Complexity.M,
            resumable = true,
            factory = { Game2048() },
            icon = { scene, size -> ArcadeIcons.fusion(scene, size) },
        )
    }
}
