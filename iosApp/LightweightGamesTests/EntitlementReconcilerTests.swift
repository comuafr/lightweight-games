import XCTest
@testable import LightweightGames
import GamesCore

/// Unit tests for the pure decision logic in `Store/EntitlementReconciler.swift`
/// (PLAN.md §6/§8). Deliberately has no dependency on `StoreKitTest` /
/// `SKTestSession` -- these are plain, fast, synchronous assertions over an
/// `EntitlementSource` + two `Bool`s.
final class EntitlementReconcilerTests: XCTestCase {
    func testOwnedProductAlwaysGrantsRegardlessOfCurrentSource() {
        XCTAssertEqual(
            EntitlementReconciler.reconcile(current: EntitlementSource.none, syncSucceeded: true, ownsProduct: true),
            .grant
        )
        XCTAssertEqual(
            EntitlementReconciler.reconcile(current: .play, syncSucceeded: true, ownsProduct: true),
            .grant
        )
        XCTAssertEqual(
            EntitlementReconciler.reconcile(current: .appstore, syncSucceeded: true, ownsProduct: true),
            .grant
        )
    }

    func testSyncSucceededNotOwnedAppStoreRevokes() {
        XCTAssertEqual(
            EntitlementReconciler.reconcile(current: .appstore, syncSucceeded: true, ownsProduct: false),
            .revoke
        )
    }

    func testSyncSucceededNotOwnedPlayKeeps() {
        // This store has no say over a Play-sourced entitlement.
        XCTAssertEqual(
            EntitlementReconciler.reconcile(current: .play, syncSucceeded: true, ownsProduct: false),
            .keep
        )
    }

    func testSyncSucceededNotOwnedNoneKeeps() {
        XCTAssertEqual(
            EntitlementReconciler.reconcile(current: EntitlementSource.none, syncSucceeded: true, ownsProduct: false),
            .keep
        )
    }

    func testSyncFailedAlwaysKeeps() {
        // Store unreachable: never touch the local cache, regardless of
        // what the (unknowable, since sync failed) ownership would be.
        XCTAssertEqual(
            EntitlementReconciler.reconcile(current: .appstore, syncSucceeded: false, ownsProduct: false),
            .keep
        )
        XCTAssertEqual(
            EntitlementReconciler.reconcile(current: .appstore, syncSucceeded: false, ownsProduct: true),
            .grant // ownsProduct still wins: a locally-verified transaction found before the sync failed is still real.
        )
    }
}
