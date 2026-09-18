package com.lightweight.snake

/**
 * Pure Snake game logic. NO Android imports — runs and is unit-tested on the JVM.
 *
 * The board is [cols] x [rows]. The snake body is held in a ring buffer of packed
 * cell indices (y * cols + x), ordered tail..head, plus an [occupied] bitmap for
 * O(1) collision and food placement. tick()/turn() never allocate.
 *
 * Determinism: all randomness comes from [rnd] (a () -> Double in [0,1)), so tests
 * can seed it.
 */
class Game(
    val cols: Int = 17,
    val rows: Int = 24,
    private val rnd: () -> Double = Math::random,
) {
    enum class Dir(val dx: Int, val dy: Int) {
        UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

        fun isOpposite(o: Dir) = dx + o.dx == 0 && dy + o.dy == 0
    }

    private val cap = cols * rows
    private val cells = IntArray(cap)        // ring buffer of packed positions, tail..head
    private val occupied = BooleanArray(cap)
    private var start = 0                     // index of the tail within `cells`

    var length = 0; private set              // number of snake cells
    var dir = Dir.RIGHT; private set          // committed direction
    private var pendingDir = Dir.RIGHT        // queued input for the next tick

    var foodX = 0; private set
    var foodY = 0; private set
    var score = 0L; private set
    var over = false; private set
    var won = false; private set              // board completely filled

    init { reset() }

    val headX: Int get() = cells[(start + length - 1) % cap] % cols
    val headY: Int get() = cells[(start + length - 1) % cap] / cols

    /** Position of body segment [i] (0 = tail, length-1 = head). */
    fun segmentX(i: Int): Int = cells[(start + i) % cap] % cols
    fun segmentY(i: Int): Int = cells[(start + i) % cap] / cols

    /** Queue a direction change. Ignores a 180° reversal into the neck. */
    fun turn(d: Dir) {
        if (over) return
        if (d.isOpposite(dir)) return
        pendingDir = d
    }

    /** Advance one logical step. Returns true if food was eaten this tick. */
    fun tick(): Boolean {
        if (over) return false
        dir = pendingDir
        val nx = headX + dir.dx
        val ny = headY + dir.dy
        if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) { over = true; return false }

        val np = ny * cols + nx
        val grow = nx == foodX && ny == foodY
        val tailPacked = cells[start]
        // Hitting the tail is fine when it is about to move away (i.e. not growing).
        if (occupied[np] && !(np == tailPacked && !grow)) { over = true; return false }

        if (grow) {
            cells[(start + length) % cap] = np
            occupied[np] = true
            length++
            score++
            if (length >= cap) { won = true; over = true } else placeFood()
        } else {
            occupied[tailPacked] = false
            start = (start + 1) % cap
            cells[(start + length - 1) % cap] = np
            occupied[np] = true
        }
        return grow
    }

    fun reset() {
        occupied.fill(false)
        over = false
        won = false
        score = 0
        start = 0
        length = 3
        val cy = rows / 2
        val headXStart = cols / 2 - 1
        for (i in 0 until length) {
            val x = headXStart - (length - 1) + i
            val p = cy * cols + x
            cells[i] = p
            occupied[p] = true
        }
        dir = Dir.RIGHT
        pendingDir = Dir.RIGHT
        placeFood()
    }

    /** Place food uniformly at random on a free cell, using the injected RNG. */
    private fun placeFood() {
        val free = cap - length
        if (free <= 0) return
        var k = (rnd() * free).toInt().coerceIn(0, free - 1)
        var idx = 0
        while (idx < cap) {
            if (!occupied[idx]) {
                if (k == 0) { foodX = idx % cols; foodY = idx / cols; return }
                k--
            }
            idx++
        }
    }

    /** Test-only seam: pin food to a specific cell to make scenarios deterministic. */
    internal fun placeFoodAt(x: Int, y: Int) { foodX = x; foodY = y }
}
