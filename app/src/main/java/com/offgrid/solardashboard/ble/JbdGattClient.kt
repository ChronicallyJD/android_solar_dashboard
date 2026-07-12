package com.offgrid.solardashboard.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.offgrid.solardashboard.protocol.DeviceReading
import com.offgrid.solardashboard.protocol.JbdProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Connects to a JBD/Vatrer BMS over GATT and reads register 0x03 basic info.
 * Android port of solar_monitor/jbd.py's JBDGattReader + read_jbd_device.
 *
 * Resilience mirrors the Python design:
 *  - buffer resync (strip non-0xDD leading bytes, discard corrupt-length frames)
 *  - notify-settle delay before writing the command
 *  - whole operation bounded by PER_DEVICE_TIMEOUT
 */
class JbdGattClient(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    private val buffer = ArrayList<Byte>()
    private var packetReady: CompletableDeferred<ByteArray>? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var connected: CompletableDeferred<Boolean>? = null
    private var servicesReady: CompletableDeferred<Boolean>? = null

    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    @SuppressLint("MissingPermission")
    suspend fun read(mac: String, friendlyName: String?, password: String?): DeviceReading {
        val ts = nowIso()
        val name = friendlyName ?: mac
        val r = DeviceReading(address = mac, name = name, deviceType = "bms", timestamp = ts)
        var gatt: BluetoothGatt? = null
        try {
            withTimeout(JbdProtocol.PER_DEVICE_TIMEOUT_MS) {
                val device = adapter?.getRemoteDevice(mac)
                    ?: throw IllegalStateException("Bluetooth adapter unavailable")
                connected = CompletableDeferred()
                servicesReady = CompletableDeferred()
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice_TRANSPORT_LE)
                connected!!.await()
                gatt!!.discoverServices()
                servicesReady!!.await()

                discoverChars(gatt!!)
                enableNotify(gatt!!)
                delay(JbdProtocol.NOTIFY_SETTLE_DELAY_MS)

                if (!password.isNullOrEmpty()) {
                    authenticate(gatt!!, password)
                }
                val raw = sendRecv(gatt!!, JbdProtocol.BASIC_INFO_CMD)
                val info = JbdProtocol.parseBasicInfo(raw)
                JbdProtocol.applyTo(r, info)
            }
        } catch (e: TimeoutCancellationException) {
            r.error = "Timed out after ${JbdProtocol.PER_DEVICE_TIMEOUT_MS / 1000}s - " +
                "device connected but did not respond"
        } catch (e: Exception) {
            r.error = e.message ?: e.toString()
        } finally {
            try {
                gatt?.disconnect(); gatt?.close()
            } catch (_: Exception) {}
        }
        return r
    }

    @SuppressLint("MissingPermission")
    private fun discoverChars(gatt: BluetoothGatt) {
        // Known-UUID match preferred, then heuristic (notify + write).
        for (cand in JbdProtocol.UUID_CANDIDATES) {
            val svc = gatt.getService(UUID.fromString(cand.service)) ?: continue
            val tx = svc.getCharacteristic(UUID.fromString(cand.tx))
            val rx = svc.getCharacteristic(UUID.fromString(cand.rx))
            if (tx != null && rx != null) {
                txChar = tx; rxChar = rx; return
            }
        }
        for (svc in gatt.services) {
            val notif = svc.characteristics.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            }
            val writer = svc.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            if (notif != null && writer != null) {
                txChar = notif; rxChar = writer; return
            }
        }
        throw IllegalStateException("No compatible JBD service found - check device firmware or UUID list")
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(gatt: BluetoothGatt) {
        val tx = txChar ?: throw IllegalStateException("TX characteristic missing")
        gatt.setCharacteristicNotification(tx, true)
        tx.getDescriptor(CCCD_UUID)?.let { d ->
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor_ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(d)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun authenticate(gatt: BluetoothGatt, password: String) {
        val reply = sendRecv(gatt, JbdProtocol.buildAuthCommand(password))
        if (reply.size < 3) throw IllegalStateException("Auth response too short (${reply.size}B)")
        if (reply[2].toInt() and 0xFF == 0x80)
            throw IllegalStateException("BMS rejected password - update the password in your config")
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendRecv(gatt: BluetoothGatt, cmd: ByteArray): ByteArray {
        synchronized(buffer) { buffer.clear() }
        val deferred = CompletableDeferred<ByteArray>()
        packetReady = deferred
        val rx = rxChar ?: throw IllegalStateException("RX characteristic disappeared after connect")
        val withResponse = rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        @Suppress("DEPRECATION")
        rx.value = cmd
        @Suppress("DEPRECATION")
        rx.writeType = if (withResponse)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        gatt.writeCharacteristic(rx)
        return withTimeout(JbdProtocol.READ_TIMEOUT_MS) { deferred.await() }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (connected?.isCompleted == false)
                    connected?.completeExceptionally(IllegalStateException("Failed to connect"))
                packetReady?.let { if (!it.isCompleted) it.completeExceptionally(IllegalStateException("Device disconnected")) }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            servicesReady?.complete(true)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onNotify(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            onNotify(value)
        }
    }

    /** Accumulate notify bytes, resync on the 0xDD marker, signal on complete packet. */
    private fun onNotify(data: ByteArray) {
        synchronized(buffer) {
            data.forEach { buffer.add(it) }
            // Strip non-0xDD leading bytes
            while (buffer.isNotEmpty() && (buffer[0].toInt() and 0xFF) != 0xDD) buffer.removeAt(0)
            // Discard corrupt-length frames
            if (buffer.size >= 4 && (buffer[3].toInt() and 0xFF) > JbdProtocol.MAX_PAYLOAD_LEN) {
                buffer.clear(); return
            }
            val arr = buffer.toByteArray()
            if (JbdProtocol.packetComplete(arr)) {
                packetReady?.let { if (!it.isCompleted) it.complete(arr) }
            }
        }
    }

    companion object {
        // Avoid importing BluetoothDevice constants directly to keep the file focused.
        private const val BluetoothDevice_TRANSPORT_LE = 2
        private val BluetoothGattDescriptor_ENABLE_NOTIFICATION_VALUE =
            byteArrayOf(0x01, 0x00)
    }
}
