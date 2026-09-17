import UIKit
import GamesCore

/// Sound / haptics toggles bound to `lg.core.store.Settings`, a
/// "Restore purchases" row (checkmark + non-interactive once unlocked), and
/// the app version, in a plain `UITableViewController(style: .insetGrouped)`
/// (PLAN.md §5).
@MainActor
final class SettingsViewController: UITableViewController {
    private let services: Services

    private enum Section: Int, CaseIterable {
        case toggles
        case purchases
        case about
    }

    private var isRestoring = false

    init(services: Services) {
        self.services = services
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = Strings.string(for: .settings)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // The entitlement can change while this screen isn't visible (a
        // purchase or restore from the paywall, or a `Transaction.updates`
        // grant/revoke arriving in the background); refresh the purchases
        // row every time this screen appears.
        tableView.reloadSections(IndexSet(integer: Section.purchases.rawValue), with: .none)
    }

    override func numberOfSections(in tableView: UITableView) -> Int {
        Section.allCases.count
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch Section(rawValue: section) {
        case .toggles: return 2 // sound, haptics
        case .purchases: return 1 // restore purchases / unlocked state
        case .about: return 1 // version
        case .none: return 0
        }
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        switch Section(rawValue: indexPath.section) {
        case .toggles:
            return switchCell(forRow: indexPath.row)
        case .purchases:
            let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
            if services.isUnlocked {
                // "Unlocked" row state: the catalog is already fully
                // unlocked, so there is nothing left to restore.
                cell.textLabel?.text = Strings.string(for: .unlockAll)
                cell.textLabel?.textColor = .secondaryLabel
                cell.accessoryType = .checkmark
                cell.selectionStyle = .none
                cell.isUserInteractionEnabled = false
            } else if isRestoring {
                cell.textLabel?.text = Strings.string(for: .restore)
                cell.textLabel?.textColor = .secondaryLabel
                let spinner = UIActivityIndicatorView(style: .medium)
                spinner.startAnimating()
                cell.accessoryView = spinner
                cell.selectionStyle = .none
                cell.isUserInteractionEnabled = false
            } else {
                cell.textLabel?.text = Strings.string(for: .restore)
                cell.textLabel?.textColor = view.tintColor
                cell.selectionStyle = .default
                cell.isUserInteractionEnabled = true
            }
            return cell
        case .about, .none:
            let cell = UITableViewCell(style: .value1, reuseIdentifier: nil)
            cell.textLabel?.text = Strings.version
            cell.detailTextLabel?.text = SettingsViewController.appVersion
            cell.selectionStyle = .none
            return cell
        }
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard Section(rawValue: indexPath.section) == .purchases else { return }
        guard !services.isUnlocked, !isRestoring else { return }
        restorePurchases()
    }

    private func restorePurchases() {
        isRestoring = true
        tableView.reloadSections(IndexSet(integer: Section.purchases.rawValue), with: .none)
        Task {
            let outcome = await services.store.restore()
            isRestoring = false
            tableView.reloadSections(IndexSet(integer: Section.purchases.rawValue), with: .none)
            presentRestoreResult(outcome)
        }
    }

    private func presentRestoreResult(_ outcome: StoreManager.RestoreOutcome) {
        let message: String
        switch outcome {
        case .restored:
            message = Strings.string(for: .restored)
        case .nothing:
            message = Strings.string(for: .nothingToRestore)
        case .failed:
            message = Strings.string(for: .purchaseFailed)
        }
        let alert = UIAlertController(title: message, message: nil, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: Strings.close, style: .default))
        present(alert, animated: true)
    }

    private func switchCell(forRow row: Int) -> UITableViewCell {
        let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
        cell.selectionStyle = .none
        let toggle = UISwitch()
        if row == 0 {
            cell.textLabel?.text = Strings.string(for: .sound)
            toggle.isOn = services.settings.soundEnabled
            toggle.addTarget(self, action: #selector(soundToggled(_:)), for: .valueChanged)
        } else {
            cell.textLabel?.text = Strings.string(for: .haptics)
            toggle.isOn = services.settings.hapticsEnabled
            toggle.addTarget(self, action: #selector(hapticsToggled(_:)), for: .valueChanged)
        }
        cell.accessoryView = toggle
        return cell
    }

    @objc private func soundToggled(_ sender: UISwitch) {
        services.settings.soundEnabled = sender.isOn
    }

    @objc private func hapticsToggled(_ sender: UISwitch) {
        services.settings.hapticsEnabled = sender.isOn
    }

    private static var appVersion: String {
        let shortVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
        return "\(shortVersion) (\(build))"
    }
}
