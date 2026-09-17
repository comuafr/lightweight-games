package lg.core.store

import lg.core.testutil.FakeClock
import lg.core.testutil.FakeStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntitlementTest {
    private val store = FakeStore()
    private val clock = FakeClock(1_000_000L)
    private val entitlement = Entitlement(store, clock)

    @Test
    fun lockedByDefault() {
        assertFalse(entitlement.isUnlocked())
        assertEquals(EntitlementSource.NONE, entitlement.source())
        assertFalse(entitlement.shouldRevalidate())
    }

    @Test
    fun grantThenReadBackFromAFreshInstance() {
        entitlement.grant(EntitlementSource.PLAY, "token-abc")
        val again = Entitlement(store, clock)
        assertTrue(again.isUnlocked())
        assertEquals(EntitlementSource.PLAY, again.source())
        assertEquals(1_000_000L, again.grantedMs())
        assertEquals(1_000_000L, again.lastVerifiedMs())
    }

    @Test
    fun revokeLocksAgain() {
        entitlement.grant(EntitlementSource.APPSTORE, "tx-1")
        entitlement.revoke()
        assertFalse(entitlement.isUnlocked())
        assertEquals(0L, entitlement.lastVerifiedMs())
    }

    @Test
    fun storedBlobIsObfuscatedAndTokenIsNotStored() {
        entitlement.grant(EntitlementSource.PLAY, "secret-token")
        val blob = store.getString(Entitlement.KEY_ENTITLEMENT, null)!!
        assertFalse(blob.contains("v1"))
        assertFalse(blob.contains("secret-token"))
        assertTrue(blob.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun corruptedBlobIsLocked() {
        entitlement.grant(EntitlementSource.PLAY, "t")
        val blob = store.getString(Entitlement.KEY_ENTITLEMENT, null)!!
        store.putString(Entitlement.KEY_ENTITLEMENT, blob.dropLast(2) + "00")
        assertFalse(Entitlement(store, clock).isUnlocked())
        store.putString(Entitlement.KEY_ENTITLEMENT, "zz")
        assertFalse(Entitlement(store, clock).isUnlocked())
        store.putString(Entitlement.KEY_ENTITLEMENT, "")
        assertFalse(Entitlement(store, clock).isUnlocked())
    }

    @Test
    fun blobCopiedToAnotherInstallDoesNotUnlock() {
        entitlement.grant(EntitlementSource.PLAY, "t")
        val blob = store.getString(Entitlement.KEY_ENTITLEMENT, null)!!
        val otherStore = FakeStore()
        otherStore.putString(Entitlement.KEY_ENTITLEMENT, blob)
        val other = Entitlement(otherStore, FakeClock(5L))
        assertFalse(other.isUnlocked())
    }

    @Test
    fun revalidationAfterSevenDays() {
        entitlement.grant(EntitlementSource.PLAY, "t")
        assertFalse(entitlement.shouldRevalidate())
        clock.advance(Entitlement.REVALIDATE_INTERVAL_MS - 1)
        assertFalse(entitlement.shouldRevalidate())
        clock.advance(1)
        assertTrue(entitlement.shouldRevalidate())
        entitlement.markVerified()
        assertFalse(entitlement.shouldRevalidate())
    }

    @Test
    fun obfuscatorRoundTripAndCrc() {
        val key = 0x1234_5678_9ABC_DEF0L
        val text = "v1|1|42|7|éàü ok"
        val encoded = Obfuscator.encode(text, key)
        assertEquals(text, Obfuscator.decode(encoded, key))
        assertNotEquals(text, Obfuscator.decode(encoded, key + 1))
        assertNull(Obfuscator.decode("abc", key))
        assertNull(Obfuscator.decode("zz", key))
        assertEquals(0xCBF43926L, Obfuscator.crc32("123456789"))
    }
}
