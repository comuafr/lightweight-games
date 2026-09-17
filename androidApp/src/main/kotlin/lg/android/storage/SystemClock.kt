package lg.android.storage

import lg.core.util.Clock

/** [Clock] backed by the device wall clock. */
internal class SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
