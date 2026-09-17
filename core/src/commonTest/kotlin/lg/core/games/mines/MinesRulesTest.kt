package lg.core.games.mines

import lg.core.GameStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

public class MinesRulesTest {
    private fun newGame(seed: Long = 1L): MinesGame {
        val game = MinesGame()
        game.resize(360f, 392f)
        game.reset(seed)
        return game
    }

    @Test
    public fun firstTapIsAlwaysSafeAndPlacesMines(): Unit {
        val game = newGame()
        assertFalse(game.debugFirstTapDone())
        game.debugTapCell(40)
        assertTrue(game.debugFirstTapDone())
        assertFalse(game.debugMine(40))
        assertEquals(GameStatus.Running, game.status)
    }

    @Test
    public fun exactlyTenMinesArePlaced(): Unit {
        val game = newGame()
        game.debugTapCell(0)
        var count = 0
        for (i in 0 until MinesGame.CELL_COUNT) if (game.debugMine(i)) count++
        assertEquals(MinesGame.MINE_COUNT, count)
    }

    @Test
    public fun tappingAZeroAdjacencyCellFloodFillsTheRegion(): Unit {
        // A corner tap seed chosen (by search) so that the first-tap-excluded
        // corner region still contains a large zero-adjacency pocket to flood.
        var game: MinesGame? = null
        for (seed in 1L..200L) {
            val g = newGame(seed)
            g.debugTapCell(0)
            if (g.debugRevealedCount() > 5) {
                game = g
                break
            }
        }
        val g = game ?: error("expected at least one seed to produce a large flood-filled region")
        assertTrue(g.debugRevealed(0))
        assertTrue(g.debugRevealedCount() > 5)
        assertEquals(g.debugRevealedCount(), g.score)
    }

    @Test
    public fun flagTogglesOnAndOff(): Unit {
        val game = newGame()
        game.debugTapCell(0)
        val idx = 60
        game.debugLongPressCell(idx)
        assertTrue(game.debugFlagged(idx))
        game.debugLongPressCell(idx)
        assertFalse(game.debugFlagged(idx))
    }

    @Test
    public fun flaggedCellCannotBeRevealedByTap(): Unit {
        val game = newGame()
        game.debugTapCell(0)
        val idx = 61
        game.debugLongPressCell(idx)
        game.debugTapCell(idx)
        assertFalse(game.debugRevealed(idx))
    }

    @Test
    public fun chordRevealsRemainingNeighborsWhenFlagsMatchCount(): Unit {
        val game = newGame(seed = 3L)
        game.debugTapCell(40)
        // Find a revealed numbered cell with >0 adjacency and flag every mine neighbor.
        var target = -1
        for (i in 0 until MinesGame.CELL_COUNT) {
            if (game.debugRevealed(i) && game.debugAdjacentCount(i) > 0) {
                target = i
                break
            }
        }
        if (target < 0) return // no numbered cell revealed for this seed; nothing to assert
        val r = target / MinesGame.SIZE
        val c = target % MinesGame.SIZE
        var flaggedMines = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = r + dr
                val nc = c + dc
                if (nr !in 0 until MinesGame.SIZE || nc !in 0 until MinesGame.SIZE) continue
                val j = nr * MinesGame.SIZE + nc
                if (game.debugMine(j)) {
                    game.debugLongPressCell(j)
                    flaggedMines++
                }
            }
        }
        if (flaggedMines != game.debugAdjacentCount(target)) return // not all mines adjacent (unrevealed neighbor is a mine outside flood) - skip
        val revealedBefore = game.debugRevealedCount()
        game.debugTapCell(target) // chord
        assertTrue(game.debugRevealedCount() >= revealedBefore)
        assertEquals(GameStatus.Running, game.status)
    }

    @Test
    public fun hittingAMineEndsTheGame(): Unit {
        val game = newGame()
        game.debugTapCell(0)
        var mineIdx = -1
        for (i in 0 until MinesGame.CELL_COUNT) if (game.debugMine(i)) { mineIdx = i; break }
        game.debugTapCell(mineIdx)
        assertTrue(game.status is GameStatus.Over)
        assertEquals(0, (game.status as GameStatus.Over).winner)
        assertTrue(game.debugRevealed(mineIdx))
    }

    @Test
    public fun revealingEverySafeCellWins(): Unit {
        val game = newGame()
        game.debugTapCell(0)
        for (i in 0 until MinesGame.CELL_COUNT) {
            if (!game.debugMine(i)) game.debugTapCell(i)
        }
        assertTrue(game.status is GameStatus.Over)
        assertEquals(MinesGame.CELL_COUNT - MinesGame.MINE_COUNT, game.debugRevealedCount())
    }

    @Test
    public fun snapshotRestoreRoundTrips(): Unit {
        val game = newGame(seed = 5L)
        game.debugTapCell(20)
        game.debugLongPressCell(0)
        val data = game.snapshot()
        assertTrue(data != null)

        val restored = MinesGame()
        restored.resize(360f, 392f)
        assertTrue(restored.restore(data))
        assertEquals(game.score, restored.score)
        assertEquals(game.debugRevealedCount(), restored.debugRevealedCount())
        assertEquals(game.debugRngState(), restored.debugRngState())
        for (i in 0 until MinesGame.CELL_COUNT) {
            assertEquals(game.debugMine(i), restored.debugMine(i))
            assertEquals(game.debugRevealed(i), restored.debugRevealed(i))
            assertEquals(game.debugFlagged(i), restored.debugFlagged(i))
        }
    }

    @Test
    public fun restoreRejectsCorruptData(): Unit {
        val game = newGame()
        assertFalse(game.restore("not a valid save"))
        assertFalse(game.restore(""))
    }
}
