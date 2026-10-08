package lg.android.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt
import lg.android.R

/**
 * Full-screen "how to play" page shown the first time a game is opened: the
 * game's title, its rules and its controls, and a single button to start.
 * Texts live in `strings.xml` as `rules_<id>` / `controls_<id>` (one pair per
 * game id); [hasHelp] is false for a game that has none, so a new game
 * without text simply skips the page instead of crashing.
 */
internal class HelpView(
    context: Context,
    gameId: String,
    title: String,
    private val onDismiss: () -> Unit,
) : ScrollView(context) {

    init {
        isClickable = true // swallow touches so nothing reaches the game underneath
        isFillViewport = true
        setBackgroundColor(context.getColor(R.color.window_background))

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(56), dp(24), dp(32))
        }
        column.addView(
            TextView(context).apply {
                text = title
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(24))
            },
        )
        column.addSection(context.getString(R.string.help_rules), text(context, "rules_$gameId"))
        column.addSection(context.getString(R.string.help_controls), text(context, "controls_$gameId"))
        column.addView(
            Button(context).apply {
                text = context.getString(R.string.help_got_it)
                minimumHeight = dp(56)
                setOnClickListener { onDismiss() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            },
        )
        addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Closes the page exactly as the "Got it" button does. */
    fun dismiss() = onDismiss()

    private fun LinearLayout.addSection(heading: String, body: String) {
        addView(
            TextView(context).apply {
                text = heading
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                alpha = 0.6f
                isAllCaps = true
                setPadding(0, dp(8), 0, dp(4))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        addView(
            TextView(context).apply {
                text = body
                textSize = 18f
                setLineSpacing(0f, 1.15f)
                setPadding(0, 0, 0, dp(16))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private fun resId(context: Context, name: String): Int =
            context.resources.getIdentifier(name, "string", context.packageName)

        private fun text(context: Context, name: String): String = context.getString(resId(context, name))

        /** True when both `rules_<id>` and `controls_<id>` exist for [gameId]. */
        fun hasHelp(context: Context, gameId: String): Boolean =
            resId(context, "rules_$gameId") != 0 && resId(context, "controls_$gameId") != 0

        fun seenKey(gameId: String): String = "help.seen.$gameId"
    }
}
