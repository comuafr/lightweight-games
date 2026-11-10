package lg.android

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt
import lg.android.billing.RestoreResult
import lg.android.ui.TileView
import lg.core.GameMeta
import lg.core.GameRegistry
import lg.core.Tier

/**
 * Game catalogue: a settings button up top, then a grid of [TileView]s (2
 * columns in portrait phones, 3 in landscape, 4 on wide/tablet screens),
 * built programmatically — no XML layouts, no RecyclerView. Only M0's single
 * free game (Snake) is registered so far; the grid logic already handles an
 * arbitrary catalogue length and an incomplete last row.
 */
class MainActivity : Activity() {
    private lateinit var gridContainer: LinearLayout
    private lateinit var banner: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(buildHeader())

        banner = buildBanner()
        root.addView(banner)

        gridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(
            gridContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        setContentView(scroll)
        EdgeToEdge.applyContentInsets(window, scroll)
        rebuildGrid()
    }

    override fun onResume() {
        super.onResume()
        // Cheap and rare (catalogue-sized) rebuild; picks up any entitlement
        // or locale change made while this activity was stopped -- in
        // particular, returning from a successful PaywallActivity purchase
        // or restore.
        rebuildGrid()

        // Opportunistic and silent (PLAN.md §6): never blocking, never shown to the user. It asks the store
        // when the games are locked (a reinstall or a new phone loses the local copy of a purchase that still
        // belongs to the account) or when an unlock is due for confirmation, and can only unlock, never lock.
        // Once per process, unless the store could not be reached: then the next resume tries again.
        val app = App.from(this)
        if (!revalidateAttempted && app.entitlement.shouldCheckStore()) {
            revalidateAttempted = true
            app.billing.silentRevalidate { result ->
                when (result) {
                    RestoreResult.RESTORED -> if (!isFinishing && !isDestroyed) rebuildGrid()
                    RestoreResult.ERROR -> revalidateAttempted = false
                    RestoreResult.NOTHING_TO_RESTORE -> Unit
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuildGrid()
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), 0)
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val settingsButton = Button(this).apply {
            text = "⚙"
            contentDescription = getString(R.string.settings)
            minWidth = dp(48)
            minimumHeight = dp(48)
            setOnClickListener { SettingsDialog.show(this@MainActivity) }
        }
        header.addView(title)
        header.addView(settingsButton)
        return header
    }

    private fun buildBanner(): TextView =
        TextView(this).apply {
            text = getString(R.string.unlock_all)
            textSize = 15f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

    private fun rebuildGrid() {
        gridContainer.removeAllViews()
        val columns = columnsForCurrentConfig()
        val games = GameRegistry.all
        val unlocked = App.from(this).isUnlocked()

        banner.visibility = if (unlocked) View.GONE else View.VISIBLE
        if (!unlocked) {
            val firstLocked = games.firstOrNull { it.tier == Tier.UNLOCKED }
            banner.setOnClickListener { PaywallActivity.start(this, firstLocked?.id) }
        }

        var index = 0
        while (index < games.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until columns) {
                val cellParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    val m = dp(8)
                    setMargins(m, m, m, m)
                }
                if (index < games.size) {
                    val meta = games[index]
                    row.addView(buildTile(meta, unlocked), cellParams)
                    index++
                } else {
                    // Keeps every tile in this row the same size even if the
                    // catalogue doesn't divide evenly by `columns`.
                    row.addView(View(this), cellParams)
                }
            }
            gridContainer.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private fun buildTile(meta: GameMeta, unlocked: Boolean): TileView {
        val locked = meta.tier == Tier.UNLOCKED && !unlocked
        val tile = TileView(this, meta)
        tile.locked = locked
        tile.borderIndex = GameRegistry.all.indexOf(meta)
        tile.setOnClickListener { onTileClicked(meta, locked) }
        return tile
    }

    private fun onTileClicked(meta: GameMeta, locked: Boolean) {
        if (locked) {
            PaywallActivity.start(this, meta.id)
            return
        }
        startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_GAME_ID, meta.id))
    }

    private fun columnsForCurrentConfig(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return when {
            widthDp >= 600 -> 4
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE -> 3
            else -> 2
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        // Process-lifetime latch (PLAN.md §6): MainActivity itself may be
        // re-created many times (rotation, coming back from the paywall) but
        // the silent revalidation must fire at most once per process.
        var revalidateAttempted = false
    }
}
