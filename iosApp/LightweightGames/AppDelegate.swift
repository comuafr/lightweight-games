import UIKit
import GamesCore

/// Classic, window-based app entry point (no `UIScene`/`SceneDelegate`, per
/// PLAN.md's M0 iOS scope): builds the platform services once and pushes
/// the catalogue as the root of a `UINavigationController`.
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    private let services = Services()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        // Tell the core which language to use for language-dependent content
        // (currently only HangmanGame's word list, see CoreConfig.kt) before
        // any GameSession/Game is created. Refreshed on every subsequent
        // locale change (Settings app language switch while running, or a
        // region change) via NSLocale.currentLocaleDidChangeNotification.
        Self.refreshCoreLanguage()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(Self.localeDidChange),
            name: NSLocale.currentLocaleDidChangeNotification,
            object: nil
        )

        let window = UIWindow(frame: UIScreen.main.bounds)
        let catalog = CatalogViewController(services: services)
        let navigationController = UINavigationController(rootViewController: catalog)
        window.rootViewController = navigationController
        window.backgroundColor = ScenePalette.colors[PaletteIndex.bg]
        window.makeKeyAndVisible()
        self.window = window

        // Cheap, local-only (PLAN.md §6): listen for transaction updates
        // (Ask to Buy clearing, refunds) for the process lifetime, and
        // check StoreKit's on-device transaction cache once at launch.
        // Neither does network I/O, and the launch check never revokes on
        // an empty/offline cache.
        services.store.startTransactionListener()
        Task { await services.store.checkCurrentEntitlementsAtLaunch() }

        return true
    }

    /// Sets `lg.core.CoreConfig.shared.languageTag` from the device's
    /// preferred language (e.g. `"en"`, `"fr-FR"`); `HangmanGame` picks its
    /// French word list when this starts with `"fr"` (case-insensitive),
    /// English otherwise. `Locale.preferredLanguages` is never empty in
    /// practice, but the `"en"` fallback keeps this total.
    private static func refreshCoreLanguage() {
        CoreConfig.shared.languageTag = Locale.preferredLanguages.first ?? "en"
    }

    @objc private func localeDidChange() {
        Self.refreshCoreLanguage()
    }
}
