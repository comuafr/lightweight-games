package lg.android.billing

import lg.core.store.EntitlementSource

/**
 * Pure decision logic for reconciling the local [lg.core.store.Entitlement]
 * against the result of a Play `queryPurchasesAsync(INAPP)` call (used by
 * both the explicit "Restore purchases" action and [PlayBilling]'s silent
 * revalidation). Free of every Play Services / Android type so it is
 * unit-testable on a plain JVM (`EntitlementReconcilerTest`, run by
 * `:androidApp:testDebugUnitTest`) without Robolectric or an emulator.
 *
 * Encodes PLAN.md §6's "règle d'or": the local state is the source of truth
 * for playing; the store only confirms it (a token is present) or revokes it
 * on proof (an empty, *successful* query while the current entitlement was
 * granted by Play itself — the refund case). A failed query never changes
 * anything.
 */
internal sealed interface ReconcileAction {
    /** A token is currently owned: (re)grant the entitlement to it. */
    data class Grant(val token: String) : ReconcileAction

    /** The query succeeded, came back empty, and Play was the current source: refund. */
    data object Revoke : ReconcileAction

    /** Nothing should change: query failed, or nothing owned but Play wasn't the source anyway. */
    data object Keep : ReconcileAction
}

/**
 * @param current The entitlement source recorded locally right now.
 * @param queryOk Whether the store query itself succeeded (network/service reachable).
 * @param ownedTokens Purchase tokens currently owned (purchased, not pending) per the query, if [queryOk].
 */
internal fun reconcile(
    current: EntitlementSource,
    queryOk: Boolean,
    ownedTokens: List<String>,
): ReconcileAction {
    if (!queryOk) return ReconcileAction.Keep
    val token = ownedTokens.firstOrNull()
    if (token != null) return ReconcileAction.Grant(token)
    return if (current == EntitlementSource.PLAY) ReconcileAction.Revoke else ReconcileAction.Keep
}
