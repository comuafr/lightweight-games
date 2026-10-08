package lg.android

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.TextView
import lg.core.store.EntitlementSource

/**
 * Debug builds only (this file does not exist in release builds): a row in the settings dialog that
 * simulates the "unlock all" purchase, or takes it back, so locked games can be tried without Play.
 */
internal object DevTools {
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
