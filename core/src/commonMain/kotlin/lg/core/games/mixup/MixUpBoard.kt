package lg.core.games.mixup

import lg.core.util.Rng

/**
 * The rules of the match-three board, without any notion of time or drawing: [COLS] x [ROWS] cells, each with a shape
 * ([shape]), possibly a bonus ([special]) and layers of ice ([ice]) that belong to the cell and stay when the shape
 * moves. A cascade step is [computeClear] (or [computeBombSwap]) then [applyClear] then [fall]; between the two first
 * calls [marked] tells which cells are about to disappear. Everything is preallocated.
 */
internal class MixUpBoard {
    val shape = IntArray(CELLS)
    val special = IntArray(CELLS)
    val ice = IntArray(CELLS)

    /** Cells about to be cleared by the current step. */
    val marked = BooleanArray(CELLS)

    /** How many rows each cell's shape has just fallen (for the animation). */
    val fallRows = IntArray(CELLS)

    /** Bonus to create at a cell when the current step is applied, or [NONE]. */
    private val spawn = IntArray(CELLS)
    private val queue = IntArray(CELLS)
    private var queued = 0

    /** Shapes cleared per kind by the last [applyClear]. */
    val cleared = IntArray(SHAPES)

    /** Number of colors in play (shapes `0 until colors`). */
    var colors: Int = SHAPES

    val iceLeft: Int
        get() {
            var n = 0
            for (i in 0 until CELLS) n += ice[i]
            return n
        }

    fun copyFrom(other: MixUpBoard) {
        other.shape.copyInto(shape)
        other.special.copyInto(special)
        other.ice.copyInto(ice)
        other.marked.copyInto(marked)
        other.spawn.copyInto(spawn)
        other.fallRows.copyInto(fallRows)
        colors = other.colors
    }

    /** Fills the board with random shapes: nothing already aligned, and at least one move possible. Ice is kept. */
    fun generate(rng: Rng) {
        do {
            for (i in 0 until CELLS) {
                special[i] = NONE
                var s: Int
                do {
                    s = rng.nextInt(colors)
                    shape[i] = s
                } while (completesRun(i))
            }
        } while (!hasMove())
        marked.fill(false)
        spawn.fill(NONE)
        fallRows.fill(0)
    }

    /** During [generate]: whether cell [i] closes a run of three with the two cells left of it or above it. */
    private fun completesRun(i: Int): Boolean {
        val x = i % COLS
        val y = i / COLS
        val s = shape[i]
        if (x >= 2 && shape[i - 1] == s && shape[i - 2] == s) return true
        return y >= 2 && shape[i - COLS] == s && shape[i - 2 * COLS] == s
    }

    fun swap(a: Int, b: Int) {
        val s = shape[a]
        shape[a] = shape[b]
        shape[b] = s
        val p = special[a]
        special[a] = special[b]
        special[b] = p
    }

    /** Whether swapping the neighbours [a] and [b] does something: a bomb moves, or a line of three appears. */
    fun isLegalSwap(a: Int, b: Int): Boolean {
        if (!adjacent(a, b)) return false
        if (special[a] == BOMB || special[b] == BOMB) return true
        if (shape[a] == shape[b]) return false
        swap(a, b)
        val result = inRun(a) || inRun(b)
        swap(a, b)
        return result
    }

    fun hasMove(): Boolean {
        for (i in 0 until CELLS) {
            if (i % COLS < COLS - 1 && isLegalSwap(i, i + 1)) return true
            if (i / COLS < ROWS - 1 && isLegalSwap(i, i + COLS)) return true
        }
        return false
    }

    private fun inRun(i: Int): Boolean {
        val s = shape[i]
        if (s !in 0 until SHAPES) return false
        val x = i % COLS
        val y = i / COLS
        var n = 1
        var k = x - 1
        while (k >= 0 && shape[y * COLS + k] == s) { n++; k-- }
        k = x + 1
        while (k < COLS && shape[y * COLS + k] == s) { n++; k++ }
        if (n >= 3) return true
        n = 1
        k = y - 1
        while (k >= 0 && shape[k * COLS + x] == s) { n++; k-- }
        k = y + 1
        while (k < ROWS && shape[k * COLS + x] == s) { n++; k++ }
        return n >= 3
    }

    /**
     * Marks every line of three or more, plans the bonuses of the longer ones and sets off the bonuses caught in them.
     * [preferA] and [preferB] are the cells just swapped (or -1): a new bonus appears there if they are in its line.
     * @return true if anything is marked.
     */
    fun computeClear(preferA: Int = -1, preferB: Int = -1): Boolean {
        marked.fill(false)
        spawn.fill(NONE)
        queued = 0
        for (y in 0 until ROWS) scanLine(y * COLS, 1, COLS, ROW_CLEARER, preferA, preferB)
        for (x in 0 until COLS) scanLine(x, COLS, ROWS, COLUMN_CLEARER, preferA, preferB)
        return setOff()
    }

    private fun scanLine(start: Int, step: Int, length: Int, lineBonus: Int, preferA: Int, preferB: Int) {
        var from = 0
        while (from < length) {
            val s = shape[start + from * step]
            var to = from + 1
            while (to < length && shape[start + to * step] == s) to++
            val n = to - from
            if (n >= 3 && s in 0 until SHAPES) {
                var at = start + (from + 1) * step
                for (k in from until to) {
                    val i = start + k * step
                    mark(i)
                    if (i == preferA || i == preferB) at = i
                }
                if (n >= 5) spawn[at] = BOMB else if (n == 4 && spawn[at] != BOMB) spawn[at] = lineBonus
            }
            from = to
        }
    }

    /**
     * The step of a swap involving a bomb, made after [swap]: the bomb takes every shape like the one it was swapped
     * with; two bombs take the whole board.
     */
    fun computeBombSwap(a: Int, b: Int) {
        marked.fill(false)
        spawn.fill(NONE)
        queued = 0
        val bomb = if (special[a] == BOMB) a else b
        val other = if (bomb == a) b else a
        if (special[other] == BOMB) {
            for (i in 0 until CELLS) mark(i)
        } else {
            val target = shape[other]
            // This bomb is spent on the chosen shape: it must not also pick one of its own.
            marked[bomb] = true
            for (i in 0 until CELLS) if (shape[i] == target) mark(i)
        }
        setOff()
    }

    private fun mark(i: Int) {
        if (marked[i]) return
        marked[i] = true
        if (special[i] != NONE) queue[queued++] = i
    }

    /** Lets every marked bonus act, once, including those it reaches in turn. */
    private fun setOff(): Boolean {
        var head = 0
        while (head < queued) {
            val i = queue[head++]
            when (special[i]) {
                ROW_CLEARER -> {
                    val first = i / COLS * COLS
                    for (x in 0 until COLS) mark(first + x)
                }
                COLUMN_CLEARER -> {
                    for (y in 0 until ROWS) mark(y * COLS + i % COLS)
                }
                BOMB -> {
                    val target = commonestShape()
                    if (target >= 0) for (k in 0 until CELLS) if (shape[k] == target) mark(k)
                }
            }
        }
        for (i in 0 until CELLS) if (marked[i]) return true
        return false
    }

    /** The shape with most cells not yet marked, or -1. */
    private fun commonestShape(): Int {
        var best = -1
        var bestCount = 0
        for (s in 0 until colors) {
            var n = 0
            for (i in 0 until CELLS) if (!marked[i] && shape[i] == s) n++
            if (n > bestCount) {
                bestCount = n
                best = s
            }
        }
        return best
    }

    /**
     * Removes the marked cells (one layer of ice each), creates the planned bonuses and counts what went into
     * [cleared]. @return the number of cells cleared.
     */
    fun applyClear(): Int {
        cleared.fill(0)
        var n = 0
        for (i in 0 until CELLS) {
            if (!marked[i]) continue
            n++
            if (shape[i] in 0 until SHAPES) cleared[shape[i]]++
            if (ice[i] > 0) ice[i]--
            when (spawn[i]) {
                NONE -> {
                    shape[i] = EMPTY
                    special[i] = NONE
                }
                BOMB -> {
                    shape[i] = BOMB_SHAPE
                    special[i] = BOMB
                }
                else -> special[i] = spawn[i]
            }
            marked[i] = false
            spawn[i] = NONE
        }
        return n
    }

    /** Lets the shapes fall into the holes and draws new ones from above. @return the longest fall, in rows. */
    fun fall(rng: Rng): Int {
        var longest = 0
        fallRows.fill(0)
        for (x in 0 until COLS) {
            var to = ROWS - 1
            for (y in ROWS - 1 downTo 0) {
                val from = y * COLS + x
                if (shape[from] == EMPTY) continue
                val dest = to * COLS + x
                if (dest != from) {
                    shape[dest] = shape[from]
                    special[dest] = special[from]
                    shape[from] = EMPTY
                    special[from] = NONE
                }
                fallRows[dest] = to - y
                if (to - y > longest) longest = to - y
                to--
            }
            val holes = to + 1
            for (y in 0 until holes) {
                val i = y * COLS + x
                shape[i] = rng.nextInt(colors)
                special[i] = NONE
                fallRows[i] = holes
                if (holes > longest) longest = holes
            }
        }
        return longest
    }

    /** Mixes the shapes (the ice stays) until nothing is aligned and a move exists; a new board as a last resort. */
    fun shuffle(rng: Rng) {
        repeat(SHUFFLE_TRIES) {
            for (i in CELLS - 1 downTo 1) swap(i, rng.nextInt(i + 1))
            var aligned = false
            for (i in 0 until CELLS) if (inRun(i)) { aligned = true; break }
            if (!aligned && hasMove()) return
        }
        generate(rng)
    }

    companion object {
        const val COLS = 7
        const val ROWS = 9
        const val CELLS = COLS * ROWS
        const val SHAPES = 6

        const val EMPTY = -1
        /** [shape] of a bomb: it has no color and never lines up. */
        const val BOMB_SHAPE = SHAPES

        const val NONE = 0
        const val ROW_CLEARER = 1
        const val COLUMN_CLEARER = 2
        const val BOMB = 3

        private const val SHUFFLE_TRIES = 60

        fun adjacent(a: Int, b: Int): Boolean {
            if (a !in 0 until CELLS || b !in 0 until CELLS) return false
            val dx = a % COLS - b % COLS
            val dy = a / COLS - b / COLS
            return dx * dx + dy * dy == 1
        }
    }
}
