package lg.core.games.sudoku

import lg.core.util.Rng

/**
 * Generates a full solution grid via randomized backtracking, then removes
 * cells (down to a difficulty-dependent target) while keeping the puzzle's
 * solution unique, via a bounded backtracking solver used only to count
 * solutions up to 2.
 *
 * ### Time budget
 *
 * Uniqueness checking is the expensive part; [removeCells] tracks a total
 * step budget ([TOTAL_STEP_BUDGET]) shared across every uniqueness check it
 * performs during one generation call. Once exhausted, removal simply stops
 * early: the resulting puzzle keeps whatever cells were already safely
 * removed (possibly more givens than the difficulty's nominal target), which
 * is still a perfectly valid, uniquely-solvable puzzle — just an easier one
 * than requested. This trades a small, documented quality regression for a
 * hard ceiling on generation time (PLAN.md §10: target ~200ms on a JVM; see
 * `SudokuRulesTest` for a generous 1.5s test bound to avoid flakiness).
 */
public object SudokuGenerator {
    public const val SIZE: Int = 9
    public const val CELL_COUNT: Int = SIZE * SIZE

    public enum class Difficulty(public val targetGivens: Int) {
        EASY(36),
        MEDIUM(30),
        HARD(26),
    }

    /** [given]: 0 = blank, 1-9 = a fixed starting digit. [solution]: the full, unique solved grid. */
    public class Puzzle(public val given: IntArray, public val solution: IntArray)

    private const val TOTAL_STEP_BUDGET: Int = 150_000
    private const val PER_CELL_STEP_CAP: Int = 12_000

    public fun generate(rng: Rng, difficulty: Difficulty): Puzzle {
        val solution = IntArray(CELL_COUNT)
        fillGrid(solution, rng)
        val given = solution.copyOf()
        removeCells(given, difficulty.targetGivens, rng)
        return Puzzle(given, solution)
    }

    // --- Full-grid generation ---------------------------------------------

    private fun fillGrid(grid: IntArray, rng: Rng): Boolean = fillCell(grid, 0, rng)

    private fun fillCell(grid: IntArray, pos: Int, rng: Rng): Boolean {
        if (pos == CELL_COUNT) return true
        if (grid[pos] != 0) return fillCell(grid, pos + 1, rng)
        val candidates = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        rng.shuffle(candidates)
        for (v in candidates) {
            if (isValid(grid, pos, v)) {
                grid[pos] = v
                if (fillCell(grid, pos + 1, rng)) return true
                grid[pos] = 0
            }
        }
        return false
    }

    private fun isValid(grid: IntArray, pos: Int, v: Int): Boolean {
        val row = pos / SIZE
        val col = pos % SIZE
        for (c in 0 until SIZE) if (grid[row * SIZE + c] == v) return false
        for (r in 0 until SIZE) if (grid[r * SIZE + col] == v) return false
        val boxRow = (row / 3) * 3
        val boxCol = (col / 3) * 3
        for (r in boxRow until boxRow + 3) {
            for (c in boxCol until boxCol + 3) {
                if (grid[r * SIZE + c] == v) return false
            }
        }
        return true
    }

    /** Test-only: counts solutions of [given] up to [limit] (exercised by `SudokuRulesTest`). */
    internal fun debugCountSolutions(given: IntArray, limit: Int = 2, maxSteps: Int = 2_000_000): Int =
        SolutionCounter(given, limit, maxSteps).solve()

    // --- Cell removal with a uniqueness check ------------------------------

    private fun removeCells(given: IntArray, targetGivens: Int, rng: Rng) {
        val order = IntArray(CELL_COUNT) { it }
        rng.shuffle(order)
        var givensLeft = CELL_COUNT
        var stepBudgetLeft = TOTAL_STEP_BUDGET
        for (idx in order) {
            if (givensLeft <= targetGivens) break
            if (stepBudgetLeft <= 0) break
            val backup = given[idx]
            given[idx] = 0
            val perCellCap = PER_CELL_STEP_CAP.coerceAtMost(stepBudgetLeft)
            val solver = SolutionCounter(given, limit = 2, maxSteps = perCellCap)
            val count = solver.solve()
            stepBudgetLeft -= solver.stepsTaken
            if (count == 1) {
                givensLeft--
            } else {
                given[idx] = backup
            }
        }
    }

    /**
     * Backtracking solver that stops as soon as it has found [limit]
     * solutions (we only ever care whether a puzzle has exactly one), or once
     * [maxSteps] recursive steps have been spent (`solve()` then returns `-1`,
     * meaning "unknown, budget exceeded" — treated as "not proven unique" by
     * the caller, so the cell removal is reverted).
     */
    private class SolutionCounter(source: IntArray, private val limit: Int, private val maxSteps: Int) {
        private val work = source.copyOf()
        private var count = 0
        var stepsTaken: Int = 0
            private set
        private var exceeded = false

        fun solve(): Int {
            search(0)
            return if (exceeded) -1 else count
        }

        private fun search(fromPos: Int) {
            if (exceeded || count >= limit) return
            stepsTaken++
            if (stepsTaken > maxSteps) {
                exceeded = true
                return
            }
            var p = fromPos
            while (p < CELL_COUNT && work[p] != 0) p++
            if (p == CELL_COUNT) {
                count++
                return
            }
            for (v in 1..9) {
                if (exceeded || count >= limit) return
                if (isValid(work, p, v)) {
                    work[p] = v
                    search(p + 1)
                    work[p] = 0
                }
            }
        }
    }
}
