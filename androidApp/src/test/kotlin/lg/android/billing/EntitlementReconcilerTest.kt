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
            reconcile(current = EntitlementSource.NONE, queryOk = true, ownedTokens = listOf("tok-1")),
        )
        assertEquals(
            ReconcileAction.Grant("tok-1"),
            reconcile(current = EntitlementSource.PLAY, queryOk = true, ownedTokens = listOf("tok-1", "tok-2")),
        )
    }

    @Test
    fun `successful empty query revokes a Play-sourced entitlement`() {
        assertEquals(
            ReconcileAction.Revoke,
            reconcile(current = EntitlementSource.PLAY, queryOk = true, ownedTokens = emptyList()),
        )
    }

    @Test
    fun `successful empty query keeps an AppStore-sourced entitlement untouched`() {
        assertEquals(
            ReconcileAction.Keep,
            reconcile(current = EntitlementSource.APPSTORE, queryOk = true, ownedTokens = emptyList()),
        )
    }

    @Test
    fun `successful empty query keeps an already-locked state untouched`() {
        assertEquals(
            ReconcileAction.Keep,
            reconcile(current = EntitlementSource.NONE, queryOk = true, ownedTokens = emptyList()),
        )
    }

    @Test
    fun `failed query always keeps, even when tokens would otherwise be owned`() {
        assertEquals(
            ReconcileAction.Keep,
            reconcile(current = EntitlementSource.PLAY, queryOk = false, ownedTokens = emptyList()),
        )
        assertEquals(
            ReconcileAction.Keep,
            reconcile(current = EntitlementSource.NONE, queryOk = false, ownedTokens = listOf("tok-1")),
        )
    }
}
