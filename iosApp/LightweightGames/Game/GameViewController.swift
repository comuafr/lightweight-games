import UIKit
import GamesCore

/// Hosts one `GameSession` full-screen: the `GameView` canvas, orientation lock derived from
/// `GameMeta.orientation`, and the background/foreground pause+persist
/// lifecycle (PLAN.md §5). Presented modally, full screen, from
/// `CatalogViewController`.
final class GameViewController: UIViewController {
    private let meta: GameMeta
    private let services: Services
    private let session: GameSession
    private lazy var gameView = GameView(session: session, soundSink: services.soundSink)

    init(meta: GameMeta, services: Services) {
        self.meta = meta
        self.services = services
        self.session = GameSession(meta: meta, scores: services.scores, settings: services.settings, clock: services.clock)
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - Orientation / system gestures

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        switch meta.orientation {
        case .portrait:
            return .portrait
        case .landscape:
            return .landscape
        default:
            // Orientation.any, or any future case this build doesn't know
            // about yet (Kotlin enums aren't exhaustively checkable from
            // Swift, so `default` covers both).
            return .allButUpsideDown
        }
    }

    override var prefersHomeIndicatorAutoHidden: Bool { true }

    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge {
        // Two-player games place a touch zone at a screen edge (PLAN.md §5);
        // defer the system gesture there so it doesn't hijack input.
        meta.players.last >= 2 ? .all : []
    }

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ScenePalette.colors[PaletteIndex.bg]

        gameView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(gameView)

        NSLayoutConstraint.activate([
            gameView.topAnchor.constraint(equalTo: view.topAnchor),
            gameView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            gameView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            gameView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

        ])

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(willResignActive),
            name: UIApplication.willResignActiveNotification,
            object: nil
        )

        // Resumable games (e.g. 2048 from M1 onward) pick up where the
        // player left off; Snake (M0) is not resumable, so this is a no-op
        // (GameSession.tryRestore checks GameMeta.resumable itself).
        _ = session.tryRestore(store: services.kvStore)

        // The permanent Quit button drawn by the session at the bottom of the game.
        session.onQuit = { [weak self] in
            self?.closeTapped()
            return KotlinUnit()
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        session.pause()
        session.persist(store: services.kvStore)
    }

    @objc private func willResignActive() {
        session.pause()
        session.persist(store: services.kvStore)
    }

    @objc private func closeTapped() {
        session.pause()
        session.persist(store: services.kvStore)
        dismiss(animated: true)
    }
}
