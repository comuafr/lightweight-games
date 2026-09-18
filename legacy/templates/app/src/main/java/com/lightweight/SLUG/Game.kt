package com.lightweight.{{SLUG_PKG}}

/**
 * TEMPLATE — pure game logic. NO Android imports allowed in this file.
 *
 * Rules:
 *  - Deterministic and unit-testable on the JVM.
 *  - Inject randomness as a () -> Double so tests can seed it.
 *  - Prefer primitive arrays; avoid per-frame allocation.
 *
 * Replace the body below with the actual game. The CI greps this file to ensure
 * it never imports android.* or androidx.*.
 */
class Game(private val rnd: () -> Double = Math::random) {

    var score: Long = 0
        private set
    var over: Boolean = false
        private set

    /** Advance the simulation by one logical tick. Return value/events as needed. */
    fun tick() {
        // TODO: implement core loop step (movement, collisions, scoring, lose check).
    }

    /** Example helper: a fair random integer in [0, n). */
    fun rollBelow(n: Int): Int = (rnd() * n).toInt().coerceIn(0, n - 1)

    /** Reset to a fresh game. */
    fun reset() {
        score = 0
        over = false
        // TODO: reset board/state
    }
}
