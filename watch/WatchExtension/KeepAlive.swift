import Foundation
import HealthKit

/// Runs a workout session so watchOS keeps the app (and the Bluetooth link) alive with the screen off.
final class KeepAlive: NSObject, HKWorkoutSessionDelegate {
    static let shared = KeepAlive()

    private let store = HKHealthStore()
    private var session: HKWorkoutSession?

    func start() {
        guard session == nil, HKHealthStore.isHealthDataAvailable() else { return }
        store.requestAuthorization(toShare: [HKObjectType.workoutType()], read: nil) { ok, _ in
            DispatchQueue.main.async { if ok { self.begin() } }
        }
    }

    private func begin() {
        guard session == nil else { return }
        let config = HKWorkoutConfiguration()
        config.activityType = .other
        config.locationType = .indoor
        guard let s = try? HKWorkoutSession(healthStore: store, configuration: config) else { return }
        s.delegate = self
        session = s
        s.startActivity(with: Date())
    }

    func stop() {
        session?.end()
        session = nil
        AppModel.shared.keepAliveOn = false
    }

    func workoutSession(_ workoutSession: HKWorkoutSession, didChangeTo toState: HKWorkoutSessionState,
                        from fromState: HKWorkoutSessionState, date: Date) {
        DispatchQueue.main.async {
            AppModel.shared.keepAliveOn = toState == .running
            if toState == .ended { self.session = nil }
        }
    }

    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
        DispatchQueue.main.async {
            self.session = nil
            AppModel.shared.keepAliveOn = false
        }
    }
}
