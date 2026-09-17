import UIKit
import GamesCore

/// One catalogue tile: an icon drawn via `IconView`, the title below it,
/// and a padlock overlay for locked (not-yet-purchased) games (PLAN.md §5).
/// Tapping is handled by `CatalogViewController`'s
/// `UICollectionViewDelegate`, not the cell itself.
final class TileCell: UICollectionViewCell {
    static let reuseIdentifier = "TileCell"

    private let surface = UIView()
    private let iconView = IconView()
    private let titleLabel = UILabel()
    private let lockIcon = UIImageView(image: UIImage(systemName: "lock.fill"))

    override init(frame: CGRect) {
        super.init(frame: frame)
        setUpViews()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    private func setUpViews() {
        surface.backgroundColor = ScenePalette.colors[PaletteIndex.surface]
        surface.layer.cornerRadius = 16
        surface.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(surface)

        iconView.translatesAutoresizingMaskIntoConstraints = false
        surface.addSubview(iconView)

        titleLabel.font = .systemFont(ofSize: 14, weight: .semibold)
        titleLabel.textColor = ScenePalette.colors[PaletteIndex.fg]
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 1
        titleLabel.adjustsFontSizeToFitWidth = true
        titleLabel.minimumScaleFactor = 0.7
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        surface.addSubview(titleLabel)

        lockIcon.tintColor = ScenePalette.colors[PaletteIndex.fgDim]
        lockIcon.contentMode = .scaleAspectFit
        lockIcon.translatesAutoresizingMaskIntoConstraints = false
        lockIcon.isHidden = true
        surface.addSubview(lockIcon)

        NSLayoutConstraint.activate([
            surface.topAnchor.constraint(equalTo: contentView.topAnchor),
            surface.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            surface.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            surface.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),

            iconView.topAnchor.constraint(equalTo: surface.topAnchor, constant: 12),
            iconView.centerXAnchor.constraint(equalTo: surface.centerXAnchor),
            iconView.widthAnchor.constraint(equalTo: surface.widthAnchor, multiplier: 0.55),
            iconView.heightAnchor.constraint(equalTo: iconView.widthAnchor),

            titleLabel.topAnchor.constraint(equalTo: iconView.bottomAnchor, constant: 8),
            titleLabel.leadingAnchor.constraint(equalTo: surface.leadingAnchor, constant: 6),
            titleLabel.trailingAnchor.constraint(equalTo: surface.trailingAnchor, constant: -6),
            titleLabel.bottomAnchor.constraint(lessThanOrEqualTo: surface.bottomAnchor, constant: -8),

            lockIcon.topAnchor.constraint(equalTo: surface.topAnchor, constant: 8),
            lockIcon.trailingAnchor.constraint(equalTo: surface.trailingAnchor, constant: -8),
            lockIcon.widthAnchor.constraint(equalToConstant: 18),
            lockIcon.heightAnchor.constraint(equalToConstant: 18),
        ])
    }

    func configure(meta: GameMeta, locked: Bool) {
        iconView.configure(meta: meta)
        titleLabel.text = Strings.string(for: meta.titleKey)
        lockIcon.isHidden = !locked
        surface.alpha = locked ? 0.55 : 1.0

        isAccessibilityElement = true
        accessibilityLabel = titleLabel.text
        accessibilityTraits = locked ? [.button, .notEnabled] : .button
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        lockIcon.isHidden = true
        surface.alpha = 1.0
    }
}
