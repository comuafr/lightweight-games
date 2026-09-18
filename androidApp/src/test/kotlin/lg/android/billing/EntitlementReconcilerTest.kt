package lg.android.billing

import lg.core.store.EntitlementSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plain JVM test: [reconcile] touches no Android or Play Services type. */
class EntitlementReconcilerTest {
    @Test
    fun `owned token grants regardless of current source`() {
        assertEquals(
            ReconcileAction.Grant("tok-1"),
            reconcile(current = EntitlementSource.NONE, queryOk = true, ownedTokens = listOf("tok-1"), allowRevoke = true),
        )
        assertEquals(
            ReconcileAction.Grant("tok-1"),
            reconcile(current = EntitlementSource.PLAY, queryOk = true, ownedTokens = listOf("tok-1", "tok-2"), allowRevoke = false),
        )
    }

    @Test
    fun `successful empty query revokes a Play-sourced entitlement`() {
        assertEquals(
            ReconcileAction.Revoke,
            reconcile(current = EntitlementSource.PLAY, queryOk = true, ownedTokens = emptyList(), allowRevoke = true),
        )
    }

    @Test
    fun `successful empty query keeps an AppStore-sourced entitlement untouched`() {
        assertEquals(
            ReconcileAction.Keep,
            reconcile(current = EntitlementSource.APPSTORE, queryOk = true, ownedTokens = emptyList(), allowRevoke = true),
        )
    }

    @Test
    fun `successful empty query keeps an already-locked state untouched`() {
        assertEquals(
            ReconcileAction.Keep,
            reconcile(current = EntitlementSource.NONE, queryOk = true, ownedTokens = emptyList(), allowRevoke = true),
        )
    }

    @Test
    fun `failed query always keeps, even when tokens would otherwise be owned`() {
        assertEquals(
            ReconcileAction.Keep,
            reconcile(current = EntitlementSource.PLAY, queryOk = false, ownedTokens = emptyList(), allowRevoke = true),
        )
        assertEquals(
            ReconcileAction.Keep,
            reconcile(current = EntitlementSource.NONE, queryOk = false, ownedTokens = listOf("tok-1"), allowRevoke = true),
        )
    }

    @Test
    fun `silent revalidation never revokes, even on a successful empty query`() {
        assertEquals(
            ReconcileAction.Keep,
            reconcile(current = EntitlementSource.PLAY, queryOk = true, ownedTokens = emptyList(), allowRevoke = false),
        )
    }

    @Test
    fun `silent revalidation still grants a found purchase`() {
        assertEquals(
            ReconcileAction.Grant("tok-9"),
            reconcile(current = EntitlementSource.NONE, queryOk = true, ownedTokens = listOf("tok-9"), allowRevoke = false),
        )
    }
}
