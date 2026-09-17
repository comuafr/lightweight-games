import Foundation
import StoreKit
import GamesCore

/// StoreKit 2 façade for the single non-consumable "unlock_all" purchase
/// (PLAN.md §6). Owns no UI; `Store/PaywallViewController.swift` and
/// `Settings/SettingsViewController.swift` call into this and update
/// themselves from the result. Every entry point is safe to call fully
/// offline: `loadProduct()` returns `nil`, `purchase`/`restore` return
/// `.failed`/`.failed`, and the local `Entitlement` (the source of truth
/// for whether the catalog is playable) is never touched on failure.
///
/// Marked `@MainActor` (StoreKit 2's async APIs don't require a particular
/// actor, but `Entitlement` is a plain, non-`Sendable` Kotlin/Native object
/// and every call site here is UIKit code that already runs on the main
/// thread, so pinning this to the main actor keeps all `Entitlement`
/// mutation single-threaded without extra locking).
@MainActor
final class StoreManager {
    /// Mirrors `lg.core.store.Entitlement.PRODUCT_ID` (Kotlin
    /// `Entitlement.companion.PRODUCT_ID`). Hardcoded rather than read from
    /// the companion object: it is a plain compile-time constant that never
    /// changes at runtime, and this removes any doubt about how Kotlin
    /// `object` constants round-trip through the Objective-C bridge (same
    /// reasoning as `CoreConstants.swift`).
    static let productID = "unlock_all"

    /// Mirrors `lg.core.store.Entitlement.REVALIDATE_INTERVAL_MS` (7 days
    /// in milliseconds), for the same reason as `productID` above.
    static let revalidateIntervalMs: Int64 = 7 * 24 * 60 * 60 * 1000

    enum PurchaseOutcome: Equatable {
        /// A verified transaction was found; the local entitlement is
        /// already granted and marked verified by the time this is
        /// returned.
        case unlocked
        /// Awaiting approval (e.g. Ask to Buy). No entitlement change yet;
        /// `Transaction.updates` (see `startTransactionListener`) will
        /// grant it later if/when it clears.
        case pending
        /// The person dismissed the purchase sheet. Not an error: never
        /// nag, never retry automatically.
        case cancelled
        /// Either StoreKit threw, or the transaction came back unverified
        /// (Apple's fraud/jailbreak signature check failed) and is treated
        /// as not a real purchase.
        case failed
    }

    enum RestoreOutcome: Equatable {
        /// A verified, non-revoked transaction for `unlock_all` was found;
        /// the local entitlement is granted and marked verified.
        case restored
        /// The sync succeeded but nothing was found for this Apple ID, or
        /// the local entitlement came from a different source (Play) and
        /// this store has no say over it.
        case nothing
        /// `AppStore.sync()` itself threw (offline, etc.); the local
        /// entitlement is left exactly as it was.
        case failed
    }

    private let entitlement: Entitlement
    private var cachedProduct: Product?
    private var transactionUpdatesTask: Task<Void, Never>?

    init(entitlement: Entitlement) {
        self.entitlement = entitlement
    }

    deinit {
        transactionUpdatesTask?.cancel()
    }

    // MARK: - Product

    /// Fetches (and caches) the `unlock_all` `Product` from the App Store.
    /// Returns `nil` on any failure (no network, product not found, etc.)
    /// so the paywall can show its offline notice and disable the buy
    /// button -- never blocks, never throws.
    func loadProduct() async -> Product? {
        if let cachedProduct {
            return cachedProduct
        }
        do {
            let products = try await Product.products(for: [StoreManager.productID])
            let product = products.first { $0.id == StoreManager.productID }
            cachedProduct = product
            return product
        } catch {
            return nil
        }
    }

    // MARK: - Purchase

    func purchase(_ product: Product) async -> PurchaseOutcome {
        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                switch verification {
                case .verified(let transaction):
                    entitlement.grant(source: .appstore, token: String(transaction.id))
                    entitlement.markVerified()
                    await transaction.finish()
                    return .unlocked
                case .unverified:
                    // Apple's local signature check failed: don't grant.
                    return .failed
                }
            case .pending:
                return .pending
            case .userCancelled:
                return .cancelled
            @unknown default:
                return .failed
            }
        } catch {
            return .failed
        }
    }

    // MARK: - Restore

    /// `AppStore.sync()` (a full re-fetch of this Apple ID's purchase
    /// history from the store, prompting for sign-in if needed) followed by
    /// reconciling the local `Entitlement` against
    /// `Transaction.currentEntitlements` via `EntitlementReconciler`.
    func restore() async -> RestoreOutcome {
        do {
            try await AppStore.sync()
        } catch {
            return .failed
        }

        let owned = await verifiedUnlockAllTransaction()
        let action = EntitlementReconciler.reconcile(
            current: entitlement.source(),
            syncSucceeded: true,
            ownsProduct: owned != nil
        )

        switch action {
        case .grant:
            if let owned {
                entitlement.grant(source: .appstore, token: String(owned.id))
                entitlement.markVerified()
            }
            return .restored
        case .revoke:
            entitlement.revoke()
            return .nothing
        case .keep:
            return .nothing
        }
    }

    // MARK: - Launch-time local check

    /// Local-only (no network) check against `Transaction.currentEntitlements`,
    /// meant to be called once at launch. Grants if a verified, non-revoked
    /// `unlock_all` transaction is cached locally by StoreKit; **never**
    /// revokes on absence -- the local cache can legitimately be empty
    /// while fully offline (PLAN.md §6).
    func checkCurrentEntitlementsAtLaunch() async {
        if let owned = await verifiedUnlockAllTransaction() {
            entitlement.grant(source: .appstore, token: String(owned.id))
            entitlement.markVerified()
        }
    }

    /// Opportunistic, silent revalidation: only runs (and only touches the
    /// store) when `Entitlement.shouldRevalidate` says the last store
    /// confirmation is older than `REVALIDATE_INTERVAL_MS` (7 days). Reuses
    /// the local, no-network check -- this deliberately does *not* call
    /// `AppStore.sync()` (that would be a network call at catalog-open
    /// time, which PLAN.md §6 explicitly forbids). Never blocks and never
    /// revokes; all failures are swallowed.
    func silentRevalidateIfNeeded() async {
        guard entitlement.shouldRevalidate(intervalMs: StoreManager.revalidateIntervalMs) else {
            return
        }
        if let owned = await verifiedUnlockAllTransaction() {
            entitlement.grant(source: .appstore, token: String(owned.id))
            entitlement.markVerified()
        }
        // Else: nothing found locally right now (could just be offline);
        // leave `lastVerifiedMs` untouched so `shouldRevalidate` keeps
        // trying next time the catalog opens, per PLAN.md §6 -- never
        // revoke, and don't claim a confirmation that didn't happen.
    }

    // MARK: - Transaction listener

    /// Starts (or restarts) a detached listener over `Transaction.updates`
    /// for transactions that change after launch: deferred purchases
    /// clearing (Ask to Buy approved), and revocations (refunds). Call once
    /// from `AppDelegate`; the task is cancelled in `deinit`.
    func startTransactionListener() {
        transactionUpdatesTask?.cancel()
        transactionUpdatesTask = Task.detached { [weak self] in
            for await update in Transaction.updates {
                await self?.handleTransactionUpdate(update)
            }
        }
    }

    private func handleTransactionUpdate(_ result: VerificationResult<Transaction>) async {
        guard case .verified(let transaction) = result else {
            // Unverified update: Apple's local signature check failed, so
            // there is nothing to grant or revoke from it.
            return
        }
        guard transaction.productID == StoreManager.productID else {
            await transaction.finish()
            return
        }
        if transaction.revocationDate != nil {
            entitlement.revoke()
        } else {
            entitlement.grant(source: .appstore, token: String(transaction.id))
            entitlement.markVerified()
        }
        await transaction.finish()
    }

    // MARK: - Helpers

    /// Scans `Transaction.currentEntitlements` (StoreKit's local cache,
    /// refreshed by `AppStore.sync()` when called first) for a verified,
    /// not-yet-revoked transaction for `unlock_all`.
    private func verifiedUnlockAllTransaction() async -> Transaction? {
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result else { continue }
            guard transaction.productID == StoreManager.productID else { continue }
            guard transaction.revocationDate == nil else { continue }
            return transaction
        }
        return nil
    }
}
