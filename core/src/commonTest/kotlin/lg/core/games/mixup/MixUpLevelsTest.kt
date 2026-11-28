package lg.core.games.mixup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class MixUpLevelsTest {
    @Test
    public fun thirtyWellFormedLevels() {
        assertEquals(MixUpGame.LEVEL_COUNT, MixUpLevels.all.size)
        for ((index, level) in MixUpLevels.all.withIndex()) {
            val n = index + 1
            assertTrue(level.moves > 0 && level.colors in 4..MixUpBoard.SHAPES, "level $n")
            val ice = (0 until MixUpBoard.CELLS).sumOf { level.iceAt(it) }
            when (level.goal) {
                Goal.SCORE -> assertTrue(level.scoreTarget > 0 && ice == 0, "level $n")
                Goal.COLLECT -> {
                    assertTrue(level.collect.isNotEmpty() && level.collect.size % 2 == 0, "level $n")
                    for (k in level.collect.indices step 2) {
                        assertTrue(level.collect[k] in 0 until level.colors && level.collect[k + 1] > 0, "level $n")
                    }
                }
                Goal.ICE -> assertTrue(ice > 0, "level $n")
            }
        }
    }

    /** Fifty fixed boards per level; the share the bot wins must stay above a floor that gets lower with the levels. */
    @Test
    public fun everyLevelCanBeWon() {
        val rates = IntArray(MixUpGame.LEVEL_COUNT)
        for (level in 1..MixUpGame.LEVEL_COUNT) {
            var won = 0
            for (seed in 1..SEEDS) if (MixUpBot.play(level, seed * 7919L + level)) won++
            rates[level - 1] = won * 100 / SEEDS
        }
        println("MIXUP " + rates.mapIndexed { i, r -> "${i + 1}:$r" }.joinToString(" "))
        val tooHard = rates.indices.filter { rates[it] < floor(it + 1) }.map { "${it + 1} (${rates[it]}%)" }
        assertTrue(tooHard.isEmpty(), "levels the bot wins too rarely: $tooHard")
    }

    private fun floor(level: Int): Int = when {
        level <= 10 -> 80
        level <= 20 -> 50
        else -> 25
    }

    private companion object {
        const val SEEDS = 50
    }
}
