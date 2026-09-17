package lg.core.games.sudoku

import lg.core.GameStatus
import lg.core.util.Rng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime

public class SudokuRulesTest {
    private fun newGame(seed: Long = 1L): SudokuGame {
        val game = SudokuGame()
        game.resize(360f, 640f)
        game.reset(seed)
        return game
    }

    private fun assertValidFullGrid(grid: IntArray) {
        for (row in 0 until 9) {
            val seen = BooleanArray(10)
            for (col in 0 until 9) {
                val v = grid[row * 9 + col]
                assertTrue(v in 1..9)
                assertFalse(seen[v], "row $row has a duplicate $v")
                seen[v] = true
            }
        }
        for (col in 0 until 9) {
            val seen = BooleanArray(10)
            for (row in 0 until 9) {
                val v = grid[row * 9 + col]
                assertFalse(seen[v], "col $col has a duplicate $v")
                seen[v] = true
            }
        }
        for (boxRow in 0 until 3) {
            for (boxCol in 0 until 3) {
                val seen = BooleanArray(10)
                for (r in 0 until 3) {
                    for (c in 0 until 3) {
                        val v = grid[(boxRow * 3 + r) * 9 + (boxCol * 3 + c)]
                        assertFalse(seen[v], "box ($boxRow,$boxCol) has a duplicate $v")
                        seen[v] = true
                    }
                }
            }
        }
    }

    @Test
    public fun generatedSolutionIsAFullValidGrid(): Unit {
        val puzzle = SudokuGenerator.generate(Rng(1L), SudokuGenerator.Difficulty.EASY)
        assertValidFullGrid(puzzle.solution)
    }

    @Test
    public fun generatedGivensAreConsistentWithTheSolution(): Unit {
        val puzzle = SudokuGenerator.generate(Rng(2L), SudokuGenerator.Difficulty.MEDIUM)
        for (i in puzzle.given.indices) {
            if (puzzle.given[i] != 0) assertEquals(puzzle.solution[i], puzzle.given[i])
        }
    }

    @Test
    public fun eachDifficultyProducesAUniquelySolvablePuzzle(): Unit {
        for (d in SudokuGenerator.Difficulty.entries) {
            val puzzle = SudokuGenerator.generate(Rng(3L), d)
            val count = SudokuGenerator.debugCountSolutions(puzzle.given, limit = 2)
            assertEquals(1, count, "difficulty $d must have a unique solution")
        }
    }

    @Test
    public fun harderDifficultiesHaveFewerOrEqualGivens(): Unit {
        var easyGivens = 0
        var hardGivens = 0
        for (v in SudokuGenerator.generate(Rng(4L), SudokuGenerator.Difficulty.EASY).given) if (v != 0) easyGivens++
        for (v in SudokuGenerator.generate(Rng(4L), SudokuGenerator.Difficulty.HARD).given) if (v != 0) hardGivens++
        assertTrue(hardGivens <= easyGivens)
    }

    @Test
    public fun generationStaysWellUnderTheGenerousTestBound(): Unit {
        val elapsed = measureTime {
            for (seed in 0 until 5) {
                SudokuGenerator.generate(Rng(seed.toLong()), SudokuGenerator.Difficulty.HARD)
            }
        }
        assertTrue(elapsed.inWholeMilliseconds < 7500, "5 HARD generations took ${elapsed.inWholeMilliseconds}ms")
    }

    @Test
    public fun choosingADifficultyGeneratesAndLeavesChoosingMode(): Unit {
        val game = newGame()
        assertTrue(game.debugChoosing())
        game.debugStartWithDifficulty(SudokuGenerator.Difficulty.EASY)
        assertFalse(game.debugChoosing())
        assertValidFullGrid(IntArray(81) { game.debugSolution(it) })
    }

    @Test
    public fun correctEntryIsAcceptedAndScoreIncreases(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(SudokuGenerator.Difficulty.EASY)
        val blank = (0 until 81).first { game.debugGiven(it) == 0 }
        game.debugSetSelected(blank)
        val scoreBefore = game.score
        game.debugApplyNumpadKey(game.debugSolution(blank))
        assertEquals(game.debugSolution(blank), game.debugCurrent(blank))
        assertTrue(game.score > scoreBefore)
    }

    @Test
    public fun wrongEntryIsStoredButNotCountedAsScore(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(SudokuGenerator.Difficulty.EASY)
        val blank = (0 until 81).first { game.debugGiven(it) == 0 }
        game.debugSetSelected(blank)
        val correct = game.debugSolution(blank)
        val wrong = if (correct == 9) 1 else correct + 1
        game.debugApplyNumpadKey(wrong)
        assertEquals(wrong, game.debugCurrent(blank))
        assertEquals(GameStatus.Running, game.status)
    }

    @Test
    public fun eraseClearsAnEnteredCell(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(SudokuGenerator.Difficulty.EASY)
        val blank = (0 until 81).first { game.debugGiven(it) == 0 }
        game.debugSetSelected(blank)
        game.debugApplyNumpadKey(game.debugSolution(blank))
        game.debugApplyNumpadKey(0)
        assertEquals(0, game.debugCurrent(blank))
    }

    @Test
    public fun givenCellsCannotBeOverwritten(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(SudokuGenerator.Difficulty.EASY)
        val givenIdx = (0 until 81).first { game.debugGiven(it) != 0 }
        game.debugSetSelected(givenIdx)
        val before = game.debugCurrent(givenIdx)
        val other = if (before == 9) 1 else before + 1
        game.debugApplyNumpadKey(other)
        assertEquals(before, game.debugCurrent(givenIdx))
    }

    @Test
    public fun fillingTheWholeGridCorrectlyWins(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(SudokuGenerator.Difficulty.EASY)
        for (i in 0 until 81) {
            if (game.debugGiven(i) == 0) {
                game.debugSetSelected(i)
                game.debugApplyNumpadKey(game.debugSolution(i))
            }
        }
        assertTrue(game.status is GameStatus.Over)
        assertEquals(0, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun snapshotRestoreRoundTripsInChoosingMode(): Unit {
        val game = newGame(seed = 6L)
        val data = game.snapshot()
        assertTrue(data != null)
        val restored = SudokuGame()
        restored.resize(360f, 640f)
        assertTrue(restored.restore(data))
        assertTrue(restored.debugChoosing())
        assertEquals(game.debugRngState(), restored.debugRngState())
    }

    @Test
    public fun snapshotRestoreRoundTripsMidGame(): Unit {
        val game = newGame(seed = 7L)
        game.debugStartWithDifficulty(SudokuGenerator.Difficulty.MEDIUM)
        val blank = (0 until 81).first { game.debugGiven(it) == 0 }
        game.debugSetSelected(blank)
        game.debugApplyNumpadKey(game.debugSolution(blank))

        val data = game.snapshot()
        assertTrue(data != null)
        val restored = SudokuGame()
        restored.resize(360f, 640f)
        assertTrue(restored.restore(data))
        assertFalse(restored.debugChoosing())
        assertEquals(game.score, restored.score)
        for (i in 0 until 81) {
            assertEquals(game.debugGiven(i), restored.debugGiven(i))
            assertEquals(game.debugSolution(i), restored.debugSolution(i))
            assertEquals(game.debugCurrent(i), restored.debugCurrent(i))
        }
    }

    @Test
    public fun restoreRejectsCorruptData(): Unit {
        val game = newGame()
        assertFalse(game.restore("not a valid save"))
        assertFalse(game.restore(""))
    }
}
