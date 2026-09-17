package lg.core

import lg.core.games.bird.BirdGame
import lg.core.games.breakout.BreakoutGame
import lg.core.games.fourinrow.FourInRowGame
import lg.core.games.g2048.Game2048
import lg.core.games.hangman.HangmanGame
import lg.core.games.mines.MinesGame
import lg.core.games.nonogram.NonogramGame
import lg.core.games.paddles.PaddlesGame
import lg.core.games.pairs.PairsGame
import lg.core.games.reversi.ReversiGame
import lg.core.games.sequence.SequenceGame
import lg.core.games.sliding.SlidingGame
import lg.core.games.snake.SnakeGame
import lg.core.games.sudoku.SudokuGame
import lg.core.games.tictactoe.TicTacToeGame

/**
 * Central catalogue of every game known to the app. Free games come first;
 * within a tier, games are listed in the order they should appear in the
 * catalogue UI. All 3 FREE games (snake, 2048, breakout) shipped in M1 part
 * (a); M3 added the 6 complexity-S/M UNLOCKED games (sequence, pairs,
 * sliding, tictactoe, paddles, fourinrow); M4 adds the remaining 6
 * complexity-M/C UNLOCKED games (bird, mines, hangman, reversi, sudoku,
 * nonogram), for 15 games total (PLAN.md §9).
 */
public object GameRegistry {
    public val all: List<GameMeta> = listOf(
        SnakeGame.META,
        Game2048.META,
        BreakoutGame.META,
        SequenceGame.META,
        PairsGame.META,
        SlidingGame.META,
        TicTacToeGame.META,
        PaddlesGame.META,
        FourInRowGame.META,
        BirdGame.META,
        MinesGame.META,
        HangmanGame.META,
        ReversiGame.META,
        SudokuGame.META,
        NonogramGame.META,
    )

    public val byId: Map<String, GameMeta> = all.associateBy { it.id }

    /** Games playable right now: always FREE ones, plus everything if [unlocked]. */
    public fun playable(unlocked: Boolean): List<GameMeta> =
        all.filter { it.tier == Tier.FREE || unlocked }
}
