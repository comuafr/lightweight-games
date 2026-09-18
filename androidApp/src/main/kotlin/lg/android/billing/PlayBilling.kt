package lg.android.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import lg.core.store.Entitlement
import lg.core.store.EntitlementSource

/** [ProductDetails] plus the formatted price string the paywall shows. */
internal data class ProductInfo(val productDetails: ProductDetails, val formattedPrice: String)

/** Outcome of a `queryPurchasesAsync` restore, as shown to the user. */
internal enum class RestoreResult { RESTORED, NOTHING_TO_RESTORE, ERROR }

/** Outcome delivered to whoever is listening for the result of a just-launched purchase flow. */
internal enum class PurchaseOutcome { SUCCESS, PENDING, FAILED }

/**
 * Thin wrapper around Play Billing 8 (PLAN.md §6). Nothing here runs at
 * construction time or app start: [BillingClient] itself, and the
 * connection to the Play Store service, are only created the first time
 * [queryProduct], [restore] or [silentRevalidate] is actually called (from
 * the paywall, "Restore purchases", or the catalogue's opportunistic
 * revalidation).
 *
 * The "local state is the source of truth" rule is implemented by
 * [reconcile] (a pure, separately unit-tested function): the store only
 * confirms an entitlement (a token is currently owned) or revokes it on
 * proof (empty successful query while the local source is [EntitlementSource.PLAY]
 * — a refund); any store or network error leaves the local state untouched.
 *
 * Every callback parameter here (`onReady`, [queryProduct]'s callback,
 * [restore]'s callback, [purchaseListener]) is always invoked on
 * [mainHandler]'s thread (the main thread in production), even when the
 * underlying Play Billing call already happens to be on it, so callers never
 * need to think about threading.
 *
 * [launchPurchase] takes the launching [Activity] only as a call parameter —
 * it is never stored in a field — so this class holds no reference to any
 * Activity once a call returns; [purchaseListener] is a plain settable
 * property the caller (the paywall) is expected to clear when it stops, for
 * the same reason.
 */
internal class PlayBilling(
    context: Context,
    private val entitlement: Entitlement,
    private val mainHandler: Handler,
) {
    private val appContext = context.applicationContext

    private var client: BillingClient? = null
    private var connecting = false
    private var reconnectAttempted = false
    private val pendingReady = ArrayList<(Boolean) -> Unit>()

    /**
     * Set by the paywall while it is on screen; receives the outcome of a
     * purchase started by [launchPurchase] (including the
     * `ITEM_ALREADY_OWNED` → restore fallback). Never called after the
     * paywall clears it, and never leaked past that: this class does not
     * reference the paywall Activity anywhere else.
     */
    var purchaseListener: ((PurchaseOutcome) -> Unit)? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        onPurchasesUpdated(result, purchases)
    }

    // ---------------------------------------------------------------- connection

    /**
     * Connects if needed (creating the [BillingClient] on first use) and
     * invokes [onReady] with whether it is now usable. Safe to call from
     * several places at once: every caller's callback is queued and flushed
     * together once the in-flight connection attempt finishes. Retries
     * exactly once if the service reports [BillingClient.BillingResponseCode.SERVICE_DISCONNECTED]
     * or disconnects before setup finishes.
     */
    fun connect(onReady: (Boolean) -> Unit) {
        val current = client
        if (current != null && current.isReady) {
            post { onReady(true) }
            return
        }
        pendingReady.add(onReady)
        if (connecting) return
        startConnection()
    }

    private fun startConnection() {
        connecting = true
        val c = client ?: newClient().also { client = it }
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                when {
                    result.responseCode == BillingClient.BillingResponseCode.OK -> {
                        reconnectAttempted = false
                        flushReady(true)
                    }
                    result.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED && !reconnectAttempted -> {
                        reconnectAttempted = true
                        startConnection()
                    }
                    else -> {
                        reconnectAttempted = false
                        flushReady(false)
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
                if (!reconnectAttempted) {
                    reconnectAttempted = true
                    startConnection()
                } else {
                    reconnectAttempted = false
                    flushReady(false)
                }
            }
        })
    }

    private fun newClient(): BillingClient =
        BillingClient.newBuilder(appContext)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()

    private fun flushReady(ready: Boolean) {
        val callbacks = ArrayList(pendingReady)
        pendingReady.clear()
        for (callback in callbacks) post { callback(ready) }
    }

    // ---------------------------------------------------------------- paywall

    /**
     * Looks up [Entitlement.PRODUCT_ID]'s price. Calls back with `null` when
     * the store cannot be reached, the query fails, or the product is
     * missing (all treated as "offline" by the paywall — never an error
     * dialog, never a retry loop).
     */
    fun queryProduct(callback: (ProductInfo?) -> Unit) {
        connect { ready ->
            val c = client
            if (!ready || c == null) {
                callback(null)
                return@connect
            }
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(Entitlement.PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
            c.queryProductDetailsAsync(params) { result, queryResult ->
                post {
                    val details = queryResult.productDetailsList.firstOrNull { it.productId == Entitlement.PRODUCT_ID }
                    val price = details?.oneTimePurchaseOfferDetails?.formattedPrice
                    if (result.responseCode != BillingClient.BillingResponseCode.OK || details == null || price == null) {
                        callback(null)
                    } else {
                        callback(ProductInfo(details, price))
                    }
                }
            }
        }
    }

    /** Opens the Play purchase sheet for [productDetails]. Outcome arrives via [purchaseListener]. */
    fun launchPurchase(activity: Activity, productDetails: ProductDetails) {
        val c = client ?: return
        val offerToken = productDetails.oneTimePurchaseOfferDetails?.offerToken ?: return
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        c.launchBillingFlow(activity, flowParams)
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        post {
            val list = purchases.orEmpty()
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    val purchased = list.firstOrNull {
                        it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.contains(Entitlement.PRODUCT_ID)
                    }
                    val pending = list.firstOrNull {
                        it.purchaseState == Purchase.PurchaseState.PENDING && it.products.contains(Entitlement.PRODUCT_ID)
                    }
                    when {
                        purchased != null -> {
                            acknowledgeIfNeeded(purchased)
                            entitlement.grant(EntitlementSource.PLAY, purchased.purchaseToken)
                            purchaseListener?.invoke(PurchaseOutcome.SUCCESS)
                        }
                        pending != null -> purchaseListener?.invoke(PurchaseOutcome.PENDING)
                        else -> Unit
                    }
                }
                BillingClient.BillingResponseCode.USER_CANCELED -> Unit
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    restore { restoreResult ->
                        purchaseListener?.invoke(
                            if (restoreResult == RestoreResult.RESTORED) PurchaseOutcome.SUCCESS else PurchaseOutcome.FAILED,
                        )
                    }
                }
                else -> purchaseListener?.invoke(PurchaseOutcome.FAILED)
            }
        }
    }

    // ---------------------------------------------------------------- restore / revalidate

    /** Explicit "Restore purchases": queries owned items and reconciles via [reconcile]. Always calls back. */
    fun restore(callback: (RestoreResult) -> Unit) = queryAndReconcile(allowRevoke = true, callback)

    private fun queryAndReconcile(allowRevoke: Boolean, callback: (RestoreResult) -> Unit) {
        connect { ready ->
            val c = client
            if (!ready || c == null) {
                callback(RestoreResult.ERROR)
                return@connect
            }
            val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            c.queryPurchasesAsync(params) { result, purchases ->
                post { callback(applyRestoreResult(result, purchases, allowRevoke)) }
            }
        }
    }

    /**
     * Opportunistic, silent revalidation (PLAN.md §6): connects, queries,
     * shows nothing, and swallows every outcome including errors. Called at
     * most once per process by the catalogue, only when
     * [Entitlement.shouldRevalidate] is true. It can only CONFIRM or GRANT,
     * never revoke: a buyer keeps playing offline, with another account, or
     * with a stale Play cache. Only the explicit [restore] honours a refund.
     */
    fun silentRevalidate() {
        queryAndReconcile(allowRevoke = false) { /* no UI */ }
    }

    private fun applyRestoreResult(result: BillingResult, purchases: List<Purchase>, allowRevoke: Boolean): RestoreResult {
        val queryOk = result.responseCode == BillingClient.BillingResponseCode.OK
        val ownedTokens = purchases
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.contains(Entitlement.PRODUCT_ID) }
            .map { it.purchaseToken }
        return when (val action = reconcile(entitlement.source(), queryOk, ownedTokens, allowRevoke)) {
            is ReconcileAction.Grant -> {
                purchases.firstOrNull { it.purchaseToken == action.token }?.let(::acknowledgeIfNeeded)
                entitlement.grant(EntitlementSource.PLAY, action.token)
                RestoreResult.RESTORED
            }
            ReconcileAction.Revoke -> {
                entitlement.revoke()
                RestoreResult.NOTHING_TO_RESTORE
            }
            ReconcileAction.Keep -> {
                if (queryOk) {
                    // A silent check that found nothing for a Play-sourced entitlement is NOT a
                    // confirmation: leave lastVerifiedMs alone so the next catalogue open retries.
                    val unconfirmedSilentMiss = !allowRevoke && entitlement.source() == EntitlementSource.PLAY
                    if (!unconfirmedSilentMiss) entitlement.markVerified()
                    RestoreResult.NOTHING_TO_RESTORE
                } else {
                    RestoreResult.ERROR
                }
            }
        }
    }

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val c = client ?: return
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        // Fire-and-forget: if this silently fails, the next restore/revalidate
        // (or Play's own retry) will see `isAcknowledged == false` again and
        // retry it; the local grant() above has already made the game
        // playable, which is all that matters offline.
        c.acknowledgePurchase(params) { }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }
}
