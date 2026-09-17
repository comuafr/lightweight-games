package lg.core.games.nonogram

/**
 * Single-line nonogram constraint propagation: given run-length clues for
 * one row or column and the currently known cell states, deduces which
 * cells are certainly filled or certainly empty across *every* placement of
 * the clue blocks consistent with what's already known.
 *
 * ### Algorithm
 *
 * [deduceLine] enumerates every way to place the blocks left-to-right (each
 * block occupies a contiguous run, separated by at least one empty cell)
 * that is consistent with [state], via [place] (recursive, but bounded: a
 * line has at most [lg.core.games.nonogram.NonogramGenerator.MAX_SIZE] cells
 * and [lg.core.games.nonogram.NonogramGenerator.MAX_CLUES] blocks, so the
 * placement count stays small). A cell that is filled in every explored
 * placement is forced filled; one that is empty in every explored placement
 * is forced empty; a cell that is *both* in different placements stays
 * unknown. [budget] bounds the total number of placements explored across a
 * whole solving pass (shared across every line, not reset per call) so a
 * pathological line can never make generation hang; running out of budget
 * simply means "no progress this line, try again next round" — always a
 * safe (if conservative) outcome, never an incorrect deduction.
 */
public object LineSolver {
    public const val UNKNOWN: Int = -1
    public const val EMPTY: Int = 0
    public const val FILLED: Int = 1

    /** Shared placement budget across an entire solving pass; see the class KDoc. */
    public class Budget(public var remaining: Int)

    /** Reused scratch buffers for one [deduceLine] call, sized for the largest supported line. */
    public class Scratch(maxLen: Int) {
        internal val working = IntArray(maxLen)
        internal val possibleFilled = BooleanArray(maxLen)
        internal val possibleEmpty = BooleanArray(maxLen)
        internal var foundCount = 0
    }

    /**
     * Deduces forced values for a line of length [len] with clues
     * `clues[0 until clueCount]`, reading and writing [state] in place
     * (`UNKNOWN`/`EMPTY`/`FILLED` per cell, length [len]).
     * @return true if [state] changed.
     */
    public fun deduceLine(len: Int, clues: IntArray, clueCount: Int, state: IntArray, budget: Budget, scratch: Scratch): Boolean {
        if (clueCount == 0) {
            var changed = false
            for (i in 0 until len) {
                if (state[i] == UNKNOWN) {
                    state[i] = EMPTY
                    changed = true
                }
            }
            return changed
        }
        for (i in 0 until len) {
            scratch.possibleFilled[i] = false
            scratch.possibleEmpty[i] = false
        }
        scratch.foundCount = 0
        place(0, 0, len, clues, clueCount, state, budget, scratch)
        if (scratch.foundCount == 0) return false

        var changed = false
        for (i in 0 until len) {
            if (state[i] != UNKNOWN) continue
            val canFill = scratch.possibleFilled[i]
            val canEmpty = scratch.possibleEmpty[i]
            if (canFill && !canEmpty) {
                state[i] = FILLED
                changed = true
            } else if (canEmpty && !canFill) {
                state[i] = EMPTY
                changed = true
            }
        }
        return changed
    }

    private fun place(blockIdx: Int, minStart: Int, len: Int, clues: IntArray, clueCount: Int, state: IntArray, budget: Budget, scratch: Scratch) {
        if (budget.remaining <= 0) return
        if (blockIdx == clueCount) {
            for (i in minStart until len) scratch.working[i] = EMPTY
            budget.remaining--
            if (matches(scratch.working, state, len)) {
                scratch.foundCount++
                for (i in 0 until len) {
                    if (scratch.working[i] == FILLED) scratch.possibleFilled[i] = true else scratch.possibleEmpty[i] = true
                }
            }
            return
        }
        val blockLen = clues[blockIdx]
        var minSpaceAfter = 0
        for (b in blockIdx + 1 until clueCount) minSpaceAfter += clues[b] + 1
        val maxStart = len - blockLen - minSpaceAfter
        var s = minStart
        while (s <= maxStart) {
            for (i in minStart until s) scratch.working[i] = EMPTY
            for (i in s until s + blockLen) scratch.working[i] = FILLED
            place(blockIdx + 1, (s + blockLen + 1).coerceAtMost(len), len, clues, clueCount, state, budget, scratch)
            if (budget.remaining <= 0) return
            s++
        }
    }

    private fun matches(working: IntArray, state: IntArray, len: Int): Boolean {
        for (i in 0 until len) {
            val want = state[i]
            if (want != UNKNOWN && want != working[i]) return false
        }
        return true
    }
}
