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
import lg.core.scene.Scene
import lg.core.scene.SoundId
import lg.core.scene.StringKey
import lg.core.util.KvCodec
import lg.core.util.Layout
import lg.core.util.Rect
import lg.core.util.Rng

/**
 * Classic 9x9 Minesweeper with [MINE_COUNT] mines.
 *
 * ### Data layout
 *
 * [mines], [revealed] and [flagged] are flat `SIZE * SIZE` boolean arrays
 * (`idx = row * SIZE + col`); [adjacentCount] caches the number of
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

    override val wantsFrames: Boolean = false

    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private val boardRect = Rect()

    private var rng: Rng = Rng(0L)
    private var firstTapDone: Boolean = false

    private val mines = BooleanArray(CELL_COUNT)
    private val revealed = BooleanArray(CELL_COUNT)
    private val flagged = BooleanArray(CELL_COUNT)
    private val adjacentCount = IntArray(CELL_COUNT)
    private var revealedCount: Int = 0
    private var explodedIndex: Int = -1
    private var lost: Boolean = false
    private var won: Boolean = false

    // Reused scratch buffers; see the class KDoc.
    private val candidateScratch = IntArray(CELL_COUNT)
    private val floodStack = IntArray(CELL_COUNT)
    private val floodQueued = BooleanArray(CELL_COUNT)

    // Events from the most recent action, read (not mutated) by render().
    private var revealedSomethingThisMove: Boolean = false
    private var hitMineThisMove: Boolean = false
    private var flagToggledThisMove: Boolean = false
    private var wonThisMove: Boolean = false

    override fun reset(seed: Long, players: Int) {
        rng = Rng(seed)
        firstTapDone = false
        mines.fill(false)
        revealed.fill(false)
        flagged.fill(false)
        adjacentCount.fill(0)
        revealedCount = 0
        explodedIndex = -1
        lost = false
        won = false
        score = 0
        revealedSomethingThisMove = false
        hitMineThisMove = false
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
        hitMineThisMove = false
        flagToggledThisMove = false
        wonThisMove = false
        if (status != GameStatus.Running) return
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
        hitMineThisMove = false
        flagToggledThisMove = false
        wonThisMove = false
        return false
    }

    // --- Move logic -----------------------------------------------------------

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
        val er = excludeIdx / SIZE
        val ec = excludeIdx % SIZE
        var n = 0
        for (i in 0 until CELL_COUNT) {
            val r = i / SIZE
            val c = i % SIZE
            if (kotlin.math.abs(r - er) <= 1 && kotlin.math.abs(c - ec) <= 1) continue
            candidateScratch[n] = i
            n++
        }
        val placeCount = MINE_COUNT.coerceAtMost(n)
        for (k in 0 until placeCount) {
            val pick = k + rng.nextInt(n - k)
            val tmp = candidateScratch[k]
            candidateScratch[k] = candidateScratch[pick]
            candidateScratch[pick] = tmp
            mines[candidateScratch[k]] = true
        }
        for (i in 0 until CELL_COUNT) adjacentCount[i] = countAdjacentMines(i)
    }

    private fun countAdjacentMines(i: Int): Int {
        val r = i / SIZE
        val c = i % SIZE
        var count = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = r + dr
                val nc = c + dc
                if (nr !in 0 until SIZE || nc !in 0 until SIZE) continue
                if (mines[nr * SIZE + nc]) count++
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
            hitMineThisMove = true
            status = GameStatus.Over(0)
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
                val r = i / SIZE
                val c = i % SIZE
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val nr = r + dr
                        val nc = c + dc
                        if (nr !in 0 until SIZE || nc !in 0 until SIZE) continue
                        val j = nr * SIZE + nc
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
        val r = idx / SIZE
        val c = idx % SIZE
        var flagCount = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = r + dr
                val nc = c + dc
                if (nr !in 0 until SIZE || nc !in 0 until SIZE) continue
                if (flagged[nr * SIZE + nc]) flagCount++
            }
        }
        if (flagCount != adjacentCount[idx]) return
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = r + dr
                val nc = c + dc
                if (nr !in 0 until SIZE || nc !in 0 until SIZE) continue
                val j = nr * SIZE + nc
                if (flagged[j] || revealed[j]) continue
                revealCell(j)
                if (status != GameStatus.Running) return
            }
        }
    }

    private fun checkWin() {
        if (revealedCount == CELL_COUNT - MINE_COUNT) {
            won = true
            wonThisMove = true
            status = GameStatus.Over(0)
        }
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

    public companion object {
        public const val SIZE: Int = 9
        public const val CELL_COUNT: Int = SIZE * SIZE
        public const val MINE_COUNT: Int = 10

        /** Vertical strip reserved for the session HUD (score / best). */
        private const val HUD_TOP_PT: Float = 32f
        private const val FLAG_SIZE_FRACTION: Float = 0.5f

        /** Static description registered in [lg.core.GameRegistry]. */
        public val META: GameMeta = GameMeta(
            id = "mines",
            titleKey = StringKey.TITLE_MINES,
            tier = Tier.UNLOCKED,
            players = 1..1,
            orientation = Orientation.ANY,
            complexity = Complexity.M,
            resumable = true,
            factory = { MinesGame() },
            icon = { scene, size -> drawIcon(scene, size) },
        )

        private fun drawIcon(scene: Scene, size: Float) {
            val gap = size * 0.08f
            val cell = (size - 3 * gap) / 2f
            scene.rect(gap, gap, cell, cell, Palette.GRID, radius = cell * 0.1f)
            scene.rect(gap * 2 + cell, gap, cell, cell, Palette.GRID, radius = cell * 0.1f)
            scene.rect(gap, gap * 2 + cell, cell, cell, Palette.GRID, radius = cell * 0.1f)
            scene.circle(gap * 2 + cell + cell / 2f, gap * 2 + cell + cell / 2f, cell * 0.32f, Palette.FG)
            scene.number(gap + cell / 2f, gap + cell / 2f, 1, Palette.TILE_0, cell * 0.55f, Align.CENTER)
        }
    }

    // --- Rendering ------------------------------------------------------------

    override fun render(scene: Scene) {
        scene.rect(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f), Palette.BG)
        scene.rect(boardRect.x, boardRect.y, boardRect.w, boardRect.h, Palette.SURFACE, radius = boardRect.w * 0.02f)

        val cell = boardRect.w / SIZE
        val pad = cell * 0.05f
        for (i in 0 until CELL_COUNT) {
            val row = i / SIZE
            val col = i % SIZE
            val cx = boardRect.x + col * cell
            val cy = boardRect.y + row * cell
            val isMine = mines[i]
            val showMine = revealed[i] && isMine || (status is GameStatus.Over && lost && isMine)

            if (revealed[i] || showMine) {
                val bg = if (i == explodedIndex) Palette.DANGER else Palette.GRID
                scene.rect(cx + pad, cy + pad, cell - 2 * pad, cell - 2 * pad, bg, radius = pad)
                if (showMine) {
                    scene.circle(cx + cell / 2f, cy + cell / 2f, cell * 0.28f, Palette.FG)
                } else if (adjacentCount[i] > 0) {
                    val color = Palette.TILE_0 + (adjacentCount[i] - 1).coerceIn(0, 7)
                    scene.number(cx + cell / 2f, cy + cell / 2f, adjacentCount[i], color, cell * 0.5f, Align.CENTER)
                }
            } else {
                scene.rect(cx + pad, cy + pad, cell - 2 * pad, cell - 2 * pad, Palette.SURFACE, radius = pad)
                scene.rect(cx + pad * 1.5f, cy + pad * 1.5f, cell - 3 * pad, cell - 3 * pad, Palette.GRID, radius = pad)
                if (flagged[i]) {
                    val fx = cx + cell * 0.3f
                    val fy = cy + cell * 0.25f
                    val fh = cell * FLAG_SIZE_FRACTION
                    scene.line(fx, fy, fx, fy + fh, Palette.DANGER, width = cell * 0.05f)
                    scene.line(fx, fy, fx + cell * 0.3f, fy + fh * 0.3f, Palette.DANGER, width = cell * 0.05f)
                    scene.line(fx + cell * 0.3f, fy + fh * 0.3f, fx, fy + fh * 0.6f, Palette.DANGER, width = cell * 0.05f)
                }
            }
        }

        if (flagToggledThisMove) scene.sound(SoundId.TICK)
        if (revealedSomethingThisMove) scene.sound(SoundId.EAT)
        if (wonThisMove) {
            scene.sound(SoundId.WIN)
            scene.haptic()
        }
        if (hitMineThisMove) {
            scene.sound(SoundId.LOSE)
            scene.haptic()
        }
    }

    // --- Save / restore -----------------------------------------------------
    // Only called from GameSession.persist() on pause, never per-frame, so the
    // map/string allocation here is fine (see docs/ARCHITECTURE.md).

    override fun snapshot(): String? {
        val map = LinkedHashMap<String, String>(8)
        map["mines"] = bitsToString(mines)
        map["revealed"] = bitsToString(revealed)
        map["flagged"] = bitsToString(flagged)
        map["first"] = if (firstTapDone) "1" else "0"
        map["exploded"] = explodedIndex.toString()
        map["lost"] = if (lost) "1" else "0"
        map["won"] = if (won) "1" else "0"
        map["score"] = score.toString()
        map["rng"] = rng.state.toString()
        return KvCodec.encode(map)
    }

    override fun restore(data: String): Boolean {
        val map = KvCodec.decode(data)
        val minesStr = map["mines"] ?: return false
        val revealedStr = map["revealed"] ?: return false
        val flaggedStr = map["flagged"] ?: return false
        if (minesStr.length != CELL_COUNT || revealedStr.length != CELL_COUNT || flaggedStr.length != CELL_COUNT) return false
        if (!isBitString(minesStr) || !isBitString(revealedStr) || !isBitString(flaggedStr)) return false
        val firstStr = map["first"]
        if (firstStr != "0" && firstStr != "1") return false
        val explodedStr = map["exploded"]?.toIntOrNull() ?: return false
        if (explodedStr < -1 || explodedStr >= CELL_COUNT) return false
        val lostStr = map["lost"]
        if (lostStr != "0" && lostStr != "1") return false
        val wonStr = map["won"]
        if (wonStr != "0" && wonStr != "1") return false
        val newScore = map["score"]?.toIntOrNull() ?: return false
        if (newScore < 0) return false
        val rngState = map["rng"]?.toLongOrNull() ?: return false

        for (i in 0 until CELL_COUNT) {
            mines[i] = minesStr[i] == '1'
            revealed[i] = revealedStr[i] == '1'
            flagged[i] = flaggedStr[i] == '1'
        }
        for (i in 0 until CELL_COUNT) adjacentCount[i] = countAdjacentMines(i)
        var count = 0
        for (i in 0 until CELL_COUNT) if (revealed[i] && !mines[i]) count++
        revealedCount = count
        firstTapDone = firstStr == "1"
        explodedIndex = explodedStr
        lost = lostStr == "1"
        won = wonStr == "1"
        score = newScore
        rng = Rng.fromState(rngState)
        status = if (lost || won) GameStatus.Over(0) else GameStatus.Running
        revealedSomethingThisMove = false
        hitMineThisMove = false
        flagToggledThisMove = false
        wonThisMove = false
        return true
    }

    private fun bitsToString(bits: BooleanArray): String {
        val sb = StringBuilder(bits.size)
        for (b in bits) sb.append(if (b) '1' else '0')
        return sb.toString()
    }

    private fun isBitString(s: String): Boolean {
        for (ch in s) if (ch != '0' && ch != '1') return false
        return true
    }
}
