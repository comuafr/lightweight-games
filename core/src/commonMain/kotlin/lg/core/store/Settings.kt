package lg.core.store

/** User-facing toggles, persisted via [KeyValueStore]. Both default to enabled. */
public class Settings(private val store: KeyValueStore) {
    public var soundEnabled: Boolean
        get() = store.getBoolean(KEY_SOUND, true)
        set(value) = store.putBoolean(KEY_SOUND, value)

    public var hapticsEnabled: Boolean
        get() = store.getBoolean(KEY_HAPTICS, true)
        set(value) = store.putBoolean(KEY_HAPTICS, value)

    private companion object {
        const val KEY_SOUND = "set.sound"
        const val KEY_HAPTICS = "set.haptics"
    }
}
