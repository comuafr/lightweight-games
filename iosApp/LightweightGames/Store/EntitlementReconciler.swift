import GamesCore

/// What `StoreManager.restore()` (and, in principle, any future
/// store-confirmation path) should do to the locally cached
/// `lg.core.store.Entitlement` after checking with the store.
enum ReconcileAction: Equatable {
    case grant
    case revoke
    case keep
}

/// Pure decision logic for reconciling the locally cached entitlement
/// against what `Transaction.currentEntitlements` says after an
/// `AppStore.sync()` (PLAN.md §6's "golden rule": local state is the source
/// of truth for playing; the store only confirms or revokes on proof).
/// Kept free of StoreKit types entirely so it is trivially unit-testable
/// without `StoreKitTest` / `SKTestSession`.
///
/// Note on `EntitlementSource`: it is a Kotlin enum exported as a Swift
/// enum with cases `.none`, `.play`, `.appstore`. `.none` can be ambiguous
/// with `Optional<EntitlementSource>.none` when the compiler's expected
/// type is `EntitlementSource?` rather than `EntitlementSource` -- every
/// call site here uses the non-optional `EntitlementSource`, so plain
/// `.appstore` / `.play` / `.none` dot-syntax resolves unambiguously, but
/// prefer the fully qualified `EntitlementSource.none` if this logic is
/// ever reused in an optional context.
enum EntitlementReconciler {
    /// - Parameters:
    ///   - current: `entitlement.source()` before reconciliation.
    ///   - syncSucceeded: whether `AppStore.sync()` completed without
    ///     throwing. When `false`, the network/store state is unknown, so
    ///     the local cache must never be touched.
    ///   - ownsProduct: whether a verified, non-revoked transaction for
    ///     `unlock_all` was found in `Transaction.currentEntitlements`
    ///     after the sync.
    static func reconcile(
        current: EntitlementSource,
        syncSucceeded: Bool,
        ownsProduct: Bool
    ) -> ReconcileAction {
        if ownsProduct {
            // The store just confirmed ownership, regardless of what the
            // local cache said (covers first restore on a fresh install,
            // and Play -> App Store account moves).
            return .grant
        }
        guard syncSucceeded else {
            // Store unreachable: never revoke on absence (PLAN.md §6).
            return .keep
        }
        guard current == EntitlementSource.appstore else {
            // Not owned per the store, but the local grant (if any) came
            // from Play or doesn't exist -- not this store's call to make.
            return .keep
        }
        // Sync succeeded, nothing owned, and the local grant was ours to
        // confirm: the store is telling us this was refunded/revoked.
        return .revoke
    }
}
