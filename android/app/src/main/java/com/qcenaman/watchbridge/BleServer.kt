package com.qcenaman.watchbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.qcenaman.watchbridge.core.Command
import com.qcenaman.watchbridge.core.Framer
import com.qcenaman.watchbridge.core.Message
import com.qcenaman.watchbridge.core.Protocol
import java.util.ArrayDeque

/** BLE peripheral the watch connects to. All calls happen on the main thread. */
@SuppressLint("MissingPermission")
class BleServer(private val context: Context, private val onCommand: (Command) -> Unit) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private lateinit var events: BluetoothGattCharacteristic
    private val subscribers = mutableSetOf<BluetoothDevice>()
    private val mtu = mutableMapOf<String, Int>()
    private val queue = ArrayDeque<Pair<BluetoothDevice, ByteArray>>()
    private var sending = false
    private var nextId = 0

    val connected: Boolean get() = subscribers.isNotEmpty()
    var onStateChanged: (() -> Unit)? = null

    fun start() {
        if (server != null) return
        val adapter = manager?.adapter ?: return
        if (!adapter.isEnabled) return
        server = manager.openGattServer(context, callback)
        events = BluetoothGattCharacteristic(
            Protocol.EVENTS,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    Protocol.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        val command = BluetoothGattCharacteristic(
            Protocol.COMMAND,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val service = BluetoothGattService(Protocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(events)
        service.addCharacteristic(command)
        server?.addService(service)
        adapter.bluetoothLeAdvertiser?.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build(),
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(Protocol.SERVICE)).build(),
            advertiseCallback,
        )
    }

    fun stop() {
        manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        server?.close()
        server = null
        subscribers.clear()
        queue.clear()
        sending = false
    }

    fun send(message: Message) {
        if (subscribers.isEmpty()) return
        val bytes = message.toJson().toByteArray(Charsets.UTF_8)
        val id = nextId++ and 0xFF
        for (device in subscribers) {
            val size = (mtu[device.address] ?: 23) - 3
            Framer.split(id, bytes, size).forEach { queue.add(device to it) }
        }
        pump()
    }

    private fun pump() {
        if (sending) return
        val (device, chunk) = queue.poll() ?: return
        sending = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            server?.notifyCharacteristicChanged(device, events, false, chunk) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            events.value = chunk
            @Suppress("DEPRECATION")
            server?.notifyCharacteristicChanged(device, events, false) == true
        }
        if (!ok) {
            sending = false
            Log.w(TAG, "notify failed, dropping chunk")
            pump()
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribers.remove(device)
                mtu.remove(device.address)
                queue.removeAll { it.first == device }
                MainHandler.post { onStateChanged?.invoke() }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, value: Int) {
            mtu[device.address] = value
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            MainHandler.post { sending = false; pump() }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            MainHandler.post {
                if (descriptor.uuid == Protocol.CCCD) {
                    val on = value != null && value.isNotEmpty() && value[0].toInt() != 0
                    if (on) subscribers.add(device) else subscribers.remove(device)
                    onStateChanged?.invoke()
                }
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (characteristic.uuid != Protocol.COMMAND || value == null) return
            val cmd = Command.parse(String(value, Charsets.UTF_8)) ?: return
            MainHandler.post { onCommand(cmd) }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertising failed: $errorCode")
        }
    }

    companion object {
        private const val TAG = "WatchBridgeBle"
    }
}
