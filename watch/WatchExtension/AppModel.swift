import BridgeCore
import Combine
import Foundation
import UserNotifications
import WatchKit

/// Everything the screens show; updated on the main thread.
final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published private(set) var inbox = Inbox()
    @Published var linkState = "Searching…"
    @Published var connected = false
    @Published var phoneBattery: Int?
    @Published var phoneCharging = false
    @Published var keepAliveOn = false

    lazy var ble = BLEClient(model: self)

    func handle(_ message: BridgeMessage) {
        inbox.apply(message)
        switch message {
        case .posted(let n) where n.buzz:
            alert(n)
        case .removed(let id):
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id])
        case .battery(let pct, let chg):
            phoneBattery = pct
            phoneCharging = chg
        default:
            break
        }
    }

    func dismiss(_ n: PhoneNotification) {
        inbox.apply(.removed(n.id))
        ble.send(.dismiss(n.id))
    }

    /// While the app is on screen: tap the wrist. Screen off: a local notification, which also taps.
    private func alert(_ n: PhoneNotification) {
        if WKExtension.shared().applicationState == .active {
            WKInterfaceDevice.current().play(.notification)
            return
        }
        let content = UNMutableNotificationContent()
        content.title = n.title.isEmpty ? n.app : n.title
        content.subtitle = n.title.isEmpty ? "" : n.app
        content.body = n.text
        content.sound = .default
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: n.id, content: content, trigger: nil))
    }
}
