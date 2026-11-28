package lg.core.games.mixup

import lg.core.util.Rng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

public class MixUpRulesTest {
    private val cols = MixUpBoard.COLS

    private fun at(col: Int, row: Int) = row * MixUpBoard.COLS + col

    /** A board with nothing aligned and no move: shapes 0..3 in a pattern that never repeats within two cells. */
    private fun plain(): MixUpBoard {
        val b = MixUpBoard()
        for (i in 0 until MixUpBoard.CELLS) b.shape[i] = (i % cols + 2 * (i / cols)) % 4
        return b
    }

    private fun marked(b: MixUpBoard) = (0 until MixUpBoard.CELLS).filter { b.marked[it] }.toSet()

    @Test
    public fun theTestPatternHasNothingToClear() {
        assertFalse(plain().computeClear())
    }

    @Test
    public fun generatedBoardsStartQuietAndPlayable() {
        for (seed in 1L..200L) {
            val b = MixUpBoard()
            b.colors = if (seed % 2 == 0L) 5 else 6
            b.generate(Rng(seed))
            assertTrue((0 until MixUpBoard.CELLS).all { b.shape[it] in 0 until b.colors })
            assertFalse(b.computeClear(), "seed $seed starts with a line")
            assertTrue(b.hasMove(), "seed $seed has no move")
        }
    }

    @Test
    public fun aLineOfThreeClearsAndShapesFall() {
        val b = plain()
        for (c in 0..2) b.shape[at(c, 8)] = 5
        val above = b.shape[at(1, 7)]
        assertTrue(b.computeClear())
        assertEquals(setOf(at(0, 8), at(1, 8), at(2, 8)), marked(b))
        assertEquals(3, b.applyClear())
        assertEquals(3, b.cleared[5])
        assertEquals(1, b.fall(Rng(1)))
        assertEquals(above, b.shape[at(1, 8)])
        assertTrue(b.shape.none { it == MixUpBoard.EMPTY })
    }

    @Test
    public fun aSwapMustLineSomethingUp() {
        val b = plain()
        b.shape[at(0, 8)] = 5
        b.shape[at(1, 8)] = 5
        b.shape[at(2, 7)] = 5
        assertTrue(b.isLegalSwap(at(2, 7), at(2, 8)))
        assertFalse(b.isLegalSwap(at(4, 0), at(5, 0)))
        assertFalse(b.isLegalSwap(at(0, 0), at(2, 0)), "not neighbours")
        assertEquals(5, b.shape[at(2, 7)], "testing a swap leaves the board as it was")
    }

    @Test
    public fun fourInARowMakesARowClearerThatEmptiesItsRow() {
        val b = plain()
        for (c in 0..3) b.shape[at(c, 8)] = 5
        b.computeClear(preferA = at(2, 8))
        assertEquals(4, b.applyClear())
        assertEquals(MixUpBoard.ROW_CLEARER, b.special[at(2, 8)])
        assertEquals(5, b.shape[at(2, 8)], "the bonus keeps its shape")
        b.fall(Rng(1))

        val c = plain()
        for (x in 0..2) c.shape[at(x, 5)] = 5
        c.special[at(1, 5)] = MixUpBoard.ROW_CLEARER
        c.computeClear()
        assertEquals((0 until cols).map { at(it, 5) }.toSet(), marked(c))
    }

    @Test
    public fun fourInAColumnMakesAColumnClearer() {
        val b = plain()
        for (y in 5..8) b.shape[at(3, y)] = 5
        b.computeClear()
        b.applyClear()
        assertEquals(1, (5..8).count { b.special[at(3, it)] == MixUpBoard.COLUMN_CLEARER })
    }

    @Test
    public fun fiveMakesABombThatTakesAKind() {
        val b = plain()
        for (c in 0..4) b.shape[at(c, 8)] = 5
        b.computeClear()
        b.applyClear()
        val bomb = (0..4).map { at(it, 8) }.single { b.special[it] == MixUpBoard.BOMB }
        assertEquals(MixUpBoard.BOMB_SHAPE, b.shape[bomb])

        val c = plain()
        val at = at(3, 4)
        c.shape[at] = MixUpBoard.BOMB_SHAPE
        c.special[at] = MixUpBoard.BOMB
        val other = at + 1
        val kind = c.shape[other]
        val ofThatKind = (0 until MixUpBoard.CELLS).count { c.shape[it] == kind }
        assertTrue(c.isLegalSwap(at, other))
        c.swap(at, other)
        c.computeBombSwap(at, other)
        assertEquals(ofThatKind + 1, marked(c).size)
        assertTrue(marked(c).all { it == other || c.shape[it] == kind })
    }

    @Test
    public fun twoBombsClearEverythingAndBonusesFireOnce() {
        val b = plain()
        for (i in listOf(at(2, 2), at(3, 2))) {
            b.shape[i] = MixUpBoard.BOMB_SHAPE
            b.special[i] = MixUpBoard.BOMB
        }
        b.special[at(0, 0)] = MixUpBoard.ROW_CLEARER
        b.special[at(5, 0)] = MixUpBoard.COLUMN_CLEARER
        b.computeBombSwap(at(2, 2), at(3, 2))
        assertEquals(MixUpBoard.CELLS, marked(b).size)
        assertEquals(MixUpBoard.CELLS, b.applyClear())
    }

    @Test
    public fun iceBreaksOneLayerPerClearAndStaysInItsCell() {
        val b = plain()
        for (c in 0..2) b.shape[at(c, 8)] = 5
        b.ice[at(0, 8)] = 2
        b.ice[at(1, 8)] = 1
        b.ice[at(6, 8)] = 1
        b.computeClear()
        b.applyClear()
        assertEquals(1, b.ice[at(0, 8)])
        assertEquals(0, b.ice[at(1, 8)])
        assertEquals(2, b.iceLeft)
    }

    @Test
    public fun shuffleGivesAPlayableQuietBoardAndKeepsTheIce() {
        val b = plain()
        b.colors = 4
        b.ice[at(2, 2)] = 2
        assertFalse(b.hasMove(), "the pattern is stuck")
        b.shuffle(Rng(3))
        assertTrue(b.hasMove())
        assertFalse(b.computeClear())
        assertEquals(2, b.ice[at(2, 2)])
    }
}
