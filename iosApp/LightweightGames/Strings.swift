import Foundation
import GamesCore

/// Maps `lg.core.scene.StringKey` ordinals to localized strings, and holds
/// the handful of extra strings the iOS shell itself needs that have no
/// equivalent in the core (`lg.core` never holds translations; PLAN.md §4).
enum Strings {
    /// Localization keys (and `.xcstrings` keys), in `StringKey.ordinal`
    /// order. This order MUST match the declaration order in
    /// `core/src/commonMain/kotlin/lg/core/scene/StringKey.kt` exactly —
    /// index `i` here corresponds to the Kotlin enum case with ordinal `i`.
    /// Each entry is the lower_snake_case spelling of the Kotlin case name.
    private static let keysByOrdinal: [String] = [
        "title_snake", // 0  TITLE_SNAKE
        "title_2048", // 1  TITLE_2048
        "title_breakout", // 2  TITLE_BREAKOUT
        "score", // 3  SCORE
        "best", // 4  BEST
        "game_over", // 5  GAME_OVER
        "paused", // 6  PAUSED
        "tap_to_start", // 7  TAP_TO_START
        "resume", // 8  RESUME
        "restart", // 9  RESTART
        "one_player", // 10 ONE_PLAYER
        "two_players", // 11 TWO_PLAYERS
        "player_1_wins", // 12 PLAYER_1_WINS
        "player_2_wins", // 13 PLAYER_2_WINS
        "draw", // 14 DRAW
        "locked", // 15 LOCKED
        "offline_notice", // 16 OFFLINE_NOTICE
        "unlock_all", // 17 UNLOCK_ALL
        "restore", // 18 RESTORE
        "settings", // 19 SETTINGS
        "sound", // 20 SOUND
        "haptics", // 21 HAPTICS
        "unlock_title", // 22 UNLOCK_TITLE
        "unlock_body", // 23 UNLOCK_BODY
        "purchase_pending", // 24 PURCHASE_PENDING
        "purchase_failed", // 25 PURCHASE_FAILED
        "restored", // 26 RESTORED
        "nothing_to_restore", // 27 NOTHING_TO_RESTORE
        "parental_gate", // 28 PARENTAL_GATE

        // M3: complexity-S unlocked games, plus Four in a Row (M).
        "title_sequence", // 29 TITLE_SEQUENCE
        "title_pairs", // 30 TITLE_PAIRS
        "title_sliding", // 31 TITLE_SLIDING
        "title_tictactoe", // 32 TITLE_TICTACTOE
        "title_paddles", // 33 TITLE_PADDLES
        "title_fourinrow", // 34 TITLE_FOURINROW

        // M4: complexity-M/C unlocked games.
        "title_bird", // 35 TITLE_BIRD
        "title_mines", // 36 TITLE_MINES
        "title_hangman", // 37 TITLE_HANGMAN
        "title_reversi", // 38 TITLE_REVERSI
        "title_sudoku", // 39 TITLE_SUDOKU
        "title_nonogram", // 40 TITLE_NONOGRAM
        "easy", // 41 EASY
        "medium", // 42 MEDIUM
        "hard", // 43 HARD
        "quit", // 44 QUIT
        "help", // 45 HELP
        "level", // 46 LEVEL
        "high_scores", // 47 HIGH_SCORES
        "grand_champion", // 48 GRAND_CHAMPION
        "new_high_score", // 49 NEW_HIGH_SCORE
        "enter_initials", // 50 ENTER_INITIALS
    ]

    /// Resolved once at process startup (PLAN.md §5: `Strings.swift` "maps
    /// `StringKey` ordinal -> localized string ... cached in an array").
    private static let localizedByOrdinal: [String] = keysByOrdinal.map {
        NSLocalizedString($0, comment: "")
    }

    /// Looks up the localized string for a raw `StringKey` ordinal, exactly
    /// as it arrives in `Scene.key` for a `TEXT` primitive.
    static func string(forOrdinal ordinal: Int32) -> String {
        let index = Int(ordinal)
        guard localizedByOrdinal.indices.contains(index) else { return "?" }
        return localizedByOrdinal[index]
    }

    /// Convenience overload for call sites that already hold a `StringKey`
    /// (e.g. `GameMeta.titleKey`) rather than a raw scene ordinal.
    static func string(for key: StringKey) -> String {
        string(forOrdinal: key.ordinal)
    }

    // Extra, iOS-shell-only strings with no lg.core.scene.StringKey
    // equivalent (the core never draws these).
    static let appName = NSLocalizedString("app_name", comment: "")
    static let close = NSLocalizedString("close", comment: "")
    static let version = NSLocalizedString("version", comment: "")
    static let ok = NSLocalizedString("ok", comment: "")
    static let cancel = NSLocalizedString("cancel", comment: "")
    static let resetScores = NSLocalizedString("reset_scores", comment: "")
    static let resetScoresConfirm = NSLocalizedString("reset_scores_confirm", comment: "")
    static let resetScoresErase = NSLocalizedString("reset_scores_erase", comment: "")
    static let resetScoresDone = NSLocalizedString("reset_scores_done", comment: "")

    /// Number of `StringKey` ordinals this table covers -- kept in sync with
    /// `keysByOrdinal.count` (and, transitively, with `StringKey.kt`; see
    /// `iosApp/tools/check_string_keys.py`). Exposed so tests can iterate
    /// every ordinal without hardcoding the count.
    static var ordinalCount: Int { keysByOrdinal.count }

    /// Test-only escape hatch onto the raw `.xcstrings` key for one ordinal,
    /// so `SmokeTests` can assert a localized value differs from its own key
    /// (the value `NSLocalizedString` falls back to when a translation is
    /// missing), which `string(forOrdinal:)` alone can't distinguish.
    static func rawKey(forOrdinal ordinal: Int32) -> String? {
        let index = Int(ordinal)
        guard keysByOrdinal.indices.contains(index) else { return nil }
        return keysByOrdinal[index]
    }
}
