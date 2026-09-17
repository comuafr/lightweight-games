package lg.preview

// Dev-only tool; see Java2DRenderer.kt header. Minimal in-memory
// implementations of core's small platform ports, so a GameSession can run
// headlessly without any real Android/iOS storage or clock.

import lg.core.store.KeyValueStore
import lg.core.util.Clock

/** Plain in-memory [KeyValueStore]; nothing is persisted across process runs. */
internal class InMemoryStore : KeyValueStore {
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

/** [Clock] driven manually by the preview's frame loop instead of wall-clock time. */
internal class ManualClock(startMs: Long = 0L) : Clock {
    private var current = startMs

    override fun nowMs(): Long = current

    fun advance(deltaMs: Long) {
        current += deltaMs
    }
}
