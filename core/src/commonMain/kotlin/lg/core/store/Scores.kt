package lg.core.store

/** One line of a high-score table: a 3-letter [name] and its [score]; [isDefault] marks the built-in scores. */
public class ScoreEntry(public val name: String, public val score: Int, public val isDefault: Boolean)

/**
 * Score tracking per game id, persisted via [KeyValueStore].
 *
 * - `"score.<id>"`: the player's best score (an int; what the HUD shows).
 * - `"hs.<id>"`: the player's real high-score table, `"AAA:1200,BBB:900"` (at most [TABLE_SIZE] entries,
 *   best first). Only real scores are stored.
 * - `"progress.<id>"`: a progress counter such as the furthest level reached.
 *
 * The table shown to the player is the merge of those real entries with the game's built-in default
 * scores ([table]); the defaults are never stored, so they can be retuned in an update and a reset
 * simply brings them back.
 */
public class Scores(private val store: KeyValueStore) {
    /** Returns the best recorded score for [gameId], or 0 if none. */
    public fun best(gameId: String): Int = store.getInt(keyFor(gameId), 0)

    /**
     * Records [score] for [gameId] if it beats the current best.
     * @return true if [score] became the new best.
     */
    public fun submit(gameId: String, score: Int): Boolean {
        val key = keyFor(gameId)
        val current = store.getInt(key, 0)
        if (score > current) {
            store.putInt(key, score)
            return true
        }
        return false
    }

    /** Returns the saved progress counter of [gameId] (e.g. the furthest level reached), or 0 if none. */
    public fun progress(gameId: String): Int = store.getInt("progress.$gameId", 0)

    /** Saves [value] as the progress of [gameId] if it is higher than the one already saved. */
    public fun saveProgress(gameId: String, value: Int) {
        if (value > progress(gameId)) store.putInt("progress.$gameId", value)
    }

    // ---------------------------------------------------------------- high-score table

    /**
     * Turns a best score saved before tables existed (`"score.<id>"`) into a real table entry named `---`,
     * once. Call it before any new score is submitted, otherwise the new best would be mistaken for an old one.
     */
    public fun migrateLegacy(gameId: String) {
        if (store.getString(tableKey(gameId), null) != null) return
        val legacy = best(gameId)
        if (legacy > 0) store.putString(tableKey(gameId), encode(listOf(ScoreEntry(NO_NAME, legacy, false))))
    }

    /**
     * The table to show for [gameId]: the player's real scores merged with the built-in [defaults]
     * (best first, [defaults] in descending order), cut to [TABLE_SIZE] lines. On a tie the older line stays ahead.
     */
    public fun table(gameId: String, defaults: IntArray): List<ScoreEntry> {
        val all = ArrayList<ScoreEntry>(TABLE_SIZE * 2)
        for (i in 0 until minOf(defaults.size, TABLE_SIZE)) {
            all.add(ScoreEntry(DEFAULT_NAMES[i], defaults[i], true))
        }
        all.addAll(realEntries(gameId))
        return all.sortedByDescending { it.score }.take(TABLE_SIZE)
    }

    /** True if [score] would take a place in the table of [gameId]: positive and strictly above its last line. */
    public fun qualifies(gameId: String, defaults: IntArray, score: Int): Boolean {
        if (score <= 0) return false
        val lines = table(gameId, defaults)
        return lines.size < TABLE_SIZE || score > lines.last().score
    }

    /**
     * Inserts [score] under [name] (cleaned by [sanitizeName]) if it [qualifies].
     * @return the rank (0 = grand champion) the score took in the displayed table, or -1 if it did not qualify.
     */
    public fun insert(gameId: String, defaults: IntArray, name: String, score: Int): Int {
        if (!qualifies(gameId, defaults, score)) return -1
        val rank = table(gameId, defaults).count { it.score >= score }
        val real = ArrayList(realEntries(gameId))
        real.add(ScoreEntry(sanitizeName(name), score, false))
        val kept = real.sortedByDescending { it.score }.take(TABLE_SIZE)
        store.putString(tableKey(gameId), encode(kept))
        return rank
    }

    /** The initials last entered by the player, or an empty string if none. */
    public fun lastInitials(): String = store.getString(LAST_KEY, null) ?: ""

    public fun saveLastInitials(name: String) {
        store.putString(LAST_KEY, sanitizeName(name))
    }

    /**
     * Erases the best scores and tables of [gameIds] and the remembered initials, so the tables go back to their
     * defaults. Progress counters (levels reached), purchases, settings and saved games are not touched.
     */
    public fun resetAll(gameIds: List<String>) {
        for (id in gameIds) {
            store.remove(keyFor(id))
            store.remove(tableKey(id))
        }
        store.remove(LAST_KEY)
    }

    private fun realEntries(gameId: String): List<ScoreEntry> {
        val raw = store.getString(tableKey(gameId), null) ?: return emptyList()
        val out = ArrayList<ScoreEntry>(TABLE_SIZE)
        for (part in raw.split(',')) {
            if (out.size >= TABLE_SIZE) break
            val colon = part.indexOf(':')
            if (colon != NAME_LENGTH) continue
            val score = part.substring(colon + 1).toIntOrNull() ?: continue
            if (score <= 0) continue
            out.add(ScoreEntry(sanitizeName(part.substring(0, colon)), score, false))
        }
        return out
    }

    private fun encode(entries: List<ScoreEntry>): String =
        entries.joinToString(",") { it.name + ":" + it.score }

    private fun keyFor(gameId: String): String = "score.$gameId"

    private fun tableKey(gameId: String): String = "hs.$gameId"

    public companion object {
        /** Lines in a table: the grand champion and the four next. */
        public const val TABLE_SIZE: Int = 5

        /** Letters in a name (initials). */
        public const val NAME_LENGTH: Int = 3

        /** Name of a score whose author is unknown (a record from before tables existed, or initials left out). */
        public const val NO_NAME: String = "---"

        /** Names of the built-in default scores, grand champion first. */
        private val DEFAULT_NAMES: Array<String> = arrayOf("COM", "REG", "ZOE", "BOB", "AXL")

        private const val LAST_KEY: String = "hs.last"

        /** Keeps only the letters A-Z (upper-cased), at most [NAME_LENGTH] of them, padded with `-`. */
        public fun sanitizeName(raw: String): String {
            val sb = StringBuilder(NAME_LENGTH)
            for (c in raw) {
                if (sb.length >= NAME_LENGTH) break
                val up = c.uppercaseChar()
                if (up in 'A'..'Z') sb.append(up)
            }
            while (sb.length < NAME_LENGTH) sb.append('-')
            return sb.toString()
        }
    }
}
