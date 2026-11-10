import XCTest
@testable import LightweightGames
import GamesCore

/// End-to-end smoke tests for M0 (PLAN.md §8/§9): a `GameSession` for Snake
/// must run headless for a few seconds without an empty or overflowing
/// scene, and the catalogue must show exactly one tile per registered game.
@MainActor
final class SmokeTests: XCTestCase {
    /// Minimal in-memory `KeyValueStore` so these tests never touch real
    /// `UserDefaults`.
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

    /// Fixed `Clock` so seed/timing-derived behavior stays deterministic.
    private final class FixedClock: NSObject, Clock {
        func nowMs() -> Int64 { 1_700_000_000_000 }
    }

    func testSnakeSessionRunsHeadlessWithoutOverflow() throws {
        let store = InMemoryStore()
        let settings = Settings(store: store)
        let scores = Scores(store: store)
        let clock = FixedClock()

        guard let meta = GameRegistry.shared.byId["snake"] else {
            XCTFail("snake is not registered in GameRegistry.shared.byId")
            return
        }

        let session = GameSession(meta: meta, scores: scores, settings: settings, clock: clock)
        session.resize(width: 390, height: 844)
        session.start(players: 1, seed: 42)

        for _ in 0..<60 {
            _ = session.update(dtMs: 16)
        }

        let scene = Scene(capacity: 1024)
        session.render(scene: scene)

        XCTAssertGreaterThan(scene.count, 0, "render() should have produced at least one primitive")
        XCTAssertEqual(scene.dropped, 0, "1024 primitives should be more than enough for one Snake frame + HUD")
    }

    func testCatalogShowsOneTilePerRegisteredGame() throws {
        let services = Services(store: InMemoryStore(), clock: FixedClock())
        let catalogVC = CatalogViewController(services: services)
        catalogVC.loadViewIfNeeded()

        XCTAssertEqual(catalogVC.games.count, GameRegistry.shared.all.count)
    }

    /// PLAN.md §9 M5: guards against a `StringKey` added to the Kotlin core
    /// (see `core/src/commonMain/kotlin/lg/core/scene/StringKey.kt`) without
    /// a matching, translated entry making it into `Localizable.xcstrings` --
    /// every ordinal must resolve to a non-empty string that differs from its
    /// own raw `.xcstrings` key, since `NSLocalizedString` silently falls
    /// back to returning the key itself when no translation is found.
    /// `iosApp/tools/check_string_keys.py` covers the ordinal-order half of
    /// this same invariant statically (without needing to run the app).
    func testEveryStringKeyOrdinalResolvesToATranslatedString() throws {
        XCTAssertEqual(Strings.ordinalCount, 51, "StringKey.kt now has a different case count; update this test and Strings.swift's keysByOrdinal together")

        for ordinal in 0..<Int32(Strings.ordinalCount) {
            let localized = Strings.string(forOrdinal: ordinal)
            let rawKey = Strings.rawKey(forOrdinal: ordinal)

            XCTAssertFalse(localized.isEmpty, "StringKey ordinal \(ordinal) resolved to an empty string")
            XCTAssertNotEqual(localized, "?", "StringKey ordinal \(ordinal) is out of Strings.swift's keysByOrdinal range")
            if let rawKey {
                XCTAssertNotEqual(
                    localized, rawKey,
                    "StringKey ordinal \(ordinal) (\"\(rawKey)\") has no translated value in Localizable.xcstrings -- NSLocalizedString fell back to the raw key"
                )
            }
        }
    }
}
