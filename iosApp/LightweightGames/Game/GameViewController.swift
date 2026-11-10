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

        // A new high score: ask for three initials (a text field, upper case), then hand them back to the session.
        session.onEnterInitials = { [weak self] _, _ in
            self?.askInitials()
            return KotlinUnit()
        }
    }

    // MARK: - High-score initials

    /// Letters in a name; same as `Scores.NAME_LENGTH` in the core.
    private static let initialsLength = 3

    private func askInitials() {
        let alert = UIAlertController(title: Strings.string(for: .enterInitials), message: nil, preferredStyle: .alert)
        alert.addTextField { [weak self] field in
            field.text = self?.services.scores.lastInitials()
            field.autocapitalizationType = .allCharacters
            field.autocorrectionType = .no
            field.spellCheckingType = .no
            field.textAlignment = .center
            field.font = .systemFont(ofSize: 28, weight: .semibold)
            field.returnKeyType = .done
            field.delegate = self
        }
        alert.addAction(UIAlertAction(title: Strings.ok, style: .default) { [weak self, weak alert] _ in
            self?.finishInitials(alert?.textFields?.first?.text)
        })
        present(alert, animated: true)
    }

    private func finishInitials(_ name: String?) {
        session.submitInitials(name: name)
        gameView.setNeedsDisplay()
    }

    /// Leaving while the initials are asked: keep the score under the default initials.
    private func giveUpInitialsIfAsked() {
        guard session.isAwaitingInitials else { return }
        (presentedViewController as? UIAlertController)?.dismiss(animated: false)
        session.submitInitials(name: nil)
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        giveUpInitialsIfAsked()
        session.pause()
        session.persist(store: services.kvStore)
    }

    @objc private func willResignActive() {
        giveUpInitialsIfAsked()
        session.pause()
        session.persist(store: services.kvStore)
    }

    @objc private func closeTapped() {
        session.pause()
        session.persist(store: services.kvStore)
        dismiss(animated: true)
    }
}

extension GameViewController: UITextFieldDelegate {
    /// Upper-cases what is typed and caps the field at three characters.
    func textField(
        _ textField: UITextField,
        shouldChangeCharactersIn range: NSRange,
        replacementString string: String
    ) -> Bool {
        let current = (textField.text ?? "") as NSString
        let updated = current.replacingCharacters(in: range, with: string.uppercased())
        return updated.count <= GameViewController.initialsLength
    }
}
