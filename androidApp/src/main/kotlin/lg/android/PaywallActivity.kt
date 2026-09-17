package lg.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.android.billingclient.api.ProductDetails
import kotlin.math.roundToInt
import lg.android.billing.ProductInfo
import lg.android.billing.PurchaseOutcome
import lg.android.billing.RestoreResult
import lg.android.ui.HoldToConfirmButton
import lg.android.ui.Strings
import lg.android.ui.TileView
import lg.core.GameMeta
import lg.core.GameRegistry
import lg.core.Tier
import lg.core.scene.StringKey

/**
 * The "unlock all" paywall (PLAN.md §6, §8): the locked game's icon, title
 * and body copy, the price once known, a parental-gate hold button, a
 * "Restore purchases" action, and an offline notice when the price can't be
 * fetched. Never blocks: there is no spinner, the back button always works,
 * and an unreachable store only ever disables the buy button — it never
 * traps the user on this screen.
 *
 * On a successful purchase or restore this finishes itself; [MainActivity]
 * re-reads [App.isUnlocked] in `onResume` and rebuilds the grid, so padlocks
 * disappear as soon as the catalogue is back on screen.
 */
class PaywallActivity : Activity() {
    private lateinit var strings: Strings
    private lateinit var priceText: TextView
    private lateinit var offlineText: TextView
    private lateinit var unlockButton: HoldToConfirmButton
    private var productDetails: ProductDetails? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        strings = Strings(this)

        val meta = intent.getStringExtra(EXTRA_GAME_ID)?.let { GameRegistry.byId[it] }
            ?: GameRegistry.all.firstOrNull { it.tier == Tier.UNLOCKED }
        if (meta == null) {
            finish()
            return
        }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dp(24)
            setPadding(pad, dp(32), pad, dp(24))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        EdgeToEdge.applyContentInsets(window, scroll)

        root.addView(buildIcon(meta), LinearLayout.LayoutParams(dp(140), dp(140)))
        root.addView(spacer(16))
        root.addView(centeredText(strings.get(StringKey.UNLOCK_TITLE), 22f, bold = true))
        root.addView(spacer(8))
        root.addView(centeredText(strings.get(StringKey.UNLOCK_BODY), 15f, bold = false))
        root.addView(spacer(4))

        priceText = centeredText("", 17f, bold = true)
        root.addView(priceText)

        offlineText = centeredText(strings.get(StringKey.OFFLINE_NOTICE), 13f, bold = false).apply {
            visibility = View.GONE
            alpha = 0.7f
        }
        root.addView(offlineText)
        root.addView(spacer(24))

        unlockButton = HoldToConfirmButton(this).apply {
            label = strings.get(StringKey.UNLOCK_ALL)
            isEnabled = false
            onConfirmed = { onGateConfirmed() }
        }
        root.addView(
            unlockButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)),
        )
        root.addView(spacer(6))
        root.addView(centeredText(strings.get(StringKey.PARENTAL_GATE), 12f, bold = false).apply { alpha = 0.6f })
        root.addView(spacer(20))

        val restoreButton = TextView(this).apply {
            text = strings.get(StringKey.RESTORE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onRestoreClicked() }
        }
        root.addView(restoreButton)

        loadProduct()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Not onStop(): launchBillingFlow() shows Play's own purchase sheet
        // on top of this Activity, which stops it for the whole purchase --
        // onPurchasesUpdated's result typically arrives around the time this
        // Activity resumes, sometimes before, sometimes just after. Clearing
        // the listener on stop would routinely drop that result. onDestroy()
        // is still well before this class could otherwise leak this
        // Activity: PlayBilling holds no other reference to it.
        App.from(this).billing.purchaseListener = null
    }

    private fun buildIcon(meta: GameMeta): View =
        TileView(this, meta).apply { locked = true }

    private fun loadProduct() {
        App.from(this).billing.queryProduct { info -> onProductLoaded(info) }
    }

    private fun onProductLoaded(info: ProductInfo?) {
        if (isFinishing || isDestroyed) return
        if (info == null) {
            priceText.visibility = View.GONE
            offlineText.visibility = View.VISIBLE
            unlockButton.isEnabled = false
            return
        }
        productDetails = info.productDetails
        priceText.text = info.formattedPrice
        priceText.visibility = View.VISIBLE
        offlineText.visibility = View.GONE
        unlockButton.isEnabled = true
    }

    private fun onGateConfirmed() {
        val details = productDetails ?: return
        App.from(this).billing.purchaseListener = { outcome -> onPurchaseOutcome(outcome) }
        App.from(this).billing.launchPurchase(this, details)
    }

    private fun onPurchaseOutcome(outcome: PurchaseOutcome) {
        if (isFinishing || isDestroyed) return
        when (outcome) {
            PurchaseOutcome.SUCCESS -> finish()
            PurchaseOutcome.PENDING -> Toast.makeText(this, strings.get(StringKey.PURCHASE_PENDING), Toast.LENGTH_LONG).show()
            PurchaseOutcome.FAILED -> Toast.makeText(this, strings.get(StringKey.PURCHASE_FAILED), Toast.LENGTH_SHORT).show()
        }
    }

    private fun onRestoreClicked() {
        App.from(this).billing.restore { result -> onRestoreResult(result) }
    }

    private fun onRestoreResult(result: RestoreResult) {
        if (isFinishing || isDestroyed) return
        when (result) {
            RestoreResult.RESTORED -> {
                Toast.makeText(this, strings.get(StringKey.RESTORED), Toast.LENGTH_SHORT).show()
                finish()
            }
            RestoreResult.NOTHING_TO_RESTORE, RestoreResult.ERROR ->
                Toast.makeText(this, strings.get(StringKey.NOTHING_TO_RESTORE), Toast.LENGTH_SHORT).show()
        }
    }

    private fun centeredText(value: String, sizeSp: Float, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun spacer(heightDp: Int): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_GAME_ID = "game_id"

        fun start(context: Context, gameId: String?) {
            val intent = Intent(context, PaywallActivity::class.java)
            if (gameId != null) intent.putExtra(EXTRA_GAME_ID, gameId)
            context.startActivity(intent)
        }
    }
}
