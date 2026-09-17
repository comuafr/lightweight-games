package lg.core.testutil

import lg.core.store.KeyValueStore

/** In-memory [KeyValueStore] for tests; never touches real platform storage. */
public class FakeStore : KeyValueStore {
    private val strings = HashMap<String, String>()
    private val ints = HashMap<String, Int>()
    private val booleans = HashMap<String, Boolean>()

    override fun getString(key: String, default: String?): String? = strings[key] ?: default

    override fun putString(key: String, value: String) {
        strings[key] = value
    }

    override fun getInt(key: String, default: Int): Int = ints[key] ?: default

    override fun putInt(key: String, value: Int) {
        ints[key] = value
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default

    override fun putBoolean(key: String, value: Boolean) {
        booleans[key] = value
    }

    override fun remove(key: String) {
        strings.remove(key)
        ints.remove(key)
        booleans.remove(key)
    }
}

/** [lg.core.util.Clock] with a settable, monotonic-by-default time for tests. */
public class FakeClock(private var timeMs: Long = 0L) : lg.core.util.Clock {
    override fun nowMs(): Long = timeMs

    public fun advance(byMs: Long) {
        timeMs += byMs
    }

    public fun set(timeMs: Long) {
        this.timeMs = timeMs
    }
}
