package lg.android

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt
import lg.android.billing.RestoreResult
import lg.android.ui.Strings
import lg.core.scene.StringKey

/**
 * Framework `AlertDialog` (no Material) with sound/haptics toggles bound
 * directly to [lg.core.store.Settings], a "Restore purchases" action (or an
 * "Unlocked" state instead, once the catalogue already is), and the app's
 * version name.
 */
internal object SettingsDialog {
    fun show(context: Context) {
        val app = App.from(context)
        val settings = app.settings
        val strings = Strings(context)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        container.addView(
            toggleRow(context, ::dp, context.getString(R.string.sound), settings.soundEnabled) { checked ->
                settings.soundEnabled = checked
            },
        )
        container.addView(
            toggleRow(context, ::dp, context.getString(R.string.haptics), settings.hapticsEnabled) { checked ->
                settings.hapticsEnabled = checked
            },
        )

        container.addView(
            TextView(context).apply {
                setPadding(0, dp(20), 0, dp(4))
                if (app.isUnlocked()) {
                    text = context.getString(R.string.unlocked_check)
                    isEnabled = false
                    alpha = 0.7f
                } else {
                    text = context.getString(R.string.restore)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        app.billing.restore { result ->
                            val message = when (result) {
                                RestoreResult.RESTORED -> strings.get(StringKey.RESTORED)
                                RestoreResult.NOTHING_TO_RESTORE, RestoreResult.ERROR -> strings.get(StringKey.NOTHING_TO_RESTORE)
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
        )

        container.addView(
            TextView(context).apply {
                text = context.getString(R.string.version, versionName(context))
                alpha = 0.6f
                textSize = 12f
                setPadding(0, dp(12), 0, 0)
            },
        )

        AlertDialog.Builder(context)
            .setTitle(R.string.settings)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun versionName(context: Context): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: PackageManager.NameNotFoundException) {
            "?"
        }

    private fun toggleRow(
        context: Context,
        dp: (Int) -> Int,
        label: String,
        initial: Boolean,
        onChanged: (Boolean) -> Unit,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(
                TextView(context).apply {
                    text = label
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(
                Switch(context).apply {
                    isChecked = initial
                    setOnCheckedChangeListener { _, checked -> onChanged(checked) }
                },
            )
        }
}
