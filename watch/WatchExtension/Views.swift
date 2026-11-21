import BridgeCore
import SwiftUI
import WatchKit

final class HostingController: WKHostingController<RootView> {
    override var body: RootView { RootView(model: AppModel.shared) }
}

struct RootView: View {
    @ObservedObject var model: AppModel
    @State private var now = Date()
    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ScrollView {
            VStack(spacing: 4) {
                Text(time).font(.system(size: 52, weight: .semibold, design: .rounded))
                Text(date).font(.footnote).foregroundColor(.gray)
                HStack(spacing: 6) {
                    Circle().fill(model.connected ? Color.green : Color.red).frame(width: 8, height: 8)
                    Text(model.linkState).font(.footnote)
                }
                if let pct = model.phoneBattery {
                    Text("Phone \(pct)%\(model.phoneCharging ? " ⚡︎" : "")").font(.footnote).foregroundColor(.gray)
                }
                NavigationLink(destination: InboxView(model: model)) {
                    Text(model.inbox.items.isEmpty ? "No notifications" : "\(model.inbox.items.count) notifications")
                }
                Button("Find phone") { model.ble.send(.findPhone) }
                if !model.keepAliveOn {
                    Button("Keep running") { KeepAlive.shared.start() }
                        .foregroundColor(.orange)
                }
            }
        }
        .onReceive(tick) { now = $0 }
    }

    private var time: String { Self.timeFormat.string(from: now) }
    private var date: String { Self.dateFormat.string(from: now) }

    private static let timeFormat: DateFormatter = {
        let f = DateFormatter(); f.timeStyle = .short; f.dateStyle = .none; return f
    }()
    private static let dateFormat: DateFormatter = {
        let f = DateFormatter(); f.setLocalizedDateFormatFromTemplate("EEEdMMM"); return f
    }()
}

struct InboxView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        List {
            ForEach(model.inbox.items) { n in
                NavigationLink(destination: DetailView(model: model, notification: n)) {
                    VStack(alignment: .leading) {
                        Text(n.app).font(.footnote).foregroundColor(.gray)
                        Text(n.title.isEmpty ? n.text : n.title).lineLimit(2)
                    }
                }
            }
        }
        .navigationBarTitle("Phone")
    }
}

struct DetailView: View {
    @ObservedObject var model: AppModel
    let notification: PhoneNotification
    @Environment(\.presentationMode) private var presentation

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                Text(notification.app).font(.footnote).foregroundColor(.gray)
                if !notification.title.isEmpty { Text(notification.title).font(.headline) }
                Text(notification.text)
                Button("Dismiss on phone") {
                    model.dismiss(notification)
                    presentation.wrappedValue.dismiss()
                }
            }
        }
    }
}
