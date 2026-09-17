package lg.core

/**
 * Tiny process-wide configuration set by the platform layer at startup.
 *
 * Kept as a single mutable object (rather than threaded through every
 * `Game`/`GameSession` constructor) because it only ever affects word-list
 * selection in `HangmanGame` and nothing else reads it during a running game;
 * see docs/ARCHITECTURE.md.
 */
public object CoreConfig {
    /**
     * BCP-47-ish language tag of the device/app locale (e.g. `"en"`,
     * `"fr-FR"`). Defaults to `"en"`. The platform sets this once at launch,
     * before any `GameSession` is created. `HangmanGame` picks the French
     * word list when this starts with `"fr"` (case-insensitive), the English
     * one otherwise.
     */
    public var languageTag: String = "en"
}
