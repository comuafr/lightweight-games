package lg.core.games.nonogram

import lg.core.util.Rng

/**
 * Generates a random nonogram picture (density ~55%), computes its row/column
 * clues, and verifies with [LineSolver]-based propagation that the puzzle is
 * solvable by pure line logic alone (no guessing). If not, it regenerates a
 * new random picture, up to [MAX_ATTEMPTS] times; if none verify, the last
 * generated picture is accepted anyway (`Puzzle.solvableByLogic` reports
 * which happened, and `NonogramGame` never relies on it for correctness —
 * only [LineSolver]-derived hints would, and this game doesn't offer hints).
 * This mirrors the same "bounded budget, documented fallback" approach as
 * `SudokuGenerator`.
 */
public object NonogramGenerator {
    public const val MAX_SIZE: Int = 15

    /** A run of length up to [MAX_SIZE] needs at most `ceil(MAX_SIZE / 2)` blocks (alternating filled/empty). */
    public const val MAX_CLUES: Int = 8

    public enum class Difficulty(public val size: Int) {
        EASY(5),
        MEDIUM(10),
        HARD(15),
    }

    private const val DENSITY: Float = 0.55f
    private const val MAX_ATTEMPTS: Int = 20
    private const val BUDGET_PER_ATTEMPT: Int = 200_000

    /**
     * [picture] is `size * size`, row-major. [rowClueCount]/[colClueCount]
     * are `size` long; [rowClues]/[colClues] are `size * MAX_CLUES`
     * flattened (`line * MAX_CLUES + k`), only the first `count` entries of
     * each line are meaningful.
     */
    public class Puzzle(
        public val size: Int,
        public val picture: BooleanArray,
        public val rowClueCount: IntArray,
        public val rowClues: IntArray,
        public val colClueCount: IntArray,
        public val colClues: IntArray,
        public val solvableByLogic: Boolean,
    )

    public fun generate(rng: Rng, difficulty: Difficulty): Puzzle {
        val size = difficulty.size
        val picture = BooleanArray(size * size)
        var solvable = false
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            for (i in picture.indices) picture[i] = rng.nextFloat() < DENSITY
            if (isSolvableByLogic(size, picture)) {
                solvable = true
                break
            }
        }

        val rowClueCount = IntArray(size)
        val rowClues = IntArray(size * MAX_CLUES)
        val colClueCount = IntArray(size)
        val colClues = IntArray(size * MAX_CLUES)
        computeClues(size, picture, rowClueCount, rowClues, colClueCount, colClues)
        return Puzzle(size, picture, rowClueCount, rowClues, colClueCount, colClues, solvable)
    }

    /** Fills [rowClueCount]/[rowClues]/[colClueCount]/[colClues] from [picture]; also used to check line clues directly. */
    public fun computeClues(
        size: Int,
        picture: BooleanArray,
        rowClueCount: IntArray,
        rowClues: IntArray,
        colClueCount: IntArray,
        colClues: IntArray,
    ) {
        for (r in 0 until size) {
            var count = 0
            var run = 0
            for (c in 0 until size) {
                if (picture[r * size + c]) {
                    run++
                } else if (run > 0) {
                    rowClues[r * MAX_CLUES + count] = run
                    count++
                    run = 0
                }
            }
            if (run > 0) {
                rowClues[r * MAX_CLUES + count] = run
                count++
            }
            rowClueCount[r] = count
        }
        for (c in 0 until size) {
            var count = 0
            var run = 0
            for (r in 0 until size) {
                if (picture[r * size + c]) {
                    run++
                } else if (run > 0) {
                    colClues[c * MAX_CLUES + count] = run
                    count++
                    run = 0
                }
            }
            if (run > 0) {
                colClues[c * MAX_CLUES + count] = run
                count++
            }
            colClueCount[c] = count
        }
    }

    private fun isSolvableByLogic(size: Int, picture: BooleanArray): Boolean {
        val rowClueCount = IntArray(size)
        val rowClues = IntArray(size * MAX_CLUES)
        val colClueCount = IntArray(size)
        val colClues = IntArray(size * MAX_CLUES)
        computeClues(size, picture, rowClueCount, rowClues, colClueCount, colClues)

        val state = IntArray(size * size) { LineSolver.UNKNOWN }
        val lineBuf = IntArray(size)
        val clueBuf = IntArray(MAX_CLUES)
        val budget = LineSolver.Budget(BUDGET_PER_ATTEMPT)
        val scratch = LineSolver.Scratch(size)

        var progress = true
        while (progress && budget.remaining > 0) {
            progress = false
            for (r in 0 until size) {
                for (c in 0 until size) lineBuf[c] = state[r * size + c]
                val cnt = rowClueCount[r]
                for (k in 0 until cnt) clueBuf[k] = rowClues[r * MAX_CLUES + k]
                if (LineSolver.deduceLine(size, clueBuf, cnt, lineBuf, budget, scratch)) {
                    for (c in 0 until size) state[r * size + c] = lineBuf[c]
                    progress = true
                }
                if (budget.remaining <= 0) break
            }
            if (budget.remaining <= 0) break
            for (c in 0 until size) {
                for (r in 0 until size) lineBuf[r] = state[r * size + c]
                val cnt = colClueCount[c]
                for (k in 0 until cnt) clueBuf[k] = colClues[c * MAX_CLUES + k]
                if (LineSolver.deduceLine(size, clueBuf, cnt, lineBuf, budget, scratch)) {
                    for (r in 0 until size) state[r * size + c] = lineBuf[r]
                    progress = true
                }
                if (budget.remaining <= 0) break
            }
        }

        for (v in state) if (v == LineSolver.UNKNOWN) return false
        return true
    }
}
