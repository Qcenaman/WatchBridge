import XCTest
@testable import BridgeCore

final class BridgeCoreTests: XCTestCase {
    func testReassemblesChunks() {
        var r = Reassembler()
        XCTAssertNil(r.add(Data([5, 0]) + Data("{\"t\":".utf8)))
        let out = r.add(Data([5, 1]) + Data("\"r\",\"id\":\"k\"}".utf8))
        XCTAssertEqual(out.flatMap(BridgeMessage.decode), .removed("k"))
    }

    func testInterleavedIds() {
        var r = Reassembler()
        XCTAssertNil(r.add(Data([1, 0, 65])))
        XCTAssertEqual(r.add(Data([2, 1, 66])), Data([66]))
        XCTAssertEqual(r.add(Data([1, 1, 67])), Data([65, 67]))
    }

    func testDecodesPosted() {
        let json = "{\"t\":\"n\",\"id\":\"a\",\"app\":\"WhatsApp\",\"title\":\"Hi\\nyou\",\"text\":\"x\",\"time\":1000,\"buzz\":true}"
        guard case .posted(let n)? = BridgeMessage.decode(Data(json.utf8)) else { return XCTFail() }
        XCTAssertEqual(n.title, "Hi\nyou")
        XCTAssertEqual(n.time.timeIntervalSince1970, 1)
        XCTAssertTrue(n.buzz)
    }

    func testDecodesBatteryAndRejectsJunk() {
        XCTAssertEqual(BridgeMessage.decode(Data("{\"t\":\"b\",\"pct\":80,\"chg\":true}".utf8)),
                       .battery(percent: 80, charging: true))
        XCTAssertNil(BridgeMessage.decode(Data("nope".utf8)))
    }

    func testCommandsMatchAndroidParser() {
        XCTAssertEqual(String(data: BridgeCommand.dismiss("k").encode(), encoding: .utf8), "{\"c\":\"dismiss\",\"id\":\"k\"}")
        XCTAssertEqual(String(data: BridgeCommand.findPhone.encode(), encoding: .utf8), "{\"c\":\"find\"}")
    }

    func testInboxOrdersDedupesAndCaps() {
        var inbox = Inbox(capacity: 2)
        func n(_ id: String, _ t: Double) -> BridgeMessage {
            .posted(PhoneNotification(id: id, app: "", title: "", text: "", time: Date(timeIntervalSince1970: t), buzz: false))
        }
        inbox.apply(n("a", 1)); inbox.apply(n("b", 2)); inbox.apply(n("a", 3)); inbox.apply(n("c", 0))
        XCTAssertEqual(inbox.items.map(\.id), ["a", "b"])
        inbox.apply(.removed("a"))
        XCTAssertEqual(inbox.items.map(\.id), ["b"])
    }
}
