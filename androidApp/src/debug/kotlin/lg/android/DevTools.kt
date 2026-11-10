package lg.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import lg.core.session.GameSession
import lg.core.store.EntitlementSource
import lg.core.store.KeyValueStore

/**
 * Debug builds only (this file does not exist in release builds): a row in the settings dialog that
 * simulates the "unlock all" purchase, or takes it back, so locked games can be tried without Play.
 */
internal object DevTools {
    /** Intent extra holding a saved-game snapshot to put the game in, for store screenshots (debug builds only). */
    private const val EXTRA_DEV_SAVE = "dev_save"

    /**
     * `adb shell am start -n fr.comua.lightgames/lg.android.GameActivity --es game_id g2048 --es dev_save "<snapshot>"`
     * starts the game straight in the given saved state (no start screen), to take store screenshots of an advanced game.
     */
    fun onGameOpened(session: GameSession, store: KeyValueStore, gameId: String, intent: Intent) {
        val snapshot = intent.getStringExtra(EXTRA_DEV_SAVE) ?: return
        store.putString("save.$gameId", snapshot)
        session.start(players = 1)
        session.tryRestore(store)
    }

    fun settingsRow(context: Context, dp: (Int) -> Int): View {
        val entitlement = App.from(context).entitlement
        return TextView(context).apply {
            text = if (entitlement.isUnlocked()) "DEV: simulate refund (lock games again)" else "DEV: simulate purchase (unlock all)"
            setPadding(0, dp(20), 0, dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (entitlement.isUnlocked()) entitlement.revoke() else entitlement.grant(EntitlementSource.PLAY, "dev-simulated")
                (context as? Activity)?.recreate()
            }
        }
    }
}
