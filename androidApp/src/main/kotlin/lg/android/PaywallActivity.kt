package lg.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import kotlin.math.roundToInt
import lg.android.billing.ProductResult
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
 * "Restore purchases" action, and, when the price can't be fetched, a message
 * saying why (store unreachable, product not offered, other error with its code)
 * and a "Try again" button. Never blocks: the back button always works, a store
 * that never answers is given up on after [LOAD_TIMEOUT_MS], and an unreachable
 * store only ever disables the buy button — it never traps the user on this screen.
 *
 * On a successful purchase or restore this finishes itself; [MainActivity]
 * re-reads [App.isUnlocked] in `onResume` and rebuilds the grid, so padlocks
 * disappear as soon as the catalogue is back on screen.
 */
class PaywallActivity : Activity() {
    private lateinit var strings: Strings
    private lateinit var priceText: TextView
    private lateinit var statusText: TextView
    private lateinit var retryButton: TextView
    private lateinit var unlockButton: HoldToConfirmButton
    private var productDetails: ProductDetails? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Incremented by each load, so a late answer to an abandoned or timed-out load is ignored. */
    private var loadId = 0

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

        statusText = centeredText("", 13f, bold = false).apply {
            visibility = View.GONE
            alpha = 0.8f
        }
        root.addView(statusText)
        retryButton = TextView(this).apply {
            text = getString(R.string.billing_retry)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { loadProduct() }
        }
        root.addView(retryButton)
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
        loadId++
        handler.removeCallbacksAndMessages(null)
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
        val id = ++loadId
        showStatus(getString(R.string.billing_connecting), retry = false)
        unlockButton.isEnabled = false
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (id == loadId) {
                loadId++
                showFailure(getString(R.string.billing_store_unavailable), null)
            }
        }, LOAD_TIMEOUT_MS)
        App.from(this).billing.queryProduct { result ->
            if (id != loadId) return@queryProduct
            handler.removeCallbacksAndMessages(null)
            onProductLoaded(result)
        }
    }

    private fun onProductLoaded(result: ProductResult) {
        if (isFinishing || isDestroyed) return
        when (result) {
            is ProductResult.Loaded -> {
                productDetails = result.info.productDetails
                priceText.text = result.info.formattedPrice
                priceText.visibility = View.VISIBLE
                statusText.visibility = View.GONE
                retryButton.visibility = View.GONE
                unlockButton.isEnabled = true
            }
            is ProductResult.StoreUnavailable -> showFailure(getString(R.string.billing_store_unavailable), result.code)
            ProductResult.NotFound -> showFailure(getString(R.string.billing_product_not_found), null)
            is ProductResult.Error -> showFailure(getString(R.string.billing_error), result.code)
        }
    }

    private fun showFailure(message: String, code: Int?) {
        priceText.visibility = View.GONE
        unlockButton.isEnabled = false
        showStatus(if (code == null) message else message + "\n" + getString(R.string.billing_code, code), retry = true)
    }

    private fun showStatus(message: String, retry: Boolean) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
        retryButton.visibility = if (retry) View.VISIBLE else View.GONE
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
            PurchaseOutcome.FAILED -> {
                val code = App.from(this).billing.lastErrorCode
                val message = if (code == BillingClient.BillingResponseCode.ITEM_UNAVAILABLE) {
                    getString(R.string.purchase_unavailable)
                } else {
                    strings.get(StringKey.PURCHASE_FAILED) + " (" + getString(R.string.billing_code, code) + ")"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
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
            RestoreResult.NOTHING_TO_RESTORE ->
                Toast.makeText(this, strings.get(StringKey.NOTHING_TO_RESTORE), Toast.LENGTH_SHORT).show()
            RestoreResult.ERROR ->
                Toast.makeText(this, getString(R.string.restore_error), Toast.LENGTH_LONG).show()
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

        /** Gives up waiting for Google Play after this long and offers to try again. */
        private const val LOAD_TIMEOUT_MS = 12_000L

        fun start(context: Context, gameId: String?) {
            val intent = Intent(context, PaywallActivity::class.java)
            if (gameId != null) intent.putExtra(EXTRA_GAME_ID, gameId)
            context.startActivity(intent)
        }
    }
}
