package lg.android

import android.content.Context
import android.view.View

/** Release builds have no developer tools: this is the empty counterpart of `src/debug/.../DevTools.kt`. */
internal object DevTools {
    @Suppress("UNUSED_PARAMETER")
    fun settingsRow(context: Context, dp: (Int) -> Int): View? = null
}
