package lg.core.games.pairs

import lg.core.GameStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class PairsRulesTest {
    // Deterministic layout: card i and card i+8 share pair id i/... simpler: pairs are
    // (0,1) (2,3) (4,5) (6,7) (8,9) (10,11) (12,13) (14,15) by index, pair id = idx/2.
    private val knownLayout = IntArray(PairsGame.CARD_COUNT) { it / 2 }

    private fun newGame(seed: Long = 1L, players: Int = 1): PairsGame {
        val game = PairsGame()
        game.resize(400f, 432f)
        game.reset(seed, players)
        game.debugSetPairIds(knownLayout)
        return game
    }

    @Test
    public fun matchingPairStaysFaceUpAndScoresAPoint(): Unit {
        val game = newGame()
        game.debugTap(0)
        game.debugTap(1)
        assertEquals(PairsGame.STATE_MATCHED, game.debugCardState(0))
        assertEquals(PairsGame.STATE_MATCHED, game.debugCardState(1))
        assertEquals(1, game.debugPairsFound(1))
        assertEquals(1, game.debugMovesCount())
        assertTrue(!game.debugWaitingMismatch())
    }

    @Test
    public fun mismatchFlipsBackAfterTheDelay(): Unit {
        val game = newGame()
        game.debugTap(0)
        game.debugTap(2) // pair ids 0 vs 1: mismatch
        assertEquals(PairsGame.STATE_FACE_UP, game.debugCardState(0))
        assertEquals(PairsGame.STATE_FACE_UP, game.debugCardState(2))
        assertTrue(game.debugWaitingMismatch())

        game.update(699)
        assertTrue(game.debugWaitingMismatch())
        game.update(2)
        assertTrue(!game.debugWaitingMismatch())
        assertEquals(PairsGame.STATE_HIDDEN, game.debugCardState(0))
        assertEquals(PairsGame.STATE_HIDDEN, game.debugCardState(2))
    }

    @Test
    public fun tapDuringMismatchWaitResolvesItImmediately(): Unit {
        val game = newGame()
        game.debugTap(0)
        game.debugTap(2)
        assertTrue(game.debugWaitingMismatch())
        game.debugTap(4) // any tap resolves the wait instead of flipping a new card
        assertTrue(!game.debugWaitingMismatch())
        assertEquals(PairsGame.STATE_HIDDEN, game.debugCardState(0))
        assertEquals(PairsGame.STATE_HIDDEN, game.debugCardState(2))
        assertEquals(PairsGame.STATE_HIDDEN, game.debugCardState(4))
    }

    @Test
    public fun twoPlayerMatchKeepsTheTurnAndMismatchPassesIt(): Unit {
        val game = newGame(players = 2)
        assertEquals(1, game.debugCurrentPlayer())

        game.debugTap(0)
        game.debugTap(1) // match: turn stays with player 1
        assertEquals(1, game.debugCurrentPlayer())
        assertEquals(1, game.debugPairsFound(1))

        game.debugTap(2)
        game.debugTap(4) // mismatch
        game.update(1000)
        assertEquals(2, game.debugCurrentPlayer())
    }

    @Test
    public fun completingAllPairsEndsTheGame(): Unit {
        val game = newGame()
        var idx = 0
        while (idx < PairsGame.CARD_COUNT) {
            game.debugTap(idx)
            game.debugTap(idx + 1)
            idx += 2
        }
        assertTrue(game.status is GameStatus.Over)
        assertEquals(0, (game.status as GameStatus.Over).winner)
        assertEquals(PairsGame.PAIR_COUNT, game.debugPairsFound(1))
    }

    @Test
    public fun twoPlayerGameEndsWithTheHigherScoringPlayerAsWinner(): Unit {
        val game = newGame(players = 2)
        // Player 1 matches pair 0, then mismatches to pass the turn to player 2,
        // who then matches every remaining pair.
        game.debugTap(0)
        game.debugTap(1)
        game.debugTap(2)
        game.debugTap(4) // mismatch, passes turn
        game.update(1000)
        assertEquals(2, game.debugCurrentPlayer())

        var idx = 2
        while (idx < PairsGame.CARD_COUNT) {
            if (game.debugCardState(idx) == PairsGame.STATE_MATCHED) {
                idx += 2
                continue
            }
            game.debugTap(idx)
            game.debugTap(idx + 1)
            idx += 2
        }
        assertTrue(game.status is GameStatus.Over)
        assertEquals(2, (game.status as GameStatus.Over).winner)
    }
}
