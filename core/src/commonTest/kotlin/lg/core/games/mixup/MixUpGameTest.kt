package lg.core.games.mixup

import lg.core.GameStatus
import lg.core.input.PointerPhase
import lg.core.scene.Scene
import lg.core.scene.StringKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

public class MixUpGameTest {
    private fun level(number: Int, seed: Long = 5): MixUpGame {
        val game = MixUpGame()
        game.reset(seed, 1)
        game.resize(360f, 560f)
        game.startLevel(number)
        return game
    }

    private fun move(game: MixUpGame) {
        val move = MixUpBot.bestMove(game)
        game.trySwap(move / 2, move / 2 + if (move % 2 == 0) 1 else MixUpBoard.COLS)
    }

    /** Plays [number] from successive seeds until it ends as asked. */
    private fun finished(number: Int, won: Boolean): MixUpGame {
        for (seed in 1L..200L) {
            val game = level(number, seed)
            while (game.status == GameStatus.Running) {
                move(game)
                MixUpBot.settle(game)
            }
            if (((game.status as GameStatus.Over).winner == GameStatus.SOLO_WIN) == won) return game
        }
        error("level $number never ended with won=$won")
    }

    @Test
    public fun aNewGameShowsTheLevelsAndOnlyReachedOnesOpen() {
        val game = MixUpGame()
        game.reset(1, 1)
        game.resize(360f, 560f)
        assertTrue(game.isChoosing)
        assertFalse(game.wantsFrames)
        assertEquals(1, game.progress)
        assertNull(game.snapshot())
    }

    @Test
    public fun winningOffersTheNextLevelAndUnlocksIt() {
        val game = finished(1, won = true)
        assertEquals(StringKey.NEXT_LEVEL, game.overActionKey)
        assertEquals(2, game.progress)
        game.reset(9, 1)
        assertEquals(2, game.level)
        assertFalse(game.isChoosing)
        assertEquals(0, game.score)
        assertIs<GameStatus.Running>(game.status)
    }

    @Test
    public fun losingReplaysTheSameLevel() {
        val game = finished(29, won = false)
        assertNull(game.overActionKey)
        game.reset(9, 1)
        assertEquals(29, game.level)
        assertFalse(game.isChoosing)
        assertEquals(MixUpLevels.all[28].moves, game.movesLeft)
    }

    @Test
    public fun winningTheLastLevelAndRestartingMidLevelGoBackToTheChoice() {
        val won = finished(30, won = true)
        assertNull(won.overActionKey)
        assertEquals(31, won.progress)
        won.reset(9, 1)
        assertTrue(won.isChoosing)

        val playing = level(3)
        playing.reset(9, 1)
        assertTrue(playing.isChoosing)
    }

    @Test
    public fun aRefusedSwapCostsNothing() {
        val game = level(1)
        val board = game.testBoard
        val a = (0 until MixUpBoard.CELLS - 1).first { it % MixUpBoard.COLS < MixUpBoard.COLS - 1 && !board.isLegalSwap(it, it + 1) }
        val before = board.shape.copyOf()
        game.trySwap(a, a + 1)
        MixUpBot.settle(game)
        assertEquals(MixUpLevels.all[0].moves, game.movesLeft)
        assertTrue(before.contentEquals(board.shape))
        assertFalse(game.canResume, "nothing played yet")
    }

    @Test
    public fun inputWaitsForTheBoardToSettle() {
        val game = level(1)
        move(game)
        val moves = game.movesLeft
        assertTrue(game.wantsFrames)
        // A whole drag during the animation is ignored.
        game.onPointer(0, PointerPhase.DOWN, 30f, 125f)
        game.onPointer(0, PointerPhase.MOVE, 80f, 125f)
        game.onPointer(0, PointerPhase.UP, 80f, 125f)
        assertEquals(moves, game.movesLeft)
        MixUpBot.settle(game)
        assertEquals(moves, game.movesLeft)
    }

    @Test
    public fun draggingAShapeSwapsIt() {
        val game = level(1)
        val move = MixUpBot.bestMove(game)
        val a = move / 2
        val down = move % 2 == 1
        val x = 30f + a % MixUpBoard.COLS * 50f
        val y = 125f + a / MixUpBoard.COLS * 50f
        game.onPointer(0, PointerPhase.DOWN, x, y)
        game.onPointer(0, PointerPhase.MOVE, x + if (down) 0f else 30f, y + if (down) 30f else 0f)
        assertEquals(MixUpLevels.all[0].moves - 1, game.movesLeft)
    }

    @Test
    public fun savingDuringACascadeGivesTheSettledBoard() {
        for (seed in 1L..40L) {
            val game = level(11, seed)
            move(game)
            val during = assertNotNull(game.snapshot())
            game.update(50)
            assertEquals(during, game.snapshot(), "seed $seed: saving changed with the animation")
            MixUpBot.settle(game)
            assertEquals(during, game.snapshot(), "seed $seed: the save differs from the board played out")

            val restored = MixUpGame()
            assertTrue(restored.restore(during))
            assertTrue(restored.canResume)
            assertEquals(during, restored.snapshot())
        }
    }

    @Test
    public fun brokenSavesAreRefused() {
        val game = level(17)
        move(game)
        val data = assertNotNull(game.snapshot())
        assertFalse(MixUpGame().restore(data.replace("level=17", "level=31")))
        assertFalse(MixUpGame().restore(data.replace("moves=24", "moves=99")))
        assertFalse(MixUpGame().restore("level=1"))
        assertFalse(MixUpGame().restore(""))
    }

    @Test
    public fun theBoardNeverDropsPrimitivesEvenFullOfIceAndBonuses() {
        val game = level(24)
        for (i in 0 until MixUpBoard.CELLS) {
            game.testBoard.ice[i] = 2
            game.testBoard.special[i] = MixUpBoard.ROW_CLEARER
        }
        val scene = Scene()
        game.render(scene)
        assertEquals(0, scene.dropped)
        assertTrue(scene.count < 900, "the session adds its own primitives: ${scene.count}")
    }
}
