package lg.core.store

import lg.core.util.Clock
import lg.core.util.Rng

/** Where the "unlock all" entitlement was granted from. */
public enum class EntitlementSource {
    NONE,
    PLAY,
    APPSTORE,
}

/**
 * Local cache of the single non-consumable purchase (`unlock_all`).
 *
 * Rules (PLAN.md §6): the local state is the source of truth for playing; the
 * store only confirms it or revokes it on proof (refund). Nothing here does
 * network. The value is stored obfuscated under [KEY_ENTITLEMENT] with a
 * per-install key so hand-editing the preferences does not unlock the app.
 *
 * Payload layout (before obfuscation): `v1|<source ordinal>|<grantedMs>|<token crc>|<crc32 of the preceding fields>`.
 */
public class Entitlement(
    private val store: KeyValueStore,
    private val clock: Clock,
) {
    /** True when the full catalog is unlocked. Cheap: reads and validates the cached blob. */
    public fun isUnlocked(): Boolean = read() != null

    /** Source of the current entitlement, or NONE. */
    public fun source(): EntitlementSource = read()?.source ?: EntitlementSource.NONE

    /** Time at which the entitlement was granted, or 0. */
    public fun grantedMs(): Long = read()?.grantedMs ?: 0L

    /**
     * Records a verified purchase. [token] is the store's purchase token or
     * transaction id; only its CRC is kept, never the token itself.
     */
    public fun grant(source: EntitlementSource, token: String) {
        require(source != EntitlementSource.NONE) { "grant requires a real source" }
        val now = clock.nowMs()
        val body = "v1|${source.ordinal}|$now|${Obfuscator.crc32(token)}"
        val payload = "$body|${Obfuscator.crc32(body)}"
        store.putString(KEY_ENTITLEMENT, Obfuscator.encode(payload, installKey()))
        markVerified()
    }

    /** Removes the entitlement (refund or store says it is no longer owned). */
    public fun revoke() {
        store.remove(KEY_ENTITLEMENT)
        store.remove(KEY_LAST_VERIFIED)
    }

    /** Last time the store confirmed the entitlement, or 0. */
    public fun lastVerifiedMs(): Long = store.getString(KEY_LAST_VERIFIED, null)?.toLongOrNull() ?: 0L

    /** Records that the store confirmed the current entitlement now. */
    public fun markVerified() {
        store.putString(KEY_LAST_VERIFIED, clock.nowMs().toString())
    }

    /**
     * True when an unlocked entitlement has not been confirmed by the store for
     * more than [intervalMs] (default 7 days). Used for the opportunistic,
     * silent revalidation at catalog open; never blocks play.
     */
    public fun shouldRevalidate(intervalMs: Long = REVALIDATE_INTERVAL_MS): Boolean {
        if (!isUnlocked()) return false
        return clock.nowMs() - lastVerifiedMs() >= intervalMs
    }

    private class Parsed(val source: EntitlementSource, val grantedMs: Long)

    private fun read(): Parsed? {
        val encoded = store.getString(KEY_ENTITLEMENT, null) ?: return null
        val payload = Obfuscator.decode(encoded, installKey()) ?: return null
        val parts = payload.split('|')
        if (parts.size != 5 || parts[0] != "v1") return null
        val body = parts.subList(0, 4).joinToString("|")
        if (parts[4].toLongOrNull() != Obfuscator.crc32(body)) return null
        val source = parts[1].toIntOrNull()?.let { EntitlementSource.entries.getOrNull(it) } ?: return null
        if (source == EntitlementSource.NONE) return null
        val grantedMs = parts[2].toLongOrNull() ?: return null
        return Parsed(source, grantedMs)
    }

    /** Per-install random key, created once and kept in the same store. */
    private fun installKey(): Long {
        val existing = store.getString(KEY_INSTALL_ID, null)?.toLongOrNull()
        if (existing != null) return existing
        val fresh = Rng(clock.nowMs() xor (store.hashCode().toLong() shl 32)).nextLong()
        store.putString(KEY_INSTALL_ID, fresh.toString())
        return fresh
    }

    public companion object {
        public const val PRODUCT_ID: String = "unlock_all"
        public const val KEY_ENTITLEMENT: String = "ent.v1"
        public const val KEY_LAST_VERIFIED: String = "ent.verified"
        public const val KEY_INSTALL_ID: String = "install.id"
        public const val REVALIDATE_INTERVAL_MS: Long = 7L * 24 * 60 * 60 * 1000
    }
}
