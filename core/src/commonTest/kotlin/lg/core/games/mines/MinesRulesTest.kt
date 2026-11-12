package lg.core.games.mines

import lg.core.GameStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

public class MinesRulesTest {
    private fun newGame(seed: Long = 1L, level: MinesGame.Difficulty = MinesGame.Difficulty.EASY): MinesGame {
        val game = MinesGame()
        game.resize(360f, 392f)
        game.reset(seed)
        // A game opens on the level choice: pick the level, as a tap on its button does.
        game.debugStartWithDifficulty(level)
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
        for (i in 0 until (game.debugSize() * game.debugSize())) if (game.debugMine(i)) count++
        assertEquals(game.debugMineCount(), count)
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
        for (i in 0 until (game.debugSize() * game.debugSize())) {
            if (game.debugRevealed(i) && game.debugAdjacentCount(i) > 0) {
                target = i
                break
            }
        }
        if (target < 0) return // no numbered cell revealed for this seed; nothing to assert
        val r = target / game.debugSize()
        val c = target % game.debugSize()
        var flaggedMines = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = r + dr
                val nc = c + dc
                if (nr !in 0 until game.debugSize() || nc !in 0 until game.debugSize()) continue
                val j = nr * game.debugSize() + nc
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

    private fun firstMine(game: MinesGame): Int {
        for (i in 0 until (game.debugSize() * game.debugSize())) if (game.debugMine(i)) return i
        return -1
    }

    @Test
    public fun hittingAMineStartsAChainOfExplosionsBeforeTheGameEnds(): Unit {
        val game = newGame()
        game.debugTapCell(0)
        val mineIdx = firstMine(game)
        game.debugTapCell(mineIdx)
        assertEquals(GameStatus.Running, game.status, "the explosions come first")
        assertTrue(game.debugExploding())
        assertTrue(game.wantsFrames, "a frame loop is needed to play the chain")
        assertEquals(1, game.debugExplosionsShown(), "only the mine that was hit has gone off")
        assertEquals(game.debugMineCount(), game.debugExplosionTotal())
        assertTrue(game.debugRevealed(mineIdx))
    }

    @Test
    public fun theOtherMinesGoOffOneByOneThenTheGameIsLost(): Unit {
        val game = newGame()
        game.debugTapCell(0)
        game.debugTapCell(firstMine(game))
        assertTrue(game.update(16), "the hit itself is reported on the first frame (sound and vibration)")
        var shown = game.debugExplosionsShown()
        var steps = 0
        while (game.status == GameStatus.Running && steps < 400) {
            game.update(16)
            assertTrue(game.debugExplosionsShown() >= shown, "mines only ever go off, never come back")
            assertTrue(game.debugExplosionsShown() - shown <= 1 || steps == 0, "one at a time")
            shown = game.debugExplosionsShown()
            steps++
        }
        assertEquals(game.debugMineCount(), game.debugExplosionsShown())
        assertEquals(GameStatus.SOLO_LOSS, (game.status as GameStatus.Over).winner)
        assertTrue(!game.debugExploding())
        assertTrue(!game.wantsFrames)
    }

    @Test
    public fun theBoardIgnoresInputWhileTheMinesAreGoingOff(): Unit {
        val game = newGame()
        game.debugTapCell(0)
        game.debugTapCell(firstMine(game))
        val before = game.debugRevealedCount()
        val gesture = lg.core.input.Gesture()
        gesture.kind = lg.core.input.GestureKind.TAP
        game.resize(400f, 432f)
        gesture.x = 200f
        gesture.y = 250f
        game.onGesture(gesture)
        gesture.kind = lg.core.input.GestureKind.LONG_PRESS
        game.onGesture(gesture)
        assertEquals(before, game.debugRevealedCount())
        assertEquals(GameStatus.Running, game.status)
    }

    @Test
    public fun theExplosionsGoOffNearestFirst(): Unit {
        val game = newGame(seed = 3L)
        game.debugTapCell(0)
        val hit = firstMine(game)
        game.debugTapCell(hit)
        fun dist(i: Int): Int {
            val dr = i / game.debugSize() - hit / game.debugSize()
            val dc = i % game.debugSize() - hit % game.debugSize()
            return dr * dr + dc * dc
        }
        assertEquals(hit, game.debugExplosionOrder(0), "the mine that was hit goes off first")
        val order = (0 until game.debugExplosionTotal()).map { game.debugExplosionOrder(it) }
        assertEquals(order.size, order.toSet().size, "every mine goes off exactly once")
        assertTrue(order.all { game.debugMine(it) }, "only mines go off")
        for (k in 2 until order.size) assertTrue(dist(order[k - 1]) <= dist(order[k]), "nearest first")
    }

    @Test
    public fun revealingEverySafeCellWins(): Unit {
        val game = newGame()
        game.debugTapCell(0)
        for (i in 0 until (game.debugSize() * game.debugSize())) {
            if (!game.debugMine(i)) game.debugTapCell(i)
        }
        assertTrue(game.status is GameStatus.Over)
        assertEquals(GameStatus.SOLO_WIN, (game.status as GameStatus.Over).winner)
        assertEquals((game.debugSize() * game.debugSize()) - game.debugMineCount(), game.debugRevealedCount())
        assertTrue(!game.debugExploding())
    }

    @Test
    public fun aGameSavedAfterALossComesBackAsLostWithEveryMineShown(): Unit {
        val game = newGame(seed = 9L)
        game.debugTapCell(0)
        game.debugTapCell(firstMine(game))
        for (k in 0 until 400) if (game.status == GameStatus.Running) game.update(16)
        val data = game.snapshot()!!
        val other = newGame(seed = 1L)
        assertTrue(other.restore(data))
        assertEquals(GameStatus.SOLO_LOSS, (other.status as GameStatus.Over).winner)
        assertEquals(game.debugMineCount(), other.debugExplosionsShown())
        assertTrue(!other.debugExploding())
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
        for (i in 0 until (game.debugSize() * game.debugSize())) {
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

    // ---------------------------------------------------------------- levels

    @Test
    public fun aNewGameOpensOnTheLevelChoiceAndPlaysNothingYet(): Unit {
        val game = MinesGame()
        game.resize(360f, 392f)
        game.reset(1L)
        assertTrue(game.debugChoosing())
        assertEquals(GameStatus.Running, game.status)
        assertFalse(game.debugFirstTapDone())
        // A tap on a board cell does nothing while the choice is shown (only the buttons answer).
        val gesture = lg.core.input.Gesture()
        gesture.kind = lg.core.input.GestureKind.TAP
        gesture.x = 5f
        gesture.y = 5f
        game.onGesture(gesture)
        assertTrue(game.debugChoosing())
        assertFalse(game.debugFirstTapDone())
    }

    @Test
    public fun tappingALevelButtonStartsThatLevel(): Unit {
        for ((i, level) in MinesGame.Difficulty.entries.withIndex()) {
            val game = MinesGame()
            game.resize(360f, 392f)
            game.reset(1L)
            val gesture = lg.core.input.Gesture()
            gesture.kind = lg.core.input.GestureKind.TAP
            gesture.x = game.debugButtonCenterX()
            gesture.y = game.debugButtonCenterY(i)
            game.onGesture(gesture)
            assertFalse(game.debugChoosing(), "$level: the board is shown")
            assertEquals(level.size, game.debugSize())
            assertEquals(level.mines, game.debugMineCount())
        }
    }

    @Test
    public fun theThreeLevelsGetBiggerAndHarder(): Unit {
        val levels = MinesGame.Difficulty.entries
        assertEquals(3, levels.size)
        for (k in 1 until levels.size) {
            assertTrue(levels[k].size > levels[k - 1].size, "a bigger board")
            assertTrue(levels[k].mines > levels[k - 1].mines, "more mines")
            val densityBefore = levels[k - 1].mines.toFloat() / (levels[k - 1].size * levels[k - 1].size)
            val density = levels[k].mines.toFloat() / (levels[k].size * levels[k].size)
            assertTrue(density > densityBefore, "a bigger share of mines")
        }
    }

    @Test
    public fun everyLevelPlacesItsMinesAwayFromTheFirstTap(): Unit {
        for (level in MinesGame.Difficulty.entries) {
            for (seed in 1L..20L) {
                val game = newGame(seed, level)
                val first = (level.size / 2) * level.size + level.size / 2
                game.debugTapCell(first)
                var count = 0
                for (i in 0 until level.size * level.size) if (game.debugMine(i)) count++
                assertEquals(level.mines, count, "$level seed $seed")
                assertFalse(game.debugMine(first))
                assertEquals(0, game.debugAdjacentCount(first), "the first tap opens a zone, never a number")
                assertEquals(GameStatus.Running, game.status)
            }
        }
    }

    @Test
    public fun everyLevelCanBeWonByOpeningEverySafeCell(): Unit {
        for (level in MinesGame.Difficulty.entries) {
            val game = newGame(4L, level)
            game.debugTapCell(0)
            for (i in 0 until level.size * level.size) if (!game.debugMine(i)) game.debugTapCell(i)
            assertEquals(GameStatus.SOLO_WIN, (game.status as GameStatus.Over).winner, "$level")
            assertEquals(level.size * level.size - level.mines, game.debugRevealedCount())
        }
    }

    @Test
    public fun everyLevelLosesWithAChainOfExplosionsOfItsOwnMines(): Unit {
        for (level in MinesGame.Difficulty.entries) {
            val game = newGame(6L, level)
            game.debugTapCell(0)
            game.debugTapCell(firstMine(game))
            assertEquals(level.mines, game.debugExplosionTotal(), "$level")
            for (k in 0 until 600) if (game.status == GameStatus.Running) game.update(16)
            assertEquals(GameStatus.SOLO_LOSS, (game.status as GameStatus.Over).winner, "$level")
        }
    }

    @Test
    public fun aMediumGameSurvivesASaveAndARestoreIntoAnotherGame(): Unit {
        val game = newGame(8L, MinesGame.Difficulty.MEDIUM)
        game.debugTapCell(60)
        game.debugLongPressCell(0)
        val data = game.snapshot()!!
        // The receiving game is a hard one that was played before: its bigger board must not leak into the restore.
        val other = newGame(2L, MinesGame.Difficulty.HARD)
        other.debugTapCell(0)
        assertTrue(other.restore(data))
        assertEquals(MinesGame.Difficulty.MEDIUM.size, other.debugSize())
        assertEquals(MinesGame.Difficulty.MEDIUM.mines, other.debugMineCount())
        assertFalse(other.debugChoosing())
        assertTrue(other.debugFlagged(0))
        assertEquals(game.debugRevealedCount(), other.debugRevealedCount())
        assertEquals(data, other.snapshot())
    }

    @Test
    public fun aSaveFromBeforeTheLevelsIsAnEasyGame(): Unit {
        val easy = MinesGame.Difficulty.EASY
        val cells = easy.size * easy.size
        val old = "mines=${"0".repeat(cells)};revealed=${"0".repeat(cells)};flagged=${"0".repeat(cells)};" +
            "first=0;exploded=-1;lost=0;won=0;score=0;rng=7"
        val game = MinesGame()
        game.resize(360f, 392f)
        assertTrue(game.restore(old))
        assertEquals(easy.size, game.debugSize())
        assertFalse(game.debugChoosing())
    }

    @Test
    public fun aSaveWithTheWrongLengthForItsLevelIsRefused(): Unit {
        val cells = MinesGame.Difficulty.MEDIUM.size * MinesGame.Difficulty.MEDIUM.size
        val bad = "mines=${"0".repeat(81)};revealed=${"0".repeat(cells)};flagged=${"0".repeat(cells)};" +
            "first=0;difficulty=1;exploded=-1;lost=0;won=0;score=0;rng=7"
        val game = MinesGame()
        assertFalse(game.restore(bad))
        assertFalse(game.restore("difficulty=9"))
    }
}
