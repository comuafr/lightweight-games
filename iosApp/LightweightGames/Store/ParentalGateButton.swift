import Foundation
import UIKit

/// A big, kid-proof "press and hold" button used to gate the purchase call
/// behind a simple parental gate (PLAN.md §6: "barrière parentale simple
/// avant tout appel d'achat"). Not a game loop: the fill animation is a
/// single `UIView.animate` on a width constraint, matching the whole hold
/// duration, and completion is decided from wall-clock elapsed time rather
/// than by polling the animation.
final class ParentalGateButton: UIControl {
    /// Called once, on the main thread, after a hold of at least
    /// `holdDuration` ends with the finger still down. Never called for a
    /// hold that is released early.
    var onUnlocked: (() -> Void)?

    private let holdDuration: TimeInterval = 3.0

    private let background = UIView()
    private let fill = UIView()
    private let titleLabel = UILabel()
    private var fillWidthConstraint: NSLayoutConstraint!
    private var pressStartTime: Date?

    override init(frame: CGRect) {
        super.init(frame: frame)
        setUpViews()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    /// Text shown on the button face (e.g. "Unlock every game" + price).
    func setTitleText(_ text: String) {
        titleLabel.text = text
    }

    func setEnabled(_ enabled: Bool) {
        isUserInteractionEnabled = enabled
        alpha = enabled ? 1.0 : 0.4
        if !enabled {
            cancelPress()
        }
    }

    private func setUpViews() {
        translatesAutoresizingMaskIntoConstraints = false
        backgroundColor = .clear
        layer.cornerRadius = 16
        clipsToBounds = true

        background.backgroundColor = ScenePalette.colors[PaletteIndex.accent].withAlphaComponent(0.25)
        background.isUserInteractionEnabled = false
        background.translatesAutoresizingMaskIntoConstraints = false
        addSubview(background)

        fill.backgroundColor = ScenePalette.colors[PaletteIndex.accent]
        fill.isUserInteractionEnabled = false
        fill.translatesAutoresizingMaskIntoConstraints = false
        background.addSubview(fill)

        titleLabel.textColor = .white
        titleLabel.font = .systemFont(ofSize: 19, weight: .bold)
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 2
        titleLabel.isUserInteractionEnabled = false
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        addSubview(titleLabel)

        fillWidthConstraint = fill.widthAnchor.constraint(equalToConstant: 0)

        NSLayoutConstraint.activate([
            heightAnchor.constraint(greaterThanOrEqualToConstant: 64),

            background.topAnchor.constraint(equalTo: topAnchor),
            background.leadingAnchor.constraint(equalTo: leadingAnchor),
            background.trailingAnchor.constraint(equalTo: trailingAnchor),
            background.bottomAnchor.constraint(equalTo: bottomAnchor),

            fill.leadingAnchor.constraint(equalTo: background.leadingAnchor),
            fill.topAnchor.constraint(equalTo: background.topAnchor),
            fill.bottomAnchor.constraint(equalTo: background.bottomAnchor),
            fillWidthConstraint,

            titleLabel.centerXAnchor.constraint(equalTo: centerXAnchor),
            titleLabel.centerYAnchor.constraint(equalTo: centerYAnchor),
            titleLabel.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 12),
            titleLabel.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -12),
        ])

        let press = UILongPressGestureRecognizer(target: self, action: #selector(handlePress(_:)))
        press.minimumPressDuration = 0
        addGestureRecognizer(press)
    }

    @objc private func handlePress(_ recognizer: UILongPressGestureRecognizer) {
        switch recognizer.state {
        case .began:
            beginPress()
        case .ended:
            endPress()
        case .cancelled, .failed:
            cancelPress()
        default:
            break
        }
    }

    private func beginPress() {
        pressStartTime = Date()
        layoutIfNeeded()
        fillWidthConstraint.constant = background.bounds.width
        UIView.animate(withDuration: holdDuration, delay: 0, options: [.curveLinear]) {
            self.layoutIfNeeded()
        }
    }

    private func endPress() {
        let elapsed = pressStartTime.map { Date().timeIntervalSince($0) }
        pressStartTime = nil
        resetFill()
        // Small epsilon: the gesture's `.ended` and the animation's nominal
        // 3.0s target can be a beat apart depending on run-loop timing.
        if let elapsed, elapsed >= holdDuration - 0.05 {
            onUnlocked?()
        }
    }

    private func cancelPress() {
        pressStartTime = nil
        resetFill()
    }

    private func resetFill() {
        UIView.animate(withDuration: 0.15) {
            self.fillWidthConstraint.constant = 0
            self.layoutIfNeeded()
        }
    }
}
