import UIKit
import StoreKit
import GamesCore

/// The "unlock_all" paywall sheet (PLAN.md §6): the locked game's icon,
/// title/body copy, the localized price once loaded, a parental-gated
/// "Unlock" button, a "Restore purchases" button, and an offline notice
/// with a disabled buy button when the product couldn't be loaded. Never
/// blocks: the close button and swipe-to-dismiss always work, even fully
/// offline or mid-purchase-attempt.
///
/// Presented as a `.pageSheet` from `CatalogViewController` when a locked
/// tile (or the "Unlock all" banner) is tapped.
@MainActor
final class PaywallViewController: UIViewController {
    private let services: Services
    private let lockedMeta: GameMeta

    /// Called after a purchase or restore actually changes the local
    /// entitlement, so the presenter can refresh immediately (the catalog
    /// also reloads unconditionally in `viewWillAppear`, this is just
    /// belt-and-braces so the tile updates the instant the sheet closes).
    private let onUnlockChanged: (() -> Void)?

    private let scrollView = UIScrollView()
    private let stack = UIStackView()
    private let iconView = IconView()
    private let titleLabel = UILabel()
    private let bodyLabel = UILabel()
    private let priceLabel = UILabel()
    private let offlineLabel = UILabel()
    private let gateHintLabel = UILabel()
    private let unlockButton = ParentalGateButton()
    private let restoreButton = UIButton(type: .system)
    private let closeButton = UIButton(type: .system)
    private let activityIndicator = UIActivityIndicatorView(style: .medium)

    private var product: Product?

    private var isBusy = false {
        didSet { applyBusyState() }
    }

    init(services: Services, lockedMeta: GameMeta, onUnlockChanged: (() -> Void)? = nil) {
        self.services = services
        self.lockedMeta = lockedMeta
        self.onUnlockChanged = onUnlockChanged
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .pageSheet
        // Never trap the user here: swipe-to-dismiss must always work,
        // purchase in progress or not (PLAN.md §6: "aucun blocage").
        isModalInPresentation = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ScenePalette.colors[PaletteIndex.bg]

        if let sheet = sheetPresentationController {
            sheet.detents = [.medium(), .large()]
            sheet.prefersGrabberVisible = true
        }

        setUpViews()
        unlockButton.onUnlocked = { [weak self] in self?.attemptPurchase() }
        restoreButton.addTarget(self, action: #selector(restoreTapped), for: .touchUpInside)
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)

        applyProductState()
        applyBusyState()

        Task { await loadProduct() }
    }

    // MARK: - Layout

    private func setUpViews() {
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.alwaysBounceVertical = true
        view.addSubview(scrollView)

        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(stack)

        iconView.configure(meta: lockedMeta)
        iconView.translatesAutoresizingMaskIntoConstraints = false

        titleLabel.text = Strings.string(for: .unlockTitle)
        titleLabel.font = .systemFont(ofSize: 24, weight: .bold)
        titleLabel.textColor = ScenePalette.colors[PaletteIndex.fg]
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 0

        bodyLabel.text = Strings.string(for: .unlockBody)
        bodyLabel.font = .systemFont(ofSize: 16, weight: .regular)
        bodyLabel.textColor = ScenePalette.colors[PaletteIndex.fgDim]
        bodyLabel.textAlignment = .center
        bodyLabel.numberOfLines = 0

        priceLabel.font = .systemFont(ofSize: 20, weight: .semibold)
        priceLabel.textColor = ScenePalette.colors[PaletteIndex.fg]
        priceLabel.textAlignment = .center
        priceLabel.numberOfLines = 1

        offlineLabel.text = Strings.string(for: .offlineNotice)
        offlineLabel.font = .systemFont(ofSize: 15, weight: .medium)
        offlineLabel.textColor = ScenePalette.colors[PaletteIndex.danger]
        offlineLabel.textAlignment = .center
        offlineLabel.numberOfLines = 0

        unlockButton.setTitleText(Strings.string(for: .unlockAll))

        gateHintLabel.text = Strings.string(for: .parentalGate)
        gateHintLabel.font = .systemFont(ofSize: 12, weight: .regular)
        gateHintLabel.textColor = ScenePalette.colors[PaletteIndex.fgDim]
        gateHintLabel.textAlignment = .center
        gateHintLabel.numberOfLines = 0

        restoreButton.setTitle(Strings.string(for: .restore), for: .normal)
        restoreButton.titleLabel?.font = .systemFont(ofSize: 16, weight: .medium)
        restoreButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true

        closeButton.setImage(UIImage(systemName: "xmark.circle.fill"), for: .normal)
        closeButton.tintColor = ScenePalette.colors[PaletteIndex.fgDim]
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.accessibilityLabel = Strings.close

        activityIndicator.hidesWhenStopped = true
        activityIndicator.translatesAutoresizingMaskIntoConstraints = false

        let iconWrapper = UIView()
        iconWrapper.translatesAutoresizingMaskIntoConstraints = false
        iconWrapper.addSubview(iconView)
        NSLayoutConstraint.activate([
            iconView.topAnchor.constraint(equalTo: iconWrapper.topAnchor),
            iconView.bottomAnchor.constraint(equalTo: iconWrapper.bottomAnchor),
            iconView.centerXAnchor.constraint(equalTo: iconWrapper.centerXAnchor),
            iconView.widthAnchor.constraint(equalToConstant: 96),
            iconView.heightAnchor.constraint(equalToConstant: 96),
        ])

        [iconWrapper, titleLabel, bodyLabel, priceLabel, offlineLabel, unlockButton, gateHintLabel, restoreButton]
            .forEach { stack.addArrangedSubview($0) }
        stack.setCustomSpacing(24, after: iconWrapper)
        stack.setCustomSpacing(4, after: unlockButton)

        view.addSubview(closeButton)
        view.addSubview(activityIndicator)

        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 44),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),

            stack.topAnchor.constraint(equalTo: scrollView.topAnchor, constant: 20),
            stack.bottomAnchor.constraint(equalTo: scrollView.bottomAnchor, constant: -20),
            stack.leadingAnchor.constraint(equalTo: scrollView.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: scrollView.trailingAnchor, constant: -24),
            stack.widthAnchor.constraint(equalTo: scrollView.widthAnchor, constant: -48),

            closeButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            closeButton.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            closeButton.widthAnchor.constraint(equalToConstant: 30),
            closeButton.heightAnchor.constraint(equalToConstant: 30),

            activityIndicator.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            activityIndicator.topAnchor.constraint(equalTo: closeButton.bottomAnchor, constant: 8),
        ])
    }

    // MARK: - Product loading

    private func loadProduct() async {
        product = await services.store.loadProduct()
        applyProductState()
    }

    private func applyProductState() {
        if let product {
            priceLabel.text = product.displayPrice
            priceLabel.isHidden = false
            offlineLabel.isHidden = true
        } else {
            priceLabel.isHidden = true
            offlineLabel.isHidden = false
        }
        unlockButton.setEnabled(product != nil && !isBusy)
    }

    private func applyBusyState() {
        if isBusy {
            activityIndicator.startAnimating()
        } else {
            activityIndicator.stopAnimating()
        }
        restoreButton.isEnabled = !isBusy
        unlockButton.setEnabled(product != nil && !isBusy)
    }

    // MARK: - Actions

    private func attemptPurchase() {
        guard let product, !isBusy else { return }
        isBusy = true
        Task {
            let outcome = await services.store.purchase(product)
            isBusy = false
            handlePurchaseOutcome(outcome)
        }
    }

    @objc private func restoreTapped() {
        guard !isBusy else { return }
        isBusy = true
        Task {
            let outcome = await services.store.restore()
            isBusy = false
            handleRestoreOutcome(outcome)
        }
    }

    @objc private func closeTapped() {
        dismiss(animated: true)
    }

    private func handlePurchaseOutcome(_ outcome: StoreManager.PurchaseOutcome) {
        switch outcome {
        case .unlocked:
            onUnlockChanged?()
            dismiss(animated: true)
        case .pending:
            presentStatus(Strings.string(for: .purchasePending))
        case .cancelled:
            break // Silent: the person just backed out, not an error.
        case .failed:
            presentStatus(Strings.string(for: .purchaseFailed))
        }
    }

    private func handleRestoreOutcome(_ outcome: StoreManager.RestoreOutcome) {
        switch outcome {
        case .restored:
            onUnlockChanged?()
            presentStatus(Strings.string(for: .restored)) { [weak self] in
                self?.dismiss(animated: true)
            }
        case .nothing:
            presentStatus(Strings.string(for: .nothingToRestore))
        case .failed:
            presentStatus(Strings.string(for: .purchaseFailed))
        }
    }

    private func presentStatus(_ message: String, completion: (() -> Void)? = nil) {
        let alert = UIAlertController(title: message, message: nil, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: Strings.close, style: .default) { _ in completion?() })
        present(alert, animated: true)
    }
}
