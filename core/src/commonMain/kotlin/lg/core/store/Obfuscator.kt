package lg.core.store

import lg.core.util.Rng

/**
 * Light obfuscation for values stored in platform preferences (the "unlock all"
 * entitlement). It is NOT cryptography and NOT DRM: the goal is only that editing
 * the preferences file by hand does not trivially unlock the catalog
 * (PLAN.md §4). A keystream derived from a per-install key is XORed with the
 * payload and the result is hex-encoded; a CRC32 inside the payload detects
 * corruption and casual tampering.
 */
public object Obfuscator {
    /** Encodes [payload] under [key] as lowercase hex. */
    public fun encode(payload: String, key: Long): String {
        val bytes = payload.encodeToByteArray()
        val rng = Rng(key)
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val k = (rng.nextLong() and 0xFF).toInt()
            val v = (b.toInt() xor k) and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        return out.toString()
    }

    /** Decodes a value produced by [encode] with the same [key]; null if the hex is malformed. */
    public fun decode(encoded: String, key: Long): String? {
        if (encoded.length % 2 != 0) return null
        val bytes = ByteArray(encoded.length / 2)
        val rng = Rng(key)
        for (i in bytes.indices) {
            val hi = hexValue(encoded[i * 2])
            val lo = hexValue(encoded[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            val k = (rng.nextLong() and 0xFF).toInt()
            bytes[i] = (((hi shl 4) or lo) xor k).toByte()
        }
        return try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** Standard CRC-32 (IEEE), table-free, of the UTF-8 bytes of [text]. */
    public fun crc32(text: String): Long {
        var crc = 0xFFFFFFFFL
        for (b in text.encodeToByteArray()) {
            crc = crc xor (b.toLong() and 0xFF)
            repeat(8) {
                crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1
            }
        }
        return crc xor 0xFFFFFFFFL
    }

    private const val HEX: String = "0123456789abcdef"

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
