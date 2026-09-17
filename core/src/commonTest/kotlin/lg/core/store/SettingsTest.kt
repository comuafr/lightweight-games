package lg.core.store

import lg.core.testutil.FakeStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

public class SettingsTest {
    @Test
    public fun defaultsToEnabled(): Unit {
        val settings = Settings(FakeStore())
        assertTrue(settings.soundEnabled)
        assertTrue(settings.hapticsEnabled)
    }

    @Test
    public fun soundEnabledPersists(): Unit {
        val store = FakeStore()
        val settings = Settings(store)
        settings.soundEnabled = false
        assertFalse(Settings(store).soundEnabled)
    }

    @Test
    public fun hapticsEnabledPersists(): Unit {
        val store = FakeStore()
        val settings = Settings(store)
        settings.hapticsEnabled = false
        assertFalse(Settings(store).hapticsEnabled)
    }

    @Test
    public fun soundAndHapticsAreIndependent(): Unit {
        val settings = Settings(FakeStore())
        settings.soundEnabled = false
        assertTrue(settings.hapticsEnabled)
    }
}
