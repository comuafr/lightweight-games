package lg.core.games.mixup

import lg.core.GameStatus

/**
 * A modest player: among the legal swaps it takes the one whose first clear does most for the goal of the level,
 * without looking at what will fall in. It plays worse than a person who plans, so a level it can win is winnable.
 */
internal object MixUpBot {
    private val scratch = MixUpBoard()

    fun settle(game: MixUpGame) {
        var guard = 0
        while (game.wantsFrames) {
            game.update(50)
            check(guard++ < 10_000) { "the board never settles" }
        }
    }

    /** Plays [level] from [seed] to its end. @return true if won. */
    fun play(level: Int, seed: Long): Boolean {
        val game = MixUpGame()
        game.reset(seed, 1)
        game.startLevel(level)
        while (game.status == GameStatus.Running) {
            val move = bestMove(game)
            check(move >= 0) { "level $level: no move offered on a settled board" }
            val a = move / 2
            game.trySwap(a, if (move % 2 == 0) a + 1 else a + MixUpBoard.COLS)
            settle(game)
        }
        return (game.status as GameStatus.Over).winner == GameStatus.SOLO_WIN
    }

    /** `2 * cell` for a swap with the right neighbour, `2 * cell + 1` with the one below; -1 if none. */
    fun bestMove(game: MixUpGame): Int {
        val board = game.testBoard
        val rules = MixUpLevels.all[game.level - 1]
        var best = -1
        var bestValue = -1
        for (a in 0 until MixUpBoard.CELLS) {
            for (down in 0..1) {
                if (down == 0 && a % MixUpBoard.COLS == MixUpBoard.COLS - 1) continue
                if (down == 1 && a / MixUpBoard.COLS == MixUpBoard.ROWS - 1) continue
                val b = if (down == 0) a + 1 else a + MixUpBoard.COLS
                if (!board.isLegalSwap(a, b)) continue
                val value = value(board, rules, a, b)
                if (value > bestValue) {
                    bestValue = value
                    best = 2 * a + down
                }
            }
        }
        return best
    }

    private fun value(board: MixUpBoard, rules: Level, a: Int, b: Int): Int {
        scratch.copyFrom(board)
        val bomb = scratch.special[a] == MixUpBoard.BOMB || scratch.special[b] == MixUpBoard.BOMB
        scratch.swap(a, b)
        if (bomb) scratch.computeBombSwap(a, b) else scratch.computeClear(a, b)
        var value = 0
        for (i in 0 until MixUpBoard.CELLS) {
            if (!scratch.marked[i]) continue
            // Lower rows first on a tie: they set off more cascades.
            value += 100 + i / MixUpBoard.COLS
            when (rules.goal) {
                Goal.ICE -> if (scratch.ice[i] > 0) value += 1000
                Goal.COLLECT -> for (k in rules.collect.indices step 2) {
                    if (scratch.shape[i] == rules.collect[k]) value += 1000
                }
                Goal.SCORE -> Unit
            }
        }
        return value
    }
}
