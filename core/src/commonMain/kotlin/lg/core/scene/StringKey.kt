package lg.core.scene

/**
 * Every translatable string used by the core. The core never holds actual
 * translations; each platform maps `StringKey.ordinal` to a localized string
 * resource (see PLAN.md §5).
 */
public enum class StringKey {
    TITLE_SNAKE,
    TITLE_2048,
    TITLE_BREAKOUT,
    SCORE,
    BEST,
    GAME_OVER,
    PAUSED,
    TAP_TO_START,
    RESUME,
    RESTART,
    ONE_PLAYER,
    TWO_PLAYERS,
    PLAYER_1_WINS,
    PLAYER_2_WINS,
    DRAW,
    LOCKED,
    OFFLINE_NOTICE,
    UNLOCK_ALL,
    RESTORE,
    SETTINGS,
    SOUND,
    HAPTICS,
    UNLOCK_TITLE,
    UNLOCK_BODY,
    PURCHASE_PENDING,
    PURCHASE_FAILED,
    RESTORED,
    NOTHING_TO_RESTORE,
    PARENTAL_GATE,

    // M3: complexity-S unlocked games, plus Four in a Row (M).
    TITLE_SEQUENCE,
    TITLE_PAIRS,
    TITLE_SLIDING,
    TITLE_TICTACTOE,
    TITLE_PADDLES,
    TITLE_FOURINROW,

    // M4: complexity-M/C unlocked games.
    TITLE_BIRD,
    TITLE_MINES,
    TITLE_HANGMAN,
    TITLE_REVERSI,
    TITLE_SUDOKU,
    TITLE_NONOGRAM,
    EASY,
    MEDIUM,
    HARD,
    QUIT,
}
