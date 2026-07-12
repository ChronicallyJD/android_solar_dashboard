package com.offgrid.solardashboard.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.offgrid.solardashboard.protocol.VictronParser
import kotlinx.coroutines.delay

/**
 * Passive/active BLE scan collecting Victron Instant Readout advertisements.
 * Android port of solar_monitor/scanner.py VictronScanner: Victron devices are
 * never connected. All data is read from the manufacturer-data payload
 * (company ID 0x02E1). Every DISTINCT payload per MAC is accumulated because
 * Victron rotates record types across broadcasts.
 */
class VictronScanner(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    // MAC -> ordered set of distinct payloads seen this cycle
    private val payloads = HashMap<String, LinkedHashSet<String>>()
    private val names = HashMap<String, String?>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handle(result: ScanResult) {
        val record = result.scanRecord ?: return
        val mfr = record.getManufacturerSpecificData(VictronParser.VICTRON_MFR_ID) ?: return
        val mac = result.device.address.uppercase()
        val hex = mfr.toHex()
        payloads.getOrPut(mac) { LinkedHashSet() }.add(hex)
        // The advertised name often arrives in a later packet (scan response /
        // extended-adv AUX) than the manufacturer data. Record it whenever a
        // non-null name shows up rather than locking in the first (null) sight.
        val advName = record.deviceName
        if (advName != null || mac !in names) names[mac] = advName
    }

    private fun hasScanPermission(): Boolean {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 31)
            Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** Run a scan for [durationSec], leaving accumulated payloads queryable. */
    @SuppressLint("MissingPermission")
    suspend fun scan(durationSec: Int) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (!hasScanPermission()) {
            Log.w(TAG, "missing scan permission")
            return
        }
        payloads.clear()
        names.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                // Victron Instant Readout is broadcast via BLE *extended*
                // advertising (the encrypted frame plus device name exceed the
                // 31-byte legacy limit). Android reports legacy-only by default,
                // so those frames (the ones we can actually decode) are
                // dropped and the device looks permanently offline. Reporting
                // all PDU types is required to see them.
                setLegacy(false)
                setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            }
            .build()
        try {
            scanner.startScan(null, settings, callback)
            delay(durationSec * 1000L)
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (e: Exception) {
                Log.w(TAG, "stopScan: ${e.message}")
            }
        }
    }

    fun payloadsFor(mac: String): List<ByteArray> =
        payloads[mac.uppercase()]?.map { VictronParser.hexToBytes(it) } ?: emptyList()

    fun nameFor(mac: String): String? = names[mac.uppercase()]

    fun seenMacs(): Set<String> = payloads.keys.toSet()

    /** A Victron device found during a scan, for the settings device picker. */
    data class Discovered(
        val mac: String,
        val name: String?,
        val recordTypes: Set<Int>,
        val suggestedType: String,
    )

    /** All Victron devices seen this scan, with their advertised name and a
     *  suggested device type inferred from the record types they broadcast. */
    fun discovered(): List<Discovered> = payloads.map { (mac, set) ->
        val rts = set.mapNotNull { hex ->
            val rt = VictronParser.parsePayload(VictronParser.hexToBytes(hex)).recordType
            if (rt == 0xFF) null else rt
        }.toSet()
        Discovered(mac, names[mac], rts, suggestType(rts))
    }.sortedBy { it.name ?: it.mac }

    private fun suggestType(records: Set<Int>): String = when {
        records.any { it in setOf(0x03, 0x06, 0x07, 0x0B, 0x0C) } -> "inverter"
        records.any { it in setOf(0x04, 0x09, 0x0D) } -> "dcdc"
        records.contains(0x05) -> "lithium"
        records.contains(0x01) -> "mppt"
        records.any { it in setOf(0x02, 0x08) } -> "monitor"
        else -> "mppt"
    }

    companion object {
        private const val TAG = "VictronScanner"
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
