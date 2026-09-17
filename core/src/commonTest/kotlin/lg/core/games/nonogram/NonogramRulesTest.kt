package lg.core.games.nonogram

import lg.core.GameStatus
import lg.core.util.Rng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

public class NonogramRulesTest {
    private fun newGame(seed: Long = 1L): NonogramGame {
        val game = NonogramGame()
        game.resize(360f, 640f)
        game.reset(seed)
        return game
    }

    @Test
    public fun cluesAreComputedCorrectlyFromAPicture(): Unit {
        // 5x5 picture, row 0 = "1 1 0 1 1" -> clues [1,1]; row 2 = "0 0 0 0 0" -> no clues.
        val size = 5
        val picture = BooleanArray(size * size)
        picture[0] = true
        picture[1] = true
        picture[3] = true
        picture[4] = true
        val rowClueCount = IntArray(size)
        val rowClues = IntArray(size * NonogramGenerator.MAX_CLUES)
        val colClueCount = IntArray(size)
        val colClues = IntArray(size * NonogramGenerator.MAX_CLUES)
        NonogramGenerator.computeClues(size, picture, rowClueCount, rowClues, colClueCount, colClues)

        assertEquals(2, rowClueCount[0])
        assertEquals(2, rowClues[0 * NonogramGenerator.MAX_CLUES + 0])
        assertEquals(2, rowClues[0 * NonogramGenerator.MAX_CLUES + 1])
        assertEquals(0, rowClueCount[2])
        assertEquals(1, colClueCount[0]) // column 0 has only row 0 filled
        assertEquals(1, colClues[0 * NonogramGenerator.MAX_CLUES + 0])
    }

    @Test
    public fun lineSolverDeducesAFullyConstrainedLine(): Unit {
        // A line of length 5 with a single clue [5] must be fully filled.
        val state = IntArray(5) { LineSolver.UNKNOWN }
        val budget = LineSolver.Budget(10_000)
        val scratch = LineSolver.Scratch(5)
        val changed = LineSolver.deduceLine(5, intArrayOf(5), 1, state, budget, scratch)
        assertTrue(changed)
        for (v in state) assertEquals(LineSolver.FILLED, v)
    }

    @Test
    public fun lineSolverDeducesTheOverlapOfATightClue(): Unit {
        // Length 5, clue [4]: placements are positions 0..3 or 1..4, so cells 1,2,3 are forced filled.
        val state = IntArray(5) { LineSolver.UNKNOWN }
        val budget = LineSolver.Budget(10_000)
        val scratch = LineSolver.Scratch(5)
        LineSolver.deduceLine(5, intArrayOf(4), 1, state, budget, scratch)
        assertEquals(LineSolver.FILLED, state[1])
        assertEquals(LineSolver.FILLED, state[2])
        assertEquals(LineSolver.FILLED, state[3])
        assertEquals(LineSolver.UNKNOWN, state[0])
        assertEquals(LineSolver.UNKNOWN, state[4])
    }

    @Test
    public fun lineSolverMarksAnEmptyLineWhenThereAreNoClues(): Unit {
        val state = IntArray(4) { LineSolver.UNKNOWN }
        val budget = LineSolver.Budget(10_000)
        val scratch = LineSolver.Scratch(4)
        val changed = LineSolver.deduceLine(4, IntArray(0), 0, state, budget, scratch)
        assertTrue(changed)
        for (v in state) assertEquals(LineSolver.EMPTY, v)
    }

    @Test
    public fun generatedEasyPuzzlesAreUsuallySolvableByPureLogic(): Unit {
        // Not a hard guarantee (bounded attempts), but density/size for EASY
        // should make this true for the vast majority of seeds.
        var solvableCount = 0
        val trials = 10
        for (seed in 0 until trials) {
            val puzzle = NonogramGenerator.generate(Rng(seed.toLong()), NonogramGenerator.Difficulty.EASY)
            if (puzzle.solvableByLogic) solvableCount++
        }
        assertTrue(solvableCount >= trials - 2, "expected most EASY puzzles to be logic-solvable, got $solvableCount/$trials")
    }

    @Test
    public fun generationStaysWellUnderTheGenerousTestBound(): Unit {
        val elapsed = kotlin.time.measureTime {
            for (seed in 0 until 5) {
                NonogramGenerator.generate(Rng(seed.toLong()), NonogramGenerator.Difficulty.HARD)
            }
        }
        assertTrue(elapsed.inWholeMilliseconds < 7500, "5 HARD (15x15) generations took ${elapsed.inWholeMilliseconds}ms")
    }

    @Test
    public fun generatedPictureMatchesItsOwnClues(): Unit {
        val puzzle = NonogramGenerator.generate(Rng(2L), NonogramGenerator.Difficulty.MEDIUM)
        val rowClueCount = IntArray(puzzle.size)
        val rowClues = IntArray(puzzle.size * NonogramGenerator.MAX_CLUES)
        val colClueCount = IntArray(puzzle.size)
        val colClues = IntArray(puzzle.size * NonogramGenerator.MAX_CLUES)
        NonogramGenerator.computeClues(puzzle.size, puzzle.picture, rowClueCount, rowClues, colClueCount, colClues)
        assertEquals(puzzle.rowClueCount.toList(), rowClueCount.toList())
        assertEquals(puzzle.colClueCount.toList(), colClueCount.toList())
    }

    @Test
    public fun tappingPaintsACellFilled(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(NonogramGenerator.Difficulty.EASY)
        game.debugPaintDown(0)
        game.debugPaintUp()
        assertEquals(NonogramGame.STATE_FILLED, game.debugCellState(0))
    }

    @Test
    public fun tappingAgainClearsTheCell(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(NonogramGenerator.Difficulty.EASY)
        game.debugPaintDown(0)
        game.debugPaintUp()
        game.debugPaintDown(0)
        game.debugPaintUp()
        assertEquals(NonogramGame.STATE_BLANK, game.debugCellState(0))
    }

    @Test
    public fun dragPaintsTheSameValueAcrossCells(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(NonogramGenerator.Difficulty.EASY)
        game.debugPaintDown(0) // cell 0 was blank -> paints FILLED
        game.debugPaintMove(1)
        game.debugPaintMove(2)
        game.debugPaintUp()
        assertEquals(NonogramGame.STATE_FILLED, game.debugCellState(0))
        assertEquals(NonogramGame.STATE_FILLED, game.debugCellState(1))
        assertEquals(NonogramGame.STATE_FILLED, game.debugCellState(2))
    }

    @Test
    public fun longPressTogglesACrossMark(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(NonogramGenerator.Difficulty.EASY)
        game.debugToggleCross(0)
        assertEquals(NonogramGame.STATE_CROSSED, game.debugCellState(0))
        game.debugToggleCross(0)
        assertEquals(NonogramGame.STATE_BLANK, game.debugCellState(0))
    }

    @Test
    public fun fillingExactlyThePictureWins(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(NonogramGenerator.Difficulty.EASY)
        val size = game.debugSize()
        for (i in 0 until size * size) {
            if (game.debugPicture(i)) {
                game.debugPaintDown(i)
                game.debugPaintUp()
            }
        }
        assertTrue(game.status is GameStatus.Over)
        assertEquals(0, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun crossesDoNotAffectWinDetection(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(NonogramGenerator.Difficulty.EASY)
        val size = game.debugSize()
        for (i in 0 until size * size) {
            if (game.debugPicture(i)) {
                game.debugPaintDown(i)
                game.debugPaintUp()
            } else {
                game.debugToggleCross(i)
            }
        }
        assertTrue(game.status is GameStatus.Over)
    }

    @Test
    public fun snapshotRestoreRoundTripsInChoosingMode(): Unit {
        val game = newGame(seed = 9L)
        val data = game.snapshot()
        assertTrue(data != null)
        val restored = NonogramGame()
        restored.resize(360f, 640f)
        assertTrue(restored.restore(data))
        assertTrue(restored.debugChoosing())
    }

    @Test
    public fun snapshotRestoreRoundTripsMidGame(): Unit {
        val game = newGame(seed = 10L)
        game.debugStartWithDifficulty(NonogramGenerator.Difficulty.MEDIUM)
        game.debugPaintDown(0)
        game.debugPaintUp()
        game.debugToggleCross(1)

        val data = game.snapshot()
        assertTrue(data != null)
        val restored = NonogramGame()
        restored.resize(360f, 640f)
        assertTrue(restored.restore(data))
        assertFalse(restored.debugChoosing())
        assertEquals(game.debugSize(), restored.debugSize())
        assertEquals(game.score, restored.score)
        val size = game.debugSize()
        for (i in 0 until size * size) {
            assertEquals(game.debugPicture(i), restored.debugPicture(i))
            assertEquals(game.debugCellState(i), restored.debugCellState(i))
        }
    }

    @Test
    public fun restoreRejectsCorruptData(): Unit {
        val game = newGame()
        assertFalse(game.restore("not a valid save"))
        assertFalse(game.restore(""))
    }
}
