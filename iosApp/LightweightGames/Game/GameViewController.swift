import UIKit
import GamesCore

/// Hosts one `GameSession` full-screen: the `GameView` canvas, a close
/// button (>=44pt touch target), orientation lock derived from
/// `GameMeta.orientation`, and the background/foreground pause+persist
/// lifecycle (PLAN.md §5). Presented modally, full screen, from
/// `CatalogViewController`.
final class GameViewController: UIViewController {
    private let meta: GameMeta
    private let services: Services
    private let session: GameSession
    private lazy var gameView = GameView(session: session, soundSink: services.soundSink)

    private let closeButton: UIButton = {
        let button = UIButton(type: .system)
        button.setImage(UIImage(systemName: "xmark.circle.fill"), for: .normal)
        button.tintColor = .white
        button.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        button.layer.cornerRadius = 22
        button.accessibilityLabel = Strings.close
        button.translatesAutoresizingMaskIntoConstraints = false
        return button
    }()

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
        view.addSubview(closeButton)
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)

        NSLayoutConstraint.activate([
            gameView.topAnchor.constraint(equalTo: view.topAnchor),
            gameView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            gameView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            gameView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            closeButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            closeButton.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 8),
            closeButton.widthAnchor.constraint(equalToConstant: 44),
            closeButton.heightAnchor.constraint(equalToConstant: 44),
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
