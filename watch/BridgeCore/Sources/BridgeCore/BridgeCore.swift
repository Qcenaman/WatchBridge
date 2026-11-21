import Foundation

public enum BridgeUUID {
    public static let service = "7A1E0001-5C2B-4D1A-9F3E-2B8A6C0D4E51"
    public static let events = "7A1E0002-5C2B-4D1A-9F3E-2B8A6C0D4E51"
    public static let command = "7A1E0003-5C2B-4D1A-9F3E-2B8A6C0D4E51"
}

public struct PhoneNotification: Equatable, Identifiable {
    public let id: String
    public let app: String
    public let title: String
    public let text: String
    public let time: Date
    public let buzz: Bool

    public init(id: String, app: String, title: String, text: String, time: Date, buzz: Bool) {
        self.id = id; self.app = app; self.title = title; self.text = text; self.time = time; self.buzz = buzz
    }
}

public enum BridgeMessage: Equatable {
    case posted(PhoneNotification)
    case removed(String)
    case battery(percent: Int, charging: Bool)

    private struct Raw: Decodable {
        let t: String
        let id: String?
        let app: String?
        let title: String?
        let text: String?
        let time: Double?
        let buzz: Bool?
        let pct: Int?
        let chg: Bool?
    }

    public static func decode(_ data: Data) -> BridgeMessage? {
        guard let raw = try? JSONDecoder().decode(Raw.self, from: data) else { return nil }
        switch raw.t {
        case "n":
            guard let id = raw.id else { return nil }
            return .posted(PhoneNotification(
                id: id, app: raw.app ?? "", title: raw.title ?? "", text: raw.text ?? "",
                time: Date(timeIntervalSince1970: (raw.time ?? 0) / 1000), buzz: raw.buzz ?? false))
        case "r":
            return raw.id.map { .removed($0) }
        case "b":
            return .battery(percent: raw.pct ?? 0, charging: raw.chg ?? false)
        default:
            return nil
        }
    }
}

public enum BridgeCommand: Equatable {
    case dismiss(String)
    case findPhone
    case sync

    public func encode() -> Data {
        let dict: [String: String]
        switch self {
        case .dismiss(let id): dict = ["c": "dismiss", "id": id]
        case .findPhone: dict = ["c": "find"]
        case .sync: dict = ["c": "sync"]
        }
        return (try? JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])) ?? Data()
    }
}

/// Rebuilds messages from BLE chunks: [messageId, flags] + payload, flags bit 0 = last chunk.
public struct Reassembler {
    private var parts: [UInt8: Data] = [:]
    private let limit = 16 * 1024

    public init() {}

    public mutating func add(_ chunk: Data) -> Data? {
        guard chunk.count >= 2 else { return nil }
        let bytes = [UInt8](chunk)
        let id = bytes[0]
        let last = bytes[1] & 1 == 1
        var buffer = parts[id] ?? Data()
        buffer.append(contentsOf: bytes[2...])
        if buffer.count > limit { parts[id] = nil; return nil }
        if last {
            parts[id] = nil
            return buffer
        }
        parts[id] = buffer
        return nil
    }

    public mutating func reset() { parts.removeAll() }
}

/// Keeps the list the watch shows: newest first, capped, deduplicated by id.
public struct Inbox {
    public private(set) var items: [PhoneNotification] = []
    public let capacity: Int

    public init(capacity: Int = 30) { self.capacity = capacity }

    public mutating func apply(_ message: BridgeMessage) {
        switch message {
        case .posted(let n):
            items.removeAll { $0.id == n.id }
            items.insert(n, at: 0)
            items.sort { $0.time > $1.time }
            if items.count > capacity { items.removeLast(items.count - capacity) }
        case .removed(let id):
            items.removeAll { $0.id == id }
        case .battery:
            break
        }
    }
}
