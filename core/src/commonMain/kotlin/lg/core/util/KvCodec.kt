package lg.core.util

/**
 * Trivial `"k=v;k=v"` serialization for small string maps (settings, save
 * snapshots). Chosen over a real serialization library to keep `commonMain`
 * dependency-free (PLAN.md §2). `=`, `;` and `\` are backslash-escaped inside
 * keys and values so arbitrary text round-trips safely.
 */
public object KvCodec {
    /** Encodes [map] into a single `"k=v;k=v"` string, preserving iteration order. */
    public fun encode(map: Map<String, String>): String {
        val sb = StringBuilder()
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(';')
            first = false
            appendEscaped(sb, k)
            sb.append('=')
            appendEscaped(sb, v)
        }
        return sb.toString()
    }

    /** Decodes a string produced by [encode] back into a map. Empty input yields an empty map. */
    public fun decode(data: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (data.isEmpty()) return result
        val key = StringBuilder()
        val value = StringBuilder()
        var inValue = false
        var i = 0
        val n = data.length
        while (i < n) {
            val c = data[i]
            if (c == '\\' && i + 1 < n) {
                val escaped = data[i + 1]
                if (inValue) value.append(escaped) else key.append(escaped)
                i += 2
                continue
            }
            when (c) {
                '=' -> {
                    inValue = true
                    i++
                }
                ';' -> {
                    result[key.toString()] = value.toString()
                    key.clear()
                    value.clear()
                    inValue = false
                    i++
                }
                else -> {
                    if (inValue) value.append(c) else key.append(c)
                    i++
                }
            }
        }
        if (key.isNotEmpty() || inValue) {
            result[key.toString()] = value.toString()
        }
        return result
    }

    private fun appendEscaped(sb: StringBuilder, s: String) {
        for (c in s) {
            if (c == '\\' || c == '=' || c == ';') sb.append('\\')
            sb.append(c)
        }
    }
}
