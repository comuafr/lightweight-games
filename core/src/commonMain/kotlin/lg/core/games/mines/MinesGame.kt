package lg.core.games.mines

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
import lg.core.util.KvCodec
import lg.core.util.Layout
import lg.core.util.Rect
import lg.core.util.Rng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Classic Minesweeper in three levels ([Difficulty]): a bigger board with more mines each time. The player
 * picks the level on the in-game "choosing" screen shown at every start, like the nonogram.
 *
 * ### Data layout
 *
 * [mines], [revealed] and [flagged] are flat boolean arrays (`idx = row * size + col`) allocated once for
 * the biggest level ([MAX_CELLS]); only the first `size * size` cells are used. [adjacentCount] caches the number of
 * neighboring mines per cell, computed once mines are placed.
 *
 * ### First tap is always safe
 *
 * Mines are **not** placed by [reset]; the first TAP calls [placeMines],
 * excluding the tapped cell and its 8 neighbors, using the preallocated
 * [candidateScratch] array (a partial Fisher-Yates shuffle over it) so no
 * list/array is allocated at tap time.
 *
 * ### Flood fill
 *
 * Revealing a zero-adjacency cell flood-fills outward using [floodStack] (a
 * preallocated index stack) and [floodQueued] (a preallocated "already
 * pushed this fill" marker, cleared at the start of every fill) — no
 * recursion, no allocation.
 *
 * ### Chording
 *
 * Tapping an already-revealed numbered cell whose adjacent flag count
 * equals its [adjacentCount] reveals every remaining (non-flagged)
 * neighbor in one action ([tryChord]).
 *
 * Winning ([status] becomes [GameStatus.Over] with winner `0`, like every
 * other single-player game here) happens once every non-mine cell is
 * revealed; hitting a mine ends the game the same way, additionally
 * revealing every mine for the final render.
 */
public class MinesGame : Game {
    override val meta: GameMeta get() = META

    override var status: GameStatus = GameStatus.Idle
        private set

    override var score: Int = 0
        private set

    /** True only during the chain of explosions that follows a hit mine; a turn-based game otherwise. */
    override val wantsFrames: Boolean get() = exploding

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private val boardRect = Rect()

    private var rng: Rng = Rng(0L)
    private var firstTapDone: Boolean = false

    /** True while the level-choice screen is shown (at every start); the board is not played yet. */
    private var choosing: Boolean = true
    private var difficulty: Difficulty = Difficulty.EASY

    // The board of the chosen level (the easy one until a level is picked).
    private var size: Int = Difficulty.EASY.size
    private var mineCount: Int = Difficulty.EASY.mines
    private val cellCount: Int get() = size * size

    private val mines = BooleanArray(MAX_CELLS)
    private val revealed = BooleanArray(MAX_CELLS)
    private val flagged = BooleanArray(MAX_CELLS)
    private val adjacentCount = IntArray(MAX_CELLS)
    private var revealedCount: Int = 0
    private var explodedIndex: Int = -1
    private var lost: Boolean = false
    private var won: Boolean = false

    // The chain of explosions after a hit mine: the mine the player hit goes off first, then every other mine,
    // nearest first, one after the other. The game stays Running (input ignored) until the last one is shown.
    private var exploding: Boolean = false
    private val explosionOrder = IntArray(MAX_MINES)
    private val explosionRank = IntArray(MAX_CELLS)
    private var explosionTotal: Int = 0
    private var explosionsShown: Int = 0
    private var explosionClockMs: Int = 0
    private var explosionStartPending: Boolean = false

    // Reused scratch buffers; see the class KDoc.
    private val candidateScratch = IntArray(MAX_CELLS)
    private val floodStack = IntArray(MAX_CELLS)
    private val floodQueued = BooleanArray(MAX_CELLS)

    // Events from the most recent action, read (not mutated) by render().
    private var revealedSomethingThisMove: Boolean = false
    private var explosionThisUpdate: Boolean = false
    private var firstExplosionThisUpdate: Boolean = false
    private var lostThisUpdate: Boolean = false
    private var flagToggledThisMove: Boolean = false
    private var wonThisMove: Boolean = false

    override fun reset(seed: Long, players: Int) {
        rng = Rng(seed)
        choosing = true
        difficulty = Difficulty.EASY
        size = difficulty.size
        mineCount = difficulty.mines
        firstTapDone = false
        mines.fill(false)
        revealed.fill(false)
        flagged.fill(false)
        adjacentCount.fill(0)
        revealedCount = 0
        explodedIndex = -1
        lost = false
        won = false
        exploding = false
        explosionTotal = 0
        explosionsShown = 0
        explosionRank.fill(-1)
        score = 0
        revealedSomethingThisMove = false
        explosionThisUpdate = false
        firstExplosionThisUpdate = false
        lostThisUpdate = false
        flagToggledThisMove = false
        wonThisMove = false
        status = GameStatus.Running
    }

    override fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        Layout.fitSquare(viewWidth, viewHeight, HUD_TOP_PT, boardRect)
    }

    override fun onPointer(id: Int, phase: PointerPhase, x: Float, y: Float) {
        // Mines is tap/long-press-driven only (via onGesture); raw pointer events are ignored.
    }

    override fun onGesture(gesture: Gesture) {
        revealedSomethingThisMove = false
        flagToggledThisMove = false
        wonThisMove = false
        if (status != GameStatus.Running || exploding) return
        if (choosing) {
            if (gesture.kind != GestureKind.TAP) return
            val idx = difficultyButtonAt(gesture.x, gesture.y)
            if (idx >= 0) startWithDifficulty(Difficulty.entries[idx])
            return
        }
        when (gesture.kind) {
            GestureKind.TAP -> {
                val idx = cellAt(gesture.x, gesture.y) ?: return
                handleTap(idx)
            }
            GestureKind.LONG_PRESS -> {
                val idx = cellAt(gesture.x, gesture.y) ?: return
                handleLongPress(idx)
            }
            else -> Unit
        }
    }

    override fun update(dtMs: Int): Boolean {
        revealedSomethingThisMove = false
        explosionThisUpdate = false
        firstExplosionThisUpdate = false
        lostThisUpdate = false
        flagToggledThisMove = false
        wonThisMove = false
        if (!exploding) return false

        var changed = false
        if (explosionStartPending) {
            // The hit itself: the sound and the vibration go with the first frame after the touch.
            explosionStartPending = false
            explosionThisUpdate = true
            firstExplosionThisUpdate = true
            changed = true
        }
        explosionClockMs += dtMs
        while (explosionsShown < explosionTotal &&
            explosionClockMs >= FIRST_DELAY_MS + (explosionsShown - 1) * STEP_MS
        ) {
            explosionsShown++
            explosionThisUpdate = true
            changed = true
        }
        val doneAt = FIRST_DELAY_MS + (explosionTotal - 1) * STEP_MS + END_PAUSE_MS
        if (explosionsShown >= explosionTotal && explosionClockMs >= doneAt) {
            exploding = false
            lostThisUpdate = true
            status = GameStatus.Over(GameStatus.SOLO_LOSS)
            changed = true
        }
        return changed
    }

    /** Lists the mines in the order they go off: the one the player hit, then the others, nearest first. */
    private fun startExplosions(hit: Int) {
        explosionRank.fill(-1)
        var n = 0
        explosionOrder[n++] = hit
        for (i in 0 until cellCount) if (mines[i] && i != hit && n < mineCount) explosionOrder[n++] = i
        // Insertion sort of the others by distance to the hit mine (the order of indices breaks ties).
        for (a in 2 until n) {
            val v = explosionOrder[a]
            var b = a - 1
            while (b >= 1 && distanceSquared(explosionOrder[b], hit) > distanceSquared(v, hit)) {
                explosionOrder[b + 1] = explosionOrder[b]
                b--
            }
            explosionOrder[b + 1] = v
        }
        for (k in 0 until n) explosionRank[explosionOrder[k]] = k
        explosionTotal = n
        explosionsShown = 1
        explosionClockMs = 0
        exploding = true
        explosionStartPending = true
    }

    private fun distanceSquared(a: Int, b: Int): Int {
        val dr = a / size - b / size
        val dc = a % size - b % size
        return dr * dr + dc * dc
    }

    // --- Move logic -----------------------------------------------------------

    /** Sets up an empty board of level [d] and starts playing. */
    private fun startWithDifficulty(d: Difficulty) {
        difficulty = d
        size = d.size
        mineCount = d.mines
        mines.fill(false)
        revealed.fill(false)
        flagged.fill(false)
        adjacentCount.fill(0)
        revealedCount = 0
        explodedIndex = -1
        explosionRank.fill(-1)
        firstTapDone = false
        score = 0
        choosing = false
    }

    private fun difficultyButtonAt(x: Float, y: Float): Int {
        for (i in 0 until Difficulty.entries.size) {
            val by = buttonY(i)
            if (x >= buttonX() && x <= buttonX() + buttonW() && y >= by && y <= by + BUTTON_HEIGHT_PT) return i
        }
        return -1
    }

    private fun buttonX(): Float = (viewWidth - buttonW()) / 2f
    private fun buttonW(): Float = (viewWidth * 0.8f).coerceAtLeast(BUTTON_HEIGHT_PT * 2f)
    private fun buttonY(i: Int): Float {
        val totalH = 3 * BUTTON_HEIGHT_PT + 2 * BUTTON_GAP_PT
        val top = (viewHeight - totalH) / 2f
        return top + i * (BUTTON_HEIGHT_PT + BUTTON_GAP_PT)
    }

    private fun handleTap(idx: Int) {
        if (!firstTapDone) {
            placeMines(idx)
            firstTapDone = true
        }
        if (flagged[idx]) return
        if (revealed[idx]) {
            tryChord(idx)
        } else {
            revealCell(idx)
        }
    }

    private fun handleLongPress(idx: Int) {
        if (revealed[idx]) return
        flagged[idx] = !flagged[idx]
        flagToggledThisMove = true
    }

    private fun placeMines(excludeIdx: Int) {
        val er = excludeIdx / size
        val ec = excludeIdx % size
        var n = 0
        for (i in 0 until cellCount) {
            val r = i / size
            val c = i % size
            if (kotlin.math.abs(r - er) <= 1 && kotlin.math.abs(c - ec) <= 1) continue
            candidateScratch[n] = i
            n++
        }
        val placeCount = mineCount.coerceAtMost(n)
        for (k in 0 until placeCount) {
            val pick = k + rng.nextInt(n - k)
            val tmp = candidateScratch[k]
            candidateScratch[k] = candidateScratch[pick]
            candidateScratch[pick] = tmp
            mines[candidateScratch[k]] = true
        }
        for (i in 0 until cellCount) adjacentCount[i] = countAdjacentMines(i)
    }

    private fun countAdjacentMines(i: Int): Int {
        val r = i / size
        val c = i % size
        var count = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = r + dr
                val nc = c + dc
                if (nr !in 0 until size || nc !in 0 until size) continue
                if (mines[nr * size + nc]) count++
            }
        }
        return count
    }

    private fun revealCell(idx: Int) {
        if (revealed[idx] || flagged[idx]) return
        if (mines[idx]) {
            revealed[idx] = true
            explodedIndex = idx
            lost = true
            startExplosions(idx)
            return
        }
        val before = revealedCount
        floodFill(idx)
        if (revealedCount > before) revealedSomethingThisMove = true
        score = revealedCount
        checkWin()
    }

    private fun floodFill(start: Int) {
        var top = 0
        floodQueued.fill(false)
        floodStack[top] = start
        floodQueued[start] = true
        top++
        while (top > 0) {
            top--
            val i = floodStack[top]
            if (revealed[i] || flagged[i]) continue
            revealed[i] = true
            revealedCount++
            if (adjacentCount[i] == 0) {
                val r = i / size
                val c = i % size
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val nr = r + dr
                        val nc = c + dc
                        if (nr !in 0 until size || nc !in 0 until size) continue
                        val j = nr * size + nc
                        if (!revealed[j] && !mines[j] && !floodQueued[j]) {
                            floodStack[top] = j
                            floodQueued[j] = true
                            top++
                        }
                    }
                }
            }
        }
    }

    private fun tryChord(idx: Int) {
        if (adjacentCount[idx] == 0) return
        val r = idx / size
        val c = idx % size
        var flagCount = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = r + dr
                val nc = c + dc
                if (nr !in 0 until size || nc !in 0 until size) continue
                if (flagged[nr * size + nc]) flagCount++
            }
        }
        if (flagCount != adjacentCount[idx]) return
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = r + dr
                val nc = c + dc
                if (nr !in 0 until size || nc !in 0 until size) continue
                val j = nr * size + nc
                if (flagged[j] || revealed[j]) continue
                revealCell(j)
                if (status != GameStatus.Running || exploding) return
            }
        }
    }

    private fun checkWin() {
        if (revealedCount == cellCount - mineCount) {
            won = true
            wonThisMove = true
            status = GameStatus.Over(GameStatus.SOLO_WIN)
        }
    }

    private fun cellAt(x: Float, y: Float): Int? {
        if (boardRect.w <= 0f) return null
        if (x < boardRect.x || x > boardRect.x + boardRect.w) return null
        if (y < boardRect.y || y > boardRect.y + boardRect.h) return null
        val cell = boardRect.w / size
        val col = ((x - boardRect.x) / cell).toInt().coerceIn(0, size - 1)
        val row = ((y - boardRect.y) / cell).toInt().coerceIn(0, size - 1)
        return row * size + col
    }

    // --- Test-only accessors (internal: exercised by MinesRulesTest) ----

    internal fun debugMine(idx: Int): Boolean = mines[idx]
    internal fun debugRevealed(idx: Int): Boolean = revealed[idx]
    internal fun debugFlagged(idx: Int): Boolean = flagged[idx]
    internal fun debugAdjacentCount(idx: Int): Int = adjacentCount[idx]
    internal fun debugFirstTapDone(): Boolean = firstTapDone
    internal fun debugRevealedCount(): Int = revealedCount
    internal fun debugRngState(): Long = rng.state
    internal fun debugTapCell(idx: Int): Unit = handleTap(idx)
    internal fun debugLongPressCell(idx: Int): Unit = handleLongPress(idx)
    internal fun debugChoosing(): Boolean = choosing
    internal fun debugSize(): Int = size
    internal fun debugMineCount(): Int = mineCount
    internal fun debugStartWithDifficulty(d: Difficulty): Unit = startWithDifficulty(d)
    internal fun debugButtonCenterX(): Float = viewWidth / 2f
    internal fun debugButtonCenterY(i: Int): Float = buttonY(i) + BUTTON_HEIGHT_PT / 2f
    internal fun debugExploding(): Boolean = exploding
    internal fun debugExplosionsShown(): Int = explosionsShown
    internal fun debugExplosionTotal(): Int = explosionTotal
    internal fun debugExplosionOrder(k: Int): Int = explosionOrder[k]

    /** The three levels: a bigger board with a bigger share of mines each time (12%, 16% and 20% of the cells). */
    public enum class Difficulty(public val size: Int, public val mines: Int, internal val sizeLabel: String) {
        EASY(9, 10, "9 x 9"),
        MEDIUM(11, 19, "11 x 11"),
        HARD(13, 34, "13 x 13"),
    }

    public companion object {
        /** The biggest board (the hard level): the arrays are allocated for it once. */
        private const val MAX_SIZE: Int = 13
        private const val MAX_CELLS: Int = MAX_SIZE * MAX_SIZE
        private const val MAX_MINES: Int = 34

        private const val BUTTON_HEIGHT_PT: Float = 64f
        private const val BUTTON_GAP_PT: Float = 16f

        private val DIFFICULTY_LABELS: Array<StringKey> = arrayOf(StringKey.EASY, StringKey.MEDIUM, StringKey.HARD)

        /** Vertical strip reserved for the session HUD (score / best). */
        private const val HUD_TOP_PT: Float = 32f
        private const val FLAG_SIZE_FRACTION: Float = 0.5f

        /** Strength of the purple of a closed cell: clearly different from an opened one, light or dark theme. */
        private const val CLOSED_CELL_ALPHA: Float = 0.9f

        /** The chain of explosions after a hit mine, in milliseconds: wait for the first, then one every step. */
        private const val FIRST_DELAY_MS: Int = 450
        private const val STEP_MS: Int = 170
        private const val END_PAUSE_MS: Int = 800
        private const val SPARK_COUNT: Int = 8

        /** Size of the digits relative to a cell: large enough to read at a glance. */
        private const val NUMBER_SIZE_FRACTION: Float = 0.62f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "mines",
            titleKey = StringKey.TITLE_MINES,
            tier = Tier.UNLOCKED,
            players = 1..1,
            orientation = Orientation.PORTRAIT,
            complexity = Complexity.M,
            resumable = true,
            factory = { MinesGame() },
            icon = { scene, size -> ArcadeIcons.hiddenBombs(scene, size) },
        )
    }

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)
        if (choosing) {
            renderChooser(scene)
            return
        }
        scene.rect(boardRect.x, boardRect.y, boardRect.w, boardRect.h, Palette.SURFACE, radius = boardRect.w * 0.02f)

        val cell = boardRect.w / size
        val pad = cell * 0.05f
        for (i in 0 until cellCount) {
            val row = i / size
            val col = i % size
            val cx = boardRect.x + col * cell
            val cy = boardRect.y + row * cell
            val isMine = mines[i]
            val rank = explosionRank[i]
            val showMine = lost && isMine && rank in 0 until explosionsShown

            if (revealed[i] && !isMine || showMine) {
                // Opened cells are light (the page color), closed ones purple (the color of player 2): easy to tell
                // apart, and the digits stand on the lightest background. The flag is white and yellow to pop on it.
                val bg = if (showMine) Palette.DANGER else Palette.BG
                scene.rect(cx + pad, cy + pad, cell - 2 * pad, cell - 2 * pad, bg, radius = pad)
                if (showMine) {
                    scene.circle(cx + cell / 2f, cy + cell / 2f, cell * 0.28f, Palette.FG)
                    // The mine that has just gone off (not the ones already burnt out) throws sparks.
                    if (exploding && rank == explosionsShown - 1) drawSparks(scene, cx + cell / 2f, cy + cell / 2f, cell)
                } else if (adjacentCount[i] > 0) {
                    val color = Palette.NUMBER_1 + (adjacentCount[i] - 1).coerceIn(0, 7)
                    scene.number(cx + cell / 2f, cy + cell / 2f, adjacentCount[i], color, cell * NUMBER_SIZE_FRACTION, Align.CENTER)
                }
            } else {
                scene.rect(cx + pad, cy + pad, cell - 2 * pad, cell - 2 * pad, Palette.SURFACE, radius = pad)
                scene.rect(
                    cx + pad * 1.5f, cy + pad * 1.5f, cell - 3 * pad, cell - 3 * pad,
                    Palette.P2, radius = pad, alpha = CLOSED_CELL_ALPHA,
                )
                if (flagged[i]) {
                    val fx = cx + cell * 0.3f
                    val fy = cy + cell * 0.25f
                    val fh = cell * FLAG_SIZE_FRACTION
                    scene.line(fx, fy, fx, fy + fh, Palette.NEON_WHITE, width = cell * 0.06f)
                    scene.line(fx, fy, fx + cell * 0.3f, fy + fh * 0.3f, Palette.NEON_YELLOW, width = cell * 0.07f)
                    scene.line(fx + cell * 0.3f, fy + fh * 0.3f, fx, fy + fh * 0.6f, Palette.NEON_YELLOW, width = cell * 0.07f)
                }
            }
        }

        if (flagToggledThisMove) scene.sound(SoundId.TICK)
        if (revealedSomethingThisMove) scene.sound(SoundId.EAT)
        if (wonThisMove) {
            scene.sound(SoundId.WIN)
            scene.haptic()
        }
        if (explosionThisUpdate) scene.sound(SoundId.EXPLOSION)
        if (firstExplosionThisUpdate) scene.haptic()
        if (lostThisUpdate) scene.sound(SoundId.LOSE)
    }

    /** The level choice: three buttons, each with its board size and its number of mines. */
    private fun renderChooser(scene: Scene) {
        scene.text(viewWidth / 2f, buttonY(0) - 40f, StringKey.TITLE_MINES, Palette.FG, 24f, Align.CENTER)
        for (i in 0 until Difficulty.entries.size) {
            val d = Difficulty.entries[i]
            val by = buttonY(i)
            val bx = buttonX()
            scene.rect(bx, by, buttonW(), BUTTON_HEIGHT_PT, Palette.ACCENT, radius = 12f)
            scene.text(bx + 24f, by + BUTTON_HEIGHT_PT / 2f, DIFFICULTY_LABELS[i], Palette.BG, 20f, Align.LEFT)
            // Board size and number of mines, on the right: "9 x 9" then a mine and its count.
            scene.label(bx + buttonW() - 92f, by + BUTTON_HEIGHT_PT / 2f, d.sizeLabel, Palette.BG, 16f, Align.RIGHT)
            scene.circle(bx + buttonW() - 70f, by + BUTTON_HEIGHT_PT / 2f, 6f, Palette.FG)
            scene.number(bx + buttonW() - 16f, by + BUTTON_HEIGHT_PT / 2f, d.mines, Palette.BG, 16f, Align.RIGHT)
        }
    }

    /** Eight short yellow rays around ([x], [y]): the flash of a mine that goes off. */
    private fun drawSparks(scene: Scene, x: Float, y: Float, cell: Float) {
        for (k in 0 until SPARK_COUNT) {
            val angle = k * (2.0 * PI / SPARK_COUNT)
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            scene.line(x + dx * cell * 0.2f, y + dy * cell * 0.2f, x + dx * cell * 0.46f, y + dy * cell * 0.46f, Palette.NEON_YELLOW, width = cell * 0.06f)
        }
    }

    // --- Save / restore -----------------------------------------------------
    // Only called from GameSession.persist() on pause, never per-frame, so the
    // map/string allocation here is fine (see docs/ARCHITECTURE.md).

    override fun snapshot(): String? {
        val map = LinkedHashMap<String, String>(8)
        map["mines"] = bitsToString(mines, cellCount)
        map["revealed"] = bitsToString(revealed, cellCount)
        map["flagged"] = bitsToString(flagged, cellCount)
        map["first"] = if (firstTapDone) "1" else "0"
        map["choosing"] = if (choosing) "1" else "0"
        map["difficulty"] = difficulty.ordinal.toString()
        map["exploded"] = explodedIndex.toString()
        map["lost"] = if (lost) "1" else "0"
        map["won"] = if (won) "1" else "0"
        map["score"] = score.toString()
        map["rng"] = rng.state.toString()
        return KvCodec.encode(map)
    }

    override fun restore(data: String): Boolean {
        val map = KvCodec.decode(data)
        // A game saved before the levels existed has neither key: it is an easy 9 x 9 game, already started.
        val difficultyOrdinal = map["difficulty"]?.toIntOrNull() ?: Difficulty.EASY.ordinal
        if (difficultyOrdinal !in Difficulty.entries.indices) return false
        val saved = Difficulty.entries[difficultyOrdinal]
        val choosingStr = map["choosing"] ?: "0"
        if (choosingStr != "0" && choosingStr != "1") return false
        val savedCells = saved.size * saved.size
        val minesStr = map["mines"] ?: return false
        val revealedStr = map["revealed"] ?: return false
        val flaggedStr = map["flagged"] ?: return false
        if (minesStr.length != savedCells || revealedStr.length != savedCells || flaggedStr.length != savedCells) return false
        if (!isBitString(minesStr) || !isBitString(revealedStr) || !isBitString(flaggedStr)) return false
        val firstStr = map["first"]
        if (firstStr != "0" && firstStr != "1") return false
        val explodedStr = map["exploded"]?.toIntOrNull() ?: return false
        if (explodedStr < -1 || explodedStr >= savedCells) return false
        val lostStr = map["lost"]
        if (lostStr != "0" && lostStr != "1") return false
        val wonStr = map["won"]
        if (wonStr != "0" && wonStr != "1") return false
        val newScore = map["score"]?.toIntOrNull() ?: return false
        if (newScore < 0) return false
        val rngState = map["rng"]?.toLongOrNull() ?: return false

        // Everything checked: apply the saved level, then the saved board (cells beyond it are cleared).
        difficulty = saved
        size = saved.size
        mineCount = saved.mines
        choosing = choosingStr == "1"
        mines.fill(false)
        revealed.fill(false)
        flagged.fill(false)
        adjacentCount.fill(0)
        for (i in 0 until cellCount) {
            mines[i] = minesStr[i] == '1'
            revealed[i] = revealedStr[i] == '1'
            flagged[i] = flaggedStr[i] == '1'
        }
        for (i in 0 until cellCount) adjacentCount[i] = countAdjacentMines(i)
        var count = 0
        for (i in 0 until cellCount) if (revealed[i] && !mines[i]) count++
        revealedCount = count
        firstTapDone = firstStr == "1"
        explodedIndex = explodedStr
        lost = lostStr == "1"
        won = wonStr == "1"
        score = newScore
        rng = Rng.fromState(rngState)
        exploding = false
        explosionTotal = 0
        explosionsShown = 0
        explosionRank.fill(-1)
        if (lost && mines.any { it }) {
            // A game saved after a loss: every mine is shown as burnt out, no chain to replay.
            val hit = if (explodedIndex in 0 until cellCount && mines[explodedIndex]) explodedIndex else mines.indexOfFirst { it }
            startExplosions(hit)
            explosionsShown = explosionTotal
            explosionStartPending = false
            exploding = false
        }
        status = when {
            lost -> GameStatus.Over(GameStatus.SOLO_LOSS)
            won -> GameStatus.Over(GameStatus.SOLO_WIN)
            else -> GameStatus.Running
        }
        revealedSomethingThisMove = false
        explosionThisUpdate = false
        firstExplosionThisUpdate = false
        lostThisUpdate = false
        flagToggledThisMove = false
        wonThisMove = false
        return true
    }

    /** The first [count] entries of [bits] as a string of `0` and `1` (the arrays are bigger than the board). */
    private fun bitsToString(bits: BooleanArray, count: Int): String {
        val sb = StringBuilder(count)
        for (i in 0 until count) sb.append(if (bits[i]) '1' else '0')
        return sb.toString()
    }

    private fun isBitString(s: String): Boolean {
        for (ch in s) if (ch != '0' && ch != '1') return false
        return true
    }
}
