import UserNotifications
import WatchKit

final class ExtensionDelegate: NSObject, WKExtensionDelegate, UNUserNotificationCenterDelegate {
    func applicationDidFinishLaunching() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
        _ = AppModel.shared.ble
        KeepAlive.shared.start()
    }

    func applicationDidBecomeActive() {
        KeepAlive.shared.start()
        AppModel.shared.ble.send(.sync)
    }

    // The app already tapped the wrist itself while on screen.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([])
    }

    func handle(_ backgroundTasks: Set<WKRefreshBackgroundTask>) {
        backgroundTasks.forEach { $0.setTaskCompletedWithSnapshot(false) }
    }
}
