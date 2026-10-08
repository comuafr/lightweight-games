package lg.core.store

/** Best-score tracking per game id, persisted via [KeyValueStore] under `"score.<id>"`. */
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

    private fun keyFor(gameId: String): String = "score.$gameId"
}
