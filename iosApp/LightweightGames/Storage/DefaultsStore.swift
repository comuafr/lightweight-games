import Foundation
import GamesCore

/// `UserDefaults`-backed `KeyValueStore` (`lg.core.store.KeyValueStore`),
/// used for everything the core persists: settings, best scores, and
/// resumable-game save data (PLAN.md §4, §5). Declared as an `NSObject`
/// subclass because conforming to a Kotlin-exported protocol from Swift
/// requires it.
///
/// Method names and argument labels below must match the Kotlin interface
/// exactly, since `default` is a Kotlin parameter name exported verbatim as
/// a Swift argument label (the local parameter name `def` is just this
/// implementation's choice, to avoid shadowing the `default` keyword).
final class DefaultsStore: NSObject, KeyValueStore {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func getString(key: String, default def: String?) -> String? {
        defaults.string(forKey: key) ?? def
    }

    func putString(key: String, value: String) {
        defaults.set(value, forKey: key)
    }

    func getInt(key: String, default def: Int32) -> Int32 {
        guard defaults.object(forKey: key) != nil else { return def }
        return Int32(defaults.integer(forKey: key))
    }

    func putInt(key: String, value: Int32) {
        defaults.set(Int(value), forKey: key)
    }

    func getBoolean(key: String, default def: Bool) -> Bool {
        guard defaults.object(forKey: key) != nil else { return def }
        return defaults.bool(forKey: key)
    }

    func putBoolean(key: String, value: Bool) {
        defaults.set(value, forKey: key)
    }

    func remove(key: String) {
        defaults.removeObject(forKey: key)
    }
}
