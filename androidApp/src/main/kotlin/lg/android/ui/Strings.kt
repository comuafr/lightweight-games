package lg.android.ui

import android.content.Context
import lg.android.R
import lg.core.scene.StringKey

/**
 * Maps [StringKey] ordinals to localized strings. The `R.string.*` lookup
 * table ([ORDINAL_TO_RES]) is a plain top-level [IntArray] so a JVM unit test
 * ([lg.android.StringsCoverageTest]) can assert it covers every [StringKey]
 * without needing a [Context]; actual resolution happens once, in the
 * constructor, so [get] never touches resources at draw time.
 */
internal class Strings(context: Context) {
    private val resolved: Array<String> = Array(ORDINAL_TO_RES.size) { i -> context.getString(ORDINAL_TO_RES[i]) }

    /** Returns the cached, localized string for [key]. */
    fun get(key: StringKey): String = resolved[key.ordinal]

    /** Returns the cached, localized string for a raw [StringKey.ordinal]. */
    fun get(ordinal: Int): String = resolved[ordinal]

    companion object {
        // Index == StringKey.ordinal; keep this in the same order as
        // lg.core.scene.StringKey and covered by StringsCoverageTest.
        val ORDINAL_TO_RES: IntArray = intArrayOf(
            R.string.title_snake, // TITLE_SNAKE
            R.string.title_2048, // TITLE_2048
            R.string.title_breakout, // TITLE_BREAKOUT
            R.string.score, // SCORE
            R.string.best, // BEST
            R.string.game_over, // GAME_OVER
            R.string.paused, // PAUSED
            R.string.tap_to_start, // TAP_TO_START
            R.string.resume, // RESUME
            R.string.restart, // RESTART
            R.string.one_player, // ONE_PLAYER
            R.string.two_players, // TWO_PLAYERS
            R.string.player_1_wins, // PLAYER_1_WINS
            R.string.player_2_wins, // PLAYER_2_WINS
            R.string.draw, // DRAW
            R.string.locked, // LOCKED
            R.string.offline_notice, // OFFLINE_NOTICE
            R.string.unlock_all, // UNLOCK_ALL
            R.string.restore, // RESTORE
            R.string.settings, // SETTINGS
            R.string.sound, // SOUND
            R.string.haptics, // HAPTICS
            R.string.unlock_title, // UNLOCK_TITLE
            R.string.unlock_body, // UNLOCK_BODY
            R.string.purchase_pending, // PURCHASE_PENDING
            R.string.purchase_failed, // PURCHASE_FAILED
            R.string.restored, // RESTORED
            R.string.nothing_to_restore, // NOTHING_TO_RESTORE
            R.string.parental_gate, // PARENTAL_GATE

            // M3: complexity-S unlocked games, plus Four in a Row (M).
            R.string.title_sequence, // TITLE_SEQUENCE
            R.string.title_pairs, // TITLE_PAIRS
            R.string.title_sliding, // TITLE_SLIDING
            R.string.title_tictactoe, // TITLE_TICTACTOE
            R.string.title_paddles, // TITLE_PADDLES
            R.string.title_fourinrow, // TITLE_FOURINROW

            // M4: complexity-M/C unlocked games.
            R.string.title_bird, // TITLE_BIRD
            R.string.title_mines, // TITLE_MINES
            R.string.title_hangman, // TITLE_HANGMAN
            R.string.title_reversi, // TITLE_REVERSI
            R.string.title_sudoku, // TITLE_SUDOKU
            R.string.title_nonogram, // TITLE_NONOGRAM
            R.string.easy, // EASY
            R.string.medium, // MEDIUM
            R.string.hard, // HARD
        )
    }
}
