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
}
