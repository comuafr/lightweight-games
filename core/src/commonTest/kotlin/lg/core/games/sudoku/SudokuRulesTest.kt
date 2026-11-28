package lg.core.games.sudoku

import lg.core.scene.SoundId
import lg.core.scene.Scene
import lg.core.scene.Palette
import lg.core.input.GestureKind
import lg.core.input.Gesture
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
    public fun aWrongEntryCountsLikeARightOneSoNothingGivesItAway(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(SudokuGenerator.Difficulty.EASY)
        val blank = (0 until 81).first { game.debugGiven(it) == 0 }
        game.debugSetSelected(blank)
        val correct = game.debugSolution(blank)
        val wrong = if (correct == 9) 1 else correct + 1
        val scoreBefore = game.score
        game.debugApplyNumpadKey(wrong)
        assertEquals(wrong, game.debugCurrent(blank))
        assertEquals(scoreBefore + 1, game.score)
        assertEquals(GameStatus.Running, game.status)
        // Same color, same sound: the entry is drawn like a right one.
        val scene = Scene()
        game.resize(360f, 560f)
        game.render(scene)
        assertTrue((0 until scene.count).none { scene.color[it] == Palette.DANGER })
        assertEquals(SoundId.EAT.bit, scene.soundMask)
    }

    private fun blankOf(game: SudokuGame): Int {
        val blank = (0 until 81).first { game.debugGiven(it) == 0 }
        game.debugSetSelected(blank)
        return blank
    }

    private fun tap(game: SudokuGame, x: Float, y: Float) {
        game.onGesture(Gesture().also { it.kind = GestureKind.TAP; it.x = x; it.y = y })
    }

    /** Taps slot [slot] (0..11, row by row) of the six-column pad of a 360 x 560 zone: board 55..415, pad from 423. */
    private fun tapKey(game: SudokuGame, slot: Int) = tap(game, 6f + (slot % 6) * 53f + 26f, 423f + (slot / 6) * 48f + 24f)

    @Test
    public fun thePadHasDigitsEraseAndAWidePencil(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(SudokuGenerator.Difficulty.EASY)
        game.resize(360f, 560f)
        val keys = (0 until 12).map { game.debugKeyAt(6f + (it % 6) * 53f + 26f, 423f + (it / 6) * 48f + 24f) }
        assertEquals(listOf(1, 2, 3, 4, 5, 0, 6, 7, 8, 9, SudokuGame.PENCIL_KEY, SudokuGame.PENCIL_KEY), keys)
    }

    @Test
    public fun thePencilWritesAndRemovesNotesInEmptyCellsOnly(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(SudokuGenerator.Difficulty.HARD)
        game.resize(360f, 560f)
        val cell = blankOf(game)
        tapKey(game, 10)
        assertTrue(game.debugPencil())
        tapKey(game, 0)
        tapKey(game, 9) // digit 9
        assertEquals(1 or 256, game.debugNotes(cell))
        assertEquals(0, game.debugCurrent(cell))
        tapKey(game, 0)
        assertEquals(256, game.debugNotes(cell))

        // A digit in the cell hides the notes; erasing it brings them back, erasing again removes them.
        tapKey(game, 11)
        assertTrue(!game.debugPencil())
        tapKey(game, 2)
        assertEquals(3, game.debugCurrent(cell))
        tapKey(game, 10)
        tapKey(game, 4) // pencil on a filled cell: nothing
        assertEquals(256, game.debugNotes(cell))
        tapKey(game, 5)
        assertEquals(0, game.debugCurrent(cell))
        assertEquals(256, game.debugNotes(cell))
        tapKey(game, 5)
        assertEquals(0, game.debugNotes(cell))

        val givenCell = (0 until 81).first { game.debugGiven(it) != 0 }
        game.debugSetSelected(givenCell)
        tapKey(game, 0)
        assertEquals(0, game.debugNotes(givenCell))
    }

    @Test
    public fun notesAndPencilAreSavedAndOldSavesStillLoad(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(SudokuGenerator.Difficulty.HARD)
        game.resize(360f, 560f)
        val cell = blankOf(game)
        tapKey(game, 10)
        tapKey(game, 3)
        val data = game.snapshot()!!
        val restored = SudokuGame()
        assertTrue(restored.restore(data))
        assertEquals(8, restored.debugNotes(cell))
        assertTrue(restored.debugPencil())

        // The format of version 1.0.7 had neither notes nor pencil.
        val old = data.split(";").filterNot { it.startsWith("notes=") || it.startsWith("pencil=") }.joinToString(";")
        val fromOld = SudokuGame()
        assertTrue(fromOld.restore(old))
        assertEquals(0, fromOld.debugNotes(cell))
        assertTrue(!fromOld.debugPencil())
        assertTrue(!SudokuGame().restore(data.replace("pencil=1", "pencil=7")))
        assertTrue(!SudokuGame().restore("$old;notes=1,2"))
    }

    /** Fills every blank, the last one wrongly. @return that last cell. */
    private fun fillWithOneMistake(game: SudokuGame): Int {
        val blanks = (0 until 81).filter { game.debugGiven(it) == 0 }
        for (i in blanks.dropLast(1)) {
            game.debugSetSelected(i)
            game.debugApplyNumpadKey(game.debugSolution(i))
        }
        assertTrue(!game.debugAsking())
        val last = blanks.last()
        game.debugSetSelected(last)
        game.debugApplyNumpadKey(game.debugSolution(last) % 9 + 1)
        return last
    }

    private fun easyGame(): SudokuGame {
        val game = newGame()
        game.debugStartWithDifficulty(SudokuGenerator.Difficulty.EASY)
        game.resize(360f, 560f)
        return game
    }

    @Test
    public fun aFullWrongGridAsksAndFixingGoesOn(): Unit {
        val game = easyGame()
        val last = fillWithOneMistake(game)
        assertTrue(game.debugAsking())
        assertEquals(GameStatus.Running, game.status)
        // The grid and the pad wait for an answer.
        tap(game, 20f, 100f)
        assertEquals(last, game.debugSelected())
        // The question survives a save.
        val restored = SudokuGame()
        assertTrue(restored.restore(game.snapshot()!!))
        assertTrue(restored.debugAsking())

        tap(game, 90f, 423f + 68f) // "Fix it"
        assertTrue(!game.debugAsking())
        game.debugApplyNumpadKey(game.debugSolution(last))
        assertEquals(GameStatus.SOLO_WIN, (game.status as GameStatus.Over).winner)
    }

    @Test
    public fun askingForTheSolutionLosesAndShowsItWithTheMistakes(): Unit {
        val game = easyGame()
        fillWithOneMistake(game)
        tap(game, 270f, 423f + 68f) // "Show solution"
        assertEquals(GameStatus.SOLO_LOSS, (game.status as GameStatus.Over).winner)
        assertTrue(game.ownsEndScreen)
        assertTrue(!game.wantsFrames)
        assertTrue(!game.canResume)
        val scene = Scene()
        game.render(scene)
        assertEquals(SoundId.LOSE.bit, scene.soundMask)
        // One wrong cell: one red rectangle and the red title.
        assertEquals(2, (0 until scene.count).count { scene.color[it] == Palette.DANGER })
    }

    @Test
    public fun aWinIsCelebratedForAWhileThenHoldsStill(): Unit {
        val game = easyGame()
        for (i in 0 until 81) {
            if (game.debugGiven(i) != 0) continue
            game.debugSetSelected(i)
            game.debugApplyNumpadKey(game.debugSolution(i))
        }
        assertTrue(game.wantsFrames)
        val scene = Scene()
        var sounds = 0
        var most = 0
        var frames = 0
        while (game.wantsFrames) {
            assertTrue(game.update(50))
            scene.clear()
            game.render(scene)
            assertEquals(0, scene.dropped)
            most = maxOf(most, scene.count)
            if (scene.soundMask and SoundId.WIN.bit != 0) sounds++
            assertTrue(frames++ < 1000)
        }
        assertEquals(1, sounds)
        assertTrue(most < 900, "too many primitives: $most")
    }

    @Test
    public fun everyNoteOfEveryCellFitsInTheScene(): Unit {
        val game = newGame()
        game.debugStartWithDifficulty(SudokuGenerator.Difficulty.HARD)
        game.resize(360f, 560f)
        game.debugApplyNumpadKey(0)
        tapKey(game, 10)
        for (i in 0 until 81) {
            if (game.debugGiven(i) != 0) continue
            game.debugSetSelected(i)
            for (d in 1..9) game.debugApplyNumpadKey(d)
        }
        val scene = Scene()
        game.render(scene)
        assertEquals(0, scene.dropped)
        // The session adds its frame, HUD, overlay and buttons on top.
        assertTrue(scene.count < 950, "too many primitives: ${scene.count}")
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
        assertEquals(GameStatus.SOLO_WIN, (game.status as GameStatus.Over).winner)
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
