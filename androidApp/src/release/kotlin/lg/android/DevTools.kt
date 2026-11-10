package lg.android

import android.content.Context
import android.content.Intent
import android.view.View
import lg.core.session.GameSession
import lg.core.store.KeyValueStore

/** Release builds have no developer tools: this is the empty counterpart of `src/debug/.../DevTools.kt`. */
internal object DevTools {
    @Suppress("UNUSED_PARAMETER")
    fun settingsRow(context: Context, dp: (Int) -> Int): View? = null

    @Suppress("UNUSED_PARAMETER")
    fun onGameOpened(session: GameSession, store: KeyValueStore, gameId: String, intent: Intent) = Unit
}
