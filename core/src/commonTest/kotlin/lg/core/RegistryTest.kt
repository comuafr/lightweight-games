package lg.core

import lg.core.scene.Scene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class RegistryTest {
    @Test
    public fun idsAreUnique(): Unit {
        val ids = GameRegistry.all.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
    }

    @Test
    public fun exactlyThreeFreeGamesAndFifteenTotal(): Unit {
        assertEquals(15, GameRegistry.all.size)
        assertEquals(3, GameRegistry.all.count { it.tier == Tier.FREE })
        assertEquals(12, GameRegistry.all.count { it.tier == Tier.UNLOCKED })
    }

    @Test
    public fun byIdMatchesAll(): Unit {
        for (meta in GameRegistry.all) {
            assertEquals(meta, GameRegistry.byId[meta.id])
        }
    }

    @Test
    public fun playableIncludesFreeGamesAlways(): Unit {
        val freeIds = GameRegistry.all.filter { it.tier == Tier.FREE }.map { it.id }.toSet()
        val playableLocked = GameRegistry.playable(unlocked = false).map { it.id }.toSet()
        assertTrue(playableLocked.containsAll(freeIds))
        assertTrue(playableLocked.none { GameRegistry.byId[it]!!.tier == Tier.UNLOCKED })
    }

    @Test
    public fun playableIncludesEverythingWhenUnlocked(): Unit {
        val allIds = GameRegistry.all.map { it.id }.toSet()
        val playableUnlocked = GameRegistry.playable(unlocked = true).map { it.id }.toSet()
        assertEquals(allIds, playableUnlocked)
    }

    @Test
    public fun factoryProducesAWorkingGame(): Unit {
        for (meta in GameRegistry.all) {
            val game = meta.factory()
            game.reset(seed = 1L)
            assertEquals(meta.id, game.meta.id)
        }
    }

    @Test
    public fun iconRendersWithoutDroppingPrimitives(): Unit {
        val scene = Scene()
        for (meta in GameRegistry.all) {
            scene.clear()
            meta.icon(scene, 96f)
            assertEquals(0, scene.dropped)
        }
    }

    @Test
    public fun exactlyTheFourArcadeGamesHaveAHighScoreTable(): Unit {
        val withTable = GameRegistry.all.filter { it.defaultScores != null }.map { it.id }.toSet()
        // Wall Smash has levels of its own, so it has no table.
        assertEquals(setOf("snake", "g2048", "bird", "sequence"), withTable)
        for (meta in GameRegistry.all) {
            val defaults = meta.defaultScores ?: continue
            assertEquals(lg.core.store.Scores.TABLE_SIZE, defaults.size, "${meta.id} needs one default per line")
            for (i in 1 until defaults.size) {
                assertTrue(defaults[i - 1] > defaults[i], "${meta.id} defaults must strictly decrease")
            }
            assertTrue(defaults.last() > 0, "${meta.id} defaults must be positive")
            assertEquals(1, meta.players.last, "${meta.id} is a one-player game")
        }
    }
}
