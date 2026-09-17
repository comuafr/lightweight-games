package lg.core.store

/**
 * Minimal persistent key-value store, implemented by the platform
 * (`SharedPreferences` on Android, `UserDefaults` on iOS) and injected into
 * [lg.core.session.GameSession], [Settings] and [Scores]. Deliberately an
 * interface rather than `expect`/`actual` so it stays easy to fake in tests.
 */
public interface KeyValueStore {
    public fun getString(key: String, default: String?): String?
    public fun putString(key: String, value: String)
    public fun getInt(key: String, default: Int): Int
    public fun putInt(key: String, value: Int)
    public fun getBoolean(key: String, default: Boolean): Boolean
    public fun putBoolean(key: String, value: Boolean)
    public fun remove(key: String)
}
