package lg.core.games.sliding

import lg.core.GameStatus
import lg.core.util.KvCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

public class SlidingRulesTest {
    private fun newGame(seed: Long = 1L): SlidingGame {
        val game = SlidingGame()
        game.resize(400f, 432f)
        game.reset(seed)
        return game
    }

    private fun solvedGrid(): IntArray = IntArray(SlidingGame.CELL_COUNT) { i -> if (i == SlidingGame.CELL_COUNT - 1) 0 else i + 1 }

    @Test
    public fun shuffledStateIsNotAlreadySolved(): Unit {
        val game = newGame()
        assertFalse(game.debugIsSolved())
    }

    @Test
    public fun shuffledStateIsAPermutationOfTheSolvedBoard(): Unit {
        val game = newGame()
        val seen = BooleanArray(SlidingGame.CELL_COUNT)
        for (i in 0 until SlidingGame.CELL_COUNT) {
            val v = game.debugTile(i)
            assertTrue(v in 0 until SlidingGame.CELL_COUNT)
            assertFalse(seen[v], "value $v appears twice")
            seen[v] = true
        }
    }

    @Test
    public fun onlyTilesAdjacentToTheBlankCanMove(): Unit {
        val game = newGame()
        val grid = solvedGrid()
        // Move the blank to index 5 (row 1, col 1); index 0 is not adjacent to it.
        grid[SlidingGame.CELL_COUNT - 1] = grid[5]
        grid[5] = 0
        game.debugSetGrid(grid)

        val before = game.debugTile(0)
        game.onGesture(tapAt(0))
        assertEquals(before, game.debugTile(0)) // unaffected: not adjacent to the blank
        assertEquals(0, game.score)

        val adjacentValue = game.debugTile(1) // index 1 (row 0, col 1) is adjacent to blank at index 5
        game.onGesture(tapAt(1))
        assertEquals(1, game.score)
        assertEquals(adjacentValue, game.debugTile(5))
        assertEquals(0, game.debugTile(1))
    }

    @Test
    public fun solvingTheBoardEndsTheGame(): Unit {
        val game = newGame()
        val grid = solvedGrid()
        // One move away from solved: swap the last two tiles' positions with the blank.
        grid[SlidingGame.CELL_COUNT - 1] = grid[SlidingGame.CELL_COUNT - 2]
        grid[SlidingGame.CELL_COUNT - 2] = 0
        game.debugSetGrid(grid)
        assertFalse(game.debugIsSolved())

        game.onGesture(tapAt(SlidingGame.CELL_COUNT - 1))
        assertTrue(game.debugIsSolved())
        assertTrue(game.status is GameStatus.Over)
        assertEquals(0, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun snapshotRestoreRoundTrips(): Unit {
        val game = newGame(seed = 7L)
        game.onGesture(tapAt(indexOfNeighborOfBlank(game)))
        val scoreBefore = game.score
        val gridBefore = IntArray(SlidingGame.CELL_COUNT) { game.debugTile(it) }
        val rngBefore = game.debugRngState()

        val data = game.snapshot()
        assertTrue(data != null)

        val restored = SlidingGame()
        restored.resize(400f, 432f)
        assertTrue(restored.restore(data))
        assertEquals(scoreBefore, restored.score)
        assertEquals(rngBefore, restored.debugRngState())
        for (i in 0 until SlidingGame.CELL_COUNT) {
            assertEquals(gridBefore[i], restored.debugTile(i))
        }
    }

    @Test
    public fun restoreRejectsCorruptData(): Unit {
        val game = newGame()
        assertFalse(game.restore("not a valid save"))
        assertFalse(game.restore(""))

        // Complete but invalid: value 1 appears twice (not a permutation of 0..15).
        val map = LinkedHashMap<String, String>()
        for (i in 0 until SlidingGame.CELL_COUNT) map["t$i"] = if (i == 0) "1" else if (i == 1) "1" else i.toString()
        map["score"] = "0"
        map["rng"] = "42"
        assertFalse(game.restore(KvCodec.encode(map)))
    }

    private fun indexOfNeighborOfBlank(game: SlidingGame): Int {
        val blank = game.debugBlankIndex()
        val row = blank / SlidingGame.SIZE
        val col = blank % SlidingGame.SIZE
        for (i in 0 until SlidingGame.CELL_COUNT) {
            val r = i / SlidingGame.SIZE
            val c = i % SlidingGame.SIZE
            if (kotlin.math.abs(r - row) + kotlin.math.abs(c - col) == 1) return i
        }
        error("a 4x4 board always has a neighbor")
    }

    private fun tapAt(idx: Int): lg.core.input.Gesture {
        val g = lg.core.input.Gesture()
        g.kind = lg.core.input.GestureKind.TAP
        // Coordinates matching newGame()'s resize(400f, 432f): board is 400x400 at (0, 32).
        val cell = 400f / SlidingGame.SIZE
        val row = idx / SlidingGame.SIZE
        val col = idx % SlidingGame.SIZE
        g.x = col * cell + cell / 2f
        g.y = 32f + row * cell + cell / 2f
        g.pointerId = 0
        return g
    }
}
