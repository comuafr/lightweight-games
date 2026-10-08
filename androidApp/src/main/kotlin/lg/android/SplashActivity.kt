package lg.android

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Launcher activity: shows the app's promise (no ads, no in-game purchases,
 * no internet, ...) until the player closes it (cross or Play button), then opens [MainActivity].
 * It is only the entry point of a cold start;
 * [MainActivity] is never routed through it again.
 */
class SplashActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        column.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 36f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            },
        )
        column.addView(
            TextView(this).apply {
                text = getString(R.string.splash_tagline)
                textSize = 18f
                alpha = 0.7f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(32))
            },
        )
        for (line in getString(R.string.splash_motto).split('\n')) {
            column.addView(
                TextView(this).apply {
                    text = "✓  $line"
                    textSize = 18f
                    setPadding(0, dp(6), 0, dp(6))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        column.addView(
            Button(this).apply {
                text = getString(R.string.splash_play)
                minimumHeight = dp(52)
                minimumWidth = dp(160)
                setOnClickListener { openCatalogue() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(32)
            },
        )

        // The splash stays until the player closes it with the cross (or Play).
        val closeCross = TextView(this).apply {
            text = "✕"
            textSize = 24f
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.splash_close)
            setOnClickListener { openCatalogue() }
        }
        val root = FrameLayout(this)
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            closeCross,
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) },
        )
        setContentView(root)
        EdgeToEdge.applyContentInsets(window, root)
    }

    private fun openCatalogue() {
        if (done) return
        done = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
