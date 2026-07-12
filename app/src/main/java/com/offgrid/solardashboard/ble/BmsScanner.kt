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
import com.offgrid.solardashboard.protocol.JbdProtocol
import kotlinx.coroutines.delay

/**
 * General BLE name scan for the BMS device picker. Unlike Victron devices,
 * JBD/Vatrer packs carry no manufacturer-data beacon; they are plain connectable
 * GATT peripherals, so discovery just collects advertised names and flags the
 * ones that look like a BMS (name keyword match or a known BMS module OUI).
 */
class BmsScanner(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    // MAC -> best advertised name seen (a later packet may carry the name).
    private val names = LinkedHashMap<String, String?>()

    data class Discovered(val mac: String, val name: String?, val likelyBms: Boolean)

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed: $errorCode") }
    }

    @SuppressLint("MissingPermission")
    private fun handle(result: ScanResult) {
        val mac = result.device.address.uppercase()
        val advName = result.scanRecord?.deviceName
        if (advName != null || mac !in names) names[mac] = advName
    }

    private fun hasScanPermission(): Boolean {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 31)
            Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun scan(durationSec: Int): List<Discovered> {
        val scanner = adapter?.bluetoothLeScanner ?: return emptyList()
        if (!hasScanPermission()) { Log.w(TAG, "missing scan permission"); return emptyList() }
        names.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply { setLegacy(false); setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) }
            .build()
        try {
            scanner.startScan(null, settings, callback)
            delay(durationSec * 1000L)
        } finally {
            try { scanner.stopScan(callback) } catch (e: Exception) { Log.w(TAG, "stopScan: ${e.message}") }
        }
        return names.map { (mac, name) ->
            val kw = name != null && JbdProtocol.NAME_KEYWORDS.any { name.lowercase().contains(it) }
            val oui = BMS_OUIS.any { mac.startsWith(it) }
            Discovered(mac, name, kw || oui)
        }
            // Drop nameless noise, but keep any OUI-matched device even if unnamed.
            .filter { it.name != null || it.likelyBms }
            .sortedWith(compareByDescending<Discovered> { it.likelyBms }.thenBy { it.name ?: it.mac })
    }

    companion object {
        private const val TAG = "BmsScanner"
        // OUIs commonly used by JBD/Vatrer BMS BLE modules (Telink et al.).
        private val BMS_OUIS = listOf("A4:C1:37")
    }
}
