import UIKit
import GamesCore

/// Full-catalogue grid, one tile per `GameRegistry.shared.all` entry
/// (PLAN.md §5). M0 only registers Snake, but the compositional layout
/// (2 columns in portrait, 3-4 in landscape/wide) already supports the
/// full 15-tile catalogue from later milestones. Locked tiles are shown
/// with a padlock rather than hidden, per PLAN.md §6 ("aucun blocage").
final class CatalogViewController: UIViewController {
    private let services: Services

    /// The full catalogue backing the grid. `private(set)` (not `private`)
    /// so `SmokeTests` can assert its count against `GameRegistry`.
    private(set) var games: [GameMeta] = []

    /// `silentRevalidateIfNeeded()` is opportunistic and cheap to call
    /// repeatedly (it no-ops unless `Entitlement.shouldRevalidate` says
    /// it's been 7+ days), but PLAN.md §6 wants it at most once per catalog
    /// open, not on every `viewWillAppear` (e.g. closing a game and
    /// returning to the catalog shouldn't re-trigger it). One process-wide
    /// flag is enough since `Services`/`StoreManager` are single instances
    /// for the process lifetime.
    private static var hasRevalidatedThisLaunch = false

    private lazy var collectionView: UICollectionView = {
        let layout = CatalogViewController.makeLayout()
        let view = UICollectionView(frame: .zero, collectionViewLayout: layout)
        view.backgroundColor = ScenePalette.colors[PaletteIndex.bg]
        view.register(TileCell.self, forCellWithReuseIdentifier: TileCell.reuseIdentifier)
        view.dataSource = self
        view.delegate = self
        view.translatesAutoresizingMaskIntoConstraints = false
        return view
    }()

    /// "Unlock all" banner shown above the grid while any game is locked
    /// (PLAN.md §6/§5). Collapsed to zero height (not just hidden) once the
    /// catalog is fully unlocked, via `bannerHeightConstraint`.
    private lazy var unlockBanner: UIButton = {
        let button = UIButton(type: .system)
        button.backgroundColor = ScenePalette.colors[PaletteIndex.accent]
        button.setTitleColor(.white, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        button.setTitle(Strings.string(for: .unlockAll), for: .normal)
        button.addTarget(self, action: #selector(unlockBannerTapped), for: .touchUpInside)
        button.translatesAutoresizingMaskIntoConstraints = false
        button.isHidden = true
        button.clipsToBounds = true
        return button
    }()

    private var bannerHeightConstraint: NSLayoutConstraint!

    init(services: Services) {
        self.services = services
        super.init(nibName: nil, bundle: nil)
        title = Strings.appName
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ScenePalette.colors[PaletteIndex.bg]
        games = GameRegistry.shared.all

        navigationItem.rightBarButtonItem = UIBarButtonItem(
            image: UIImage(systemName: "gearshape"),
            style: .plain,
            target: self,
            action: #selector(openSettings)
        )

        view.addSubview(unlockBanner)
        view.addSubview(collectionView)

        bannerHeightConstraint = unlockBanner.heightAnchor.constraint(equalToConstant: 0)

        NSLayoutConstraint.activate([
            unlockBanner.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            unlockBanner.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            unlockBanner.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bannerHeightConstraint,

            collectionView.topAnchor.constraint(equalTo: unlockBanner.bottomAnchor),
            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collectionView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        updateUnlockBanner()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // Reload every time the catalog becomes visible again (closing a
        // game, or dismissing the paywall after a purchase/restore) so a
        // freshly granted entitlement immediately unlocks tiles and hides
        // the banner -- PLAN.md §5's "aucun rafraîchissement sur le
        // catalogue" is about not redrawing mid-scroll for no reason, not
        // about skipping a refresh when returning to it.
        games = GameRegistry.shared.all
        updateUnlockBanner()
        collectionView.reloadData()
        revalidateIfNeeded()
    }

    @objc private func openSettings() {
        let settingsVC = SettingsViewController(services: services)
        navigationController?.pushViewController(settingsVC, animated: true)
    }

    @objc private func unlockBannerTapped() {
        guard let meta = firstLockedMeta() else { return }
        presentPaywall(for: meta)
    }

    private func firstLockedMeta() -> GameMeta? {
        games.first { $0.tier == .unlocked }
    }

    private func updateUnlockBanner() {
        let shouldShow = !services.isUnlocked && firstLockedMeta() != nil
        unlockBanner.isHidden = !shouldShow
        bannerHeightConstraint.constant = shouldShow ? 48 : 0
    }

    private func revalidateIfNeeded() {
        guard !CatalogViewController.hasRevalidatedThisLaunch else { return }
        guard services.isUnlocked else { return }
        CatalogViewController.hasRevalidatedThisLaunch = true
        Task { await services.store.silentRevalidateIfNeeded() }
    }

    private func presentPaywall(for meta: GameMeta) {
        let paywall = PaywallViewController(services: services, lockedMeta: meta) { [weak self] in
            guard let self else { return }
            self.updateUnlockBanner()
            self.collectionView.reloadData()
        }
        present(paywall, animated: true)
    }

    private static func makeLayout() -> UICollectionViewCompositionalLayout {
        UICollectionViewCompositionalLayout { _, environment in
            let width = environment.container.effectiveContentSize.width
            let columns = width >= 700 ? 4 : (width >= 460 ? 3 : 2)
            let spacing: CGFloat = 12

            let itemSize = NSCollectionLayoutSize(
                widthDimension: .fractionalWidth(1.0 / CGFloat(columns)),
                heightDimension: .fractionalWidth(1.0 / CGFloat(columns))
            )
            let item = NSCollectionLayoutItem(layoutSize: itemSize)
            item.contentInsets = NSDirectionalEdgeInsets(
                top: spacing / 2, leading: spacing / 2, bottom: spacing / 2, trailing: spacing / 2
            )

            let groupSize = NSCollectionLayoutSize(
                widthDimension: .fractionalWidth(1.0),
                heightDimension: .fractionalWidth(1.0 / CGFloat(columns))
            )
            let group = NSCollectionLayoutGroup.horizontal(layoutSize: groupSize, subitems: Array(repeating: item, count: columns))

            let section = NSCollectionLayoutSection(group: group)
            section.contentInsets = NSDirectionalEdgeInsets(top: spacing, leading: spacing, bottom: spacing, trailing: spacing)
            return section
        }
    }
}

extension CatalogViewController: UICollectionViewDataSource {
    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        games.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        guard let cell = collectionView.dequeueReusableCell(
            withReuseIdentifier: TileCell.reuseIdentifier,
            for: indexPath
        ) as? TileCell else {
            return UICollectionViewCell()
        }
        let meta = games[indexPath.item]
        cell.configure(meta: meta, locked: isLocked(meta))
        return cell
    }
}

extension CatalogViewController: UICollectionViewDelegate {
    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        collectionView.deselectItem(at: indexPath, animated: true)
        let meta = games[indexPath.item]
        if isLocked(meta) {
            presentPaywall(for: meta)
            return
        }
        let gameVC = GameViewController(meta: meta, services: services)
        gameVC.modalPresentationStyle = .fullScreen
        present(gameVC, animated: true)
    }

    private func isLocked(_ meta: GameMeta) -> Bool {
        meta.tier == .unlocked && !services.isUnlocked
    }
}
