import Foundation
import GamesCore

/// Wall-clock time source for the core (`lg.core.util.Clock`), backed by
/// `Date`. Used by `GameSession` for deterministic-seed defaults and
/// gesture timing (tap/swipe/long-press thresholds).
final class SystemClock: NSObject, Clock {
    func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
