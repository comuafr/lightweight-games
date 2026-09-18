import XCTest
import StoreKitTest
@testable import LightweightGames
import GamesCore

/// End-to-end StoreKit 2 tests for the "unlock_all" purchase (PLAN.md §6/§8):
/// a purchase against the local `Products.storekit` configuration must grant
/// the entitlement, and a simulated refund/clear must lock it back down via
/// `StoreManager.restore()`. Uses `SKTestSession`, Apple's on-device
/// StoreKit testing harness -- no network, no sandbox Apple ID required.
///
/// `Products.storekit` (`iosApp/Products.storekit`) must be a resource of
/// this test target (see `project.yml`, `LightweightGamesTests.sources`).
///
/// The whole class is `@MainActor` (rather than individual methods) so that
/// `setUpWithError`/`tearDownWithError` (synchronous overrides of
/// non-isolated `XCTestCase` methods) and the `async` test methods can all
/// touch `services.store` (a `@MainActor` `StoreManager`) directly -- a
/// standard pattern for StoreKit 2 + XCTest, per Apple's own sample code.
@MainActor
final class StoreKitTests: XCTestCase {
    /// Minimal in-memory `KeyValueStore`, mirroring `SmokeTests.InMemoryStore`
    /// -- kept as a private duplicate rather than shared, since these two
    /// test files are compiled independently and this keeps each one
    /// self-contained.
    private final class InMemoryStore: NSObject, KeyValueStore {
        private var strings: [String: String] = [:]
        private var ints: [String: Int32] = [:]
        private var bools: [String: Bool] = [:]

        func getString(key: String, default def: String?) -> String? {
            strings[key] ?? def
        }

        func putString(key: String, value: String) {
            strings[key] = value
        }

        func getInt(key: String, default def: Int32) -> Int32 {
            ints[key] ?? def
        }

        func putInt(key: String, value: Int32) {
            ints[key] = value
        }

        func getBoolean(key: String, default def: Bool) -> Bool {
            bools[key] ?? def
        }

        func putBoolean(key: String, value: Bool) {
            bools[key] = value
        }

        func remove(key: String) {
            strings.removeValue(forKey: key)
            ints.removeValue(forKey: key)
            bools.removeValue(forKey: key)
        }
    }

    private final class FixedClock: NSObject, Clock {
        func nowMs() -> Int64 { 1_700_000_000_000 }
    }

    private var session: SKTestSession!
    private var services: Services!

    override func setUpWithError() throws {
        try super.setUpWithError()
        session = try SKTestSession(configurationFileNamed: "Products")
        session.resetToDefaultState()
        session.disableDialogs = true
        session.clearTransactions()
        services = Services(store: InMemoryStore(), clock: FixedClock())
    }

    override func tearDownWithError() throws {
        session.clearTransactions()
        session = nil
        services = nil
        try super.tearDownWithError()
    }

    func testPurchaseUnlocksEntitlement() async throws {
        let product = await services.store.loadProduct()
        let unwrappedProduct = try XCTUnwrap(
            product,
            "unlock_all should be loadable from Products.storekit via SKTestSession"
        )

        let outcome = await services.store.purchase(unwrappedProduct)

        XCTAssertEqual(outcome, .unlocked)
        XCTAssertTrue(services.entitlement.isUnlocked())
        XCTAssertEqual(services.entitlement.source(), EntitlementSource.appstore)
    }

    func testClearingTransactionsThenRestoreLocksEntitlement() async throws {
        let product = await services.store.loadProduct()
        let unwrappedProduct = try XCTUnwrap(product)

        let purchaseOutcome = await services.store.purchase(unwrappedProduct)
        XCTAssertEqual(purchaseOutcome, .unlocked)
        XCTAssertTrue(services.entitlement.isUnlocked())

        // Simulate the purchase no longer existing for this account (e.g. a
        // refund): `SKTestSession.clearTransactions()` wipes the local test
        // transaction log, so `Transaction.currentEntitlements` will come
        // back empty on the next check.
        session.clearTransactions()

        let restoreOutcome = await services.store.restore()

        XCTAssertEqual(restoreOutcome, .nothing)
        XCTAssertFalse(services.entitlement.isUnlocked())
    }
}
