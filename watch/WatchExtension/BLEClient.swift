import BridgeCore
import CoreBluetooth
import Foundation

/// Finds the Samsung's WatchBridge service, subscribes to events and writes commands.
final class BLEClient: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private let serviceID = CBUUID(string: BridgeUUID.service)
    private let eventsID = CBUUID(string: BridgeUUID.events)
    private let commandID = CBUUID(string: BridgeUUID.command)

    private weak var model: AppModel?
    private var central: CBCentralManager!
    private var phone: CBPeripheral?
    private var command: CBCharacteristic?
    private var reassembler = Reassembler()
    private let restoreKey = "phoneID"

    init(model: AppModel) {
        self.model = model
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    func send(_ cmd: BridgeCommand) {
        guard let phone = phone, let command = command else { return }
        phone.writeValue(cmd.encode(), for: command, type: .withResponse)
    }

    func reconnect() {
        if let phone = phone { central.cancelPeripheralConnection(phone) }
        start()
    }

    private func start() {
        guard central.state == .poweredOn else { return }
        if let saved = UserDefaults.standard.string(forKey: restoreKey).flatMap(UUID.init(uuidString:)),
           let known = central.retrievePeripherals(withIdentifiers: [saved]).first {
            connect(known)
        }
        set("Searching…", connected: false)
        central.scanForPeripherals(withServices: [serviceID], options: nil)
    }

    private func connect(_ p: CBPeripheral) {
        phone = p
        p.delegate = self
        central.connect(p, options: nil)
    }

    private func set(_ state: String, connected: Bool) {
        model?.linkState = state
        model?.connected = connected
    }

    // MARK: Central

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn: start()
        case .unauthorized: set("Bluetooth not allowed", connected: false)
        case .poweredOff: set("Bluetooth is off", connected: false)
        default: set("Bluetooth unavailable", connected: false)
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        central.stopScan()
        set("Connecting…", connected: false)
        connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        central.stopScan()
        UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: restoreKey)
        reassembler.reset()
        peripheral.discoverServices([serviceID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        retry()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        command = nil
        set("Phone out of reach", connected: false)
        retry()
    }

    private func retry() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in self?.start() }
    }

    // MARK: Peripheral

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == serviceID }) else {
            return central.cancelPeripheralConnection(peripheral)
        }
        peripheral.discoverCharacteristics([eventsID, commandID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        for c in service.characteristics ?? [] {
            if c.uuid == eventsID { peripheral.setNotifyValue(true, for: c) }
            if c.uuid == commandID { command = c }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == eventsID, characteristic.isNotifying else { return }
        set("Connected", connected: true)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == eventsID, let chunk = characteristic.value,
              let data = reassembler.add(chunk), let message = BridgeMessage.decode(data) else { return }
        model?.handle(message)
    }
}
