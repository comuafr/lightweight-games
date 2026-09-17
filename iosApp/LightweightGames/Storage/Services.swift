import Foundation
import GamesCore

/// Small dependency container built once in `AppDelegate` and threaded
/// through the view controller hierarchy. Holds everything the core needs
/// from the platform (storage, clock) plus the shared, core-backed models
/// (`Settings`, `Scores`, `Entitlement`) and the StoreKit 2 façade
/// (`StoreManager`) so nothing downstream constructs its own.
final class Services {
    /// `lg.core.store.KeyValueStore` backing settings, best scores, and
    /// resumable-game saves. Named `kvStore` (not `store`) to avoid
    /// colliding with `store: StoreManager` below; the init parameter is
    /// still labeled `store:` since `LightweightGamesTests/SmokeTests.swift`
    /// and `StoreKitTests.swift` construct `Services(store: ..., clock: ...)`.
    let kvStore: KeyValueStore
    let clock: Clock
    let settings: Settings
    let scores: Scores

    /// Local cache of the "unlock_all" non-consumable purchase (PLAN.md §6).
    /// The store (StoreManager) only ever confirms or revokes this; reading
    /// it never touches the network.
    let entitlement: Entitlement

    /// App-wide sound sink, built lazily on first access (`Beeper`'s `init`
    /// only precomputes tone buffers -- cheap, no I/O -- and never touches
    /// the audio session or starts the engine, so this doesn't delay app
    /// launch either way). Kept for the process lifetime; see the lifetime
    /// note on `Beeper` for why.
    lazy var soundSink: SoundSink = Beeper()

    /// StoreKit 2 façade for the "unlock_all" purchase (PLAN.md §6). Built
    /// lazily so nothing touches StoreKit before the first paywall
    /// interaction, `AppDelegate`'s transaction listener start, or the
    /// launch-time local entitlement check -- all of which read this
    /// property to get here in the first place, so "lazy" mostly documents
    /// intent (construction itself is cheap and does no I/O).
    lazy var store: StoreManager = StoreManager(entitlement: entitlement)

    init(store: KeyValueStore = DefaultsStore(), clock: Clock = SystemClock()) {
        self.kvStore = store
        self.clock = clock
        self.settings = Settings(store: store)
        self.scores = Scores(store: store)
        self.entitlement = Entitlement(store: store, clock: clock)
    }

    /// Backed by `lg.core.store.Entitlement`, itself kept in sync by
    /// `StoreManager` (StoreKit 2) for grants/revocations (PLAN.md §6, M2).
    /// The local cache is the source of truth for whether the catalog is
    /// playable; the store only confirms or revokes it on proof.
    var isUnlocked: Bool { entitlement.isUnlocked() }
}
