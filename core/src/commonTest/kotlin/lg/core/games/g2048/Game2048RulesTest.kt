package lg.core.games.g2048

import lg.core.GameStatus
import lg.core.input.Gesture
import lg.core.input.GestureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

public class Game2048RulesTest {
    private fun gestureOf(kind: GestureKind): Gesture {
        val g = Gesture()
        g.kind = kind
        g.x = 0f
        g.y = 0f
        g.pointerId = 0
        return g
    }

    private fun newGame(seed: Long = 1L): Game2048 {
        val game = Game2048()
        game.resize(400f, 432f)
        game.reset(seed)
        return game
    }

    /** Empties the whole board so a test can lay out an exact scenario, overriding reset()'s random start tiles. */
    private fun clearGrid(game: Game2048) {
        for (y in 0 until Game2048.SIZE) {
            for (x in 0 until Game2048.SIZE) {
                game.debugSetCell(x, y, 0)
            }
        }
    }

    private fun setRow(game: Game2048, y: Int, values: IntArray) {
        for (x in values.indices) game.debugSetCell(x, y, values[x])
    }

    private fun rowOf(game: Game2048, y: Int): IntArray = IntArray(Game2048.SIZE) { x -> game.debugGetCell(x, y) }

    private fun fullGrid(game: Game2048): IntArray =
        IntArray(Game2048.SIZE * Game2048.SIZE) { i -> game.debugGetCell(i % Game2048.SIZE, i / Game2048.SIZE) }

    private fun countNonEmpty(grid: Game2048): Int {
        var n = 0
        for (y in 0 until Game2048.SIZE) {
            for (x in 0 until Game2048.SIZE) {
                if (grid.debugGetCell(x, y) != 0) n++
            }
        }
        return n
    }

    @Test
    public fun mergeLeftCombinesLeadingPairOnly(): Unit {
        val game = newGame()
        clearGrid(game)
        // Exponents 1,1,2,0 = values 2,2,4,0.
        setRow(game, 0, intArrayOf(1, 1, 2, 0))
        game.onGesture(gestureOf(GestureKind.SWIPE_LEFT))
        // 2+2 -> 4, then the original 4 slides next to it: [4,4,0,0].
        assertEquals(listOf(2, 2, 0, 0), rowOf(game, 0).toList())
        assertEquals(4, game.score) // one merge producing value 4
    }

    @Test
    public fun noTileMergesTwiceInOneMove(): Unit {
        val game = newGame()
        clearGrid(game)
        // Exponents 1,1,1,1 = values 2,2,2,2: must become [4,4,0,0], never a single 8.
        setRow(game, 0, intArrayOf(1, 1, 1, 1))
        game.onGesture(gestureOf(GestureKind.SWIPE_LEFT))
        assertEquals(listOf(2, 2, 0, 0), rowOf(game, 0).toList())
        assertEquals(8, game.score) // two merges, each producing value 4
    }

    @Test
    public fun moveThatChangesNothingIsIgnoredAndSpawnsNothing(): Unit {
        val game = newGame()
        clearGrid(game)
        // Already fully compacted to the left with no adjacent equals: swiping left must be a no-op.
        setRow(game, 0, intArrayOf(1, 2, 3, 0))
        val before = fullGrid(game)
        game.onGesture(gestureOf(GestureKind.SWIPE_LEFT))
        assertEquals(before.toList(), fullGrid(game).toList())
        assertEquals(0, game.score)
    }

    @Test
    public fun validMoveSpawnsExactlyOneTile(): Unit {
        val game = newGame()
        clearGrid(game)
        setRow(game, 0, intArrayOf(1, 0, 0, 0))
        assertEquals(1, countNonEmpty(game))
        game.onGesture(gestureOf(GestureKind.SWIPE_RIGHT))
        // The lone tile slides from x=0 to x=3 (changed = true), so a spawn must follow.
        assertEquals(listOf(0, 0, 0, 1), rowOf(game, 0).toList())
        assertEquals(2, countNonEmpty(game))
        assertEquals(0, game.score) // no merge occurred
    }

    @Test
    public fun gameOverDetectedOnFullNonMergeableGrid(): Unit {
        val game = newGame()
        clearGrid(game)
        // Rows 1-3 are already packed with no adjacent-equal pairs anywhere (horizontally or
        // vertically); row 0 has a single gap at x=0. Swiping left compacts row 0 to
        // [3,4,3,0] (rows 1-3 are untouched, already packed) leaving exactly one empty cell,
        // which the post-move spawn deterministically fills (only one candidate cell, so the
        // spawned exponent - 1 or 2 - is the only thing left to chance, and it never equals a
        // neighboring 3/4/5/6). The resulting board has no empty cell and no adjacent-equal
        // pair anywhere: hasAnyMove() must be false.
        setRow(game, 0, intArrayOf(0, 3, 4, 3))
        setRow(game, 1, intArrayOf(5, 6, 5, 6))
        setRow(game, 2, intArrayOf(3, 4, 3, 4))
        setRow(game, 3, intArrayOf(6, 5, 6, 5))

        game.onGesture(gestureOf(GestureKind.SWIPE_LEFT))

        assertTrue(game.status is GameStatus.Over, "expected game over, board=${fullGrid(game).toList()}")
        assertEquals(0, (game.status as GameStatus.Over).winner)
        assertEquals(Game2048.SIZE * Game2048.SIZE, countNonEmpty(game), "board must be completely full")
    }

    @Test
    public fun snapshotRestoreRoundTripReproducesExactFutureState(): Unit {
        val gameA = newGame(seed = 123L)
        // Play a handful of real moves so score, grid and rng are all non-trivial.
        val script = listOf(
            GestureKind.SWIPE_LEFT,
            GestureKind.SWIPE_UP,
            GestureKind.SWIPE_RIGHT,
            GestureKind.SWIPE_DOWN,
            GestureKind.SWIPE_LEFT,
        )
        // Flush the slide animation after each swipe (update() past ANIM_DURATION_MS) so the
        // next swipe is applied immediately instead of merely being queued as pending.
        for (kind in script) {
            gameA.onGesture(gestureOf(kind))
            gameA.update(200)
        }

        val gridAtSnapshot = fullGrid(gameA)
        val scoreAtSnapshot = gameA.score
        val wonAtSnapshot = gameA.debugReached2048()
        val snapshot = gameA.snapshot()
        assertTrue(snapshot != null)

        // Advance gameA by exactly one more move; this is what a correct restore must reproduce.
        gameA.onGesture(gestureOf(GestureKind.SWIPE_UP))
        val gridAfterOneMore = fullGrid(gameA)
        val scoreAfterOneMore = gameA.score

        val gameB = Game2048()
        gameB.resize(400f, 432f)
        assertTrue(gameB.restore(snapshot))

        assertEquals(gridAtSnapshot.toList(), fullGrid(gameB).toList())
        assertEquals(scoreAtSnapshot, gameB.score)
        assertEquals(wonAtSnapshot, gameB.debugReached2048())

        gameB.onGesture(gestureOf(GestureKind.SWIPE_UP))
        assertEquals(gridAfterOneMore.toList(), fullGrid(gameB).toList())
        assertEquals(scoreAfterOneMore, gameB.score)
    }

    @Test
    public fun restoreRejectsGarbage(): Unit {
        val game = newGame(seed = 1L)
        val before = fullGrid(game)
        val beforeScore = game.score

        assertFalse(game.restore("not a valid snapshot"))
        assertFalse(game.restore(""))
        assertFalse(game.restore("c0=1;c1=2")) // missing most keys

        // A failed restore must not have mutated any state.
        assertEquals(before.toList(), fullGrid(game).toList())
        assertEquals(beforeScore, game.score)
    }
}
