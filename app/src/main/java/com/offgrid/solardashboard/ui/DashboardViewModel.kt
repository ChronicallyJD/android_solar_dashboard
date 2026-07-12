package com.offgrid.solardashboard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.offgrid.solardashboard.alert.AlertDispatcher
import com.offgrid.solardashboard.ble.BmsScanner
import com.offgrid.solardashboard.ble.VictronScanner
import com.offgrid.solardashboard.data.AlertConfig
import com.offgrid.solardashboard.data.AlertStore
import com.offgrid.solardashboard.data.AppSettings
import com.offgrid.solardashboard.data.DeviceConfig
import com.offgrid.solardashboard.data.HistoryStore
import com.offgrid.solardashboard.data.MonitorState
import com.offgrid.solardashboard.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * UI state holder for the dashboard and settings screens. Live readings and
 * chart history come straight from the process-wide [MonitorState] that the
 * service writes to; device and app settings are read/written through
 * [SettingsRepository]. It also drives on-demand BLE discovery scans and the
 * history-database maintenance actions.
 */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)
    private val historyStore = HistoryStore(app)
    private val alertStore = AlertStore(app)

    /** Latest reading snapshot shared with the polling service. */
    val monitor: StateFlow<MonitorState.Snapshot> = MonitorState.state

    /** Rolling per-device chart history, keyed by device name. */
    val history: StateFlow<Map<String, List<MonitorState.HistPoint>>> = MonitorState.historyFlow

    private val _devices = MutableStateFlow(repo.loadDevices())
    val devices: StateFlow<List<DeviceConfig>> = _devices.asStateFlow()

    private val _settings = MutableStateFlow(repo.loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Re-read devices and settings from storage (e.g. after an external change). */
    fun reload() {
        _devices.value = repo.loadDevices()
        _settings.value = repo.loadSettings()
    }

    /** Append a device and persist the list. */
    fun addDevice(device: DeviceConfig) {
        val updated = _devices.value + device
        repo.saveDevices(updated)
        _devices.value = updated
    }

    /** Replace the device at [index] and persist. */
    fun updateDevice(index: Int, device: DeviceConfig) {
        val updated = _devices.value.toMutableList().also { it[index] = device }
        repo.saveDevices(updated)
        _devices.value = updated
    }

    /** Remove the device at [index] and persist. */
    fun removeDevice(index: Int) {
        val updated = _devices.value.toMutableList().also { it.removeAt(index) }
        repo.saveDevices(updated)
        _devices.value = updated
    }

    /** Persist app settings and publish them to observers. */
    fun saveSettings(s: AppSettings) {
        repo.saveSettings(s)
        _settings.value = s
    }

    /** Cycle the theme dark to light to business and back. */
    fun cycleTheme() {
        val next = when (_settings.value.theme) {
            "dark" -> "light"
            "light" -> "business"
            else -> "dark"
        }
        saveSettings(_settings.value.copy(theme = next))
    }

    fun dismissWelcome() = saveSettings(_settings.value.copy(welcomeSeen = true))

    /**
     * Run a one-off BLE scan and return the Victron devices in range so the
     * settings editor can prepopulate a MAC. The advertisement key is never
     * broadcast, so the user still supplies that by hand. MACs in [exclude]
     * (already-configured devices) are filtered out.
     */
    suspend fun discoverVictron(exclude: Set<String> = emptySet(), seconds: Int = 8): List<VictronScanner.Discovered> {
        val scanner = VictronScanner(getApplication())
        scanner.scan(seconds)
        return scanner.discovered().filter { it.mac.uppercase() !in exclude }
    }

    /**
     * Scan for nearby BLE devices for the BMS picker (likely-BMS flagged first).
     * MACs in [exclude] (already-configured devices) are filtered out.
     */
    suspend fun discoverBms(exclude: Set<String> = emptySet(), seconds: Int = 8): List<BmsScanner.Discovered> =
        BmsScanner(getApplication()).scan(seconds).filter { it.mac.uppercase() !in exclude }

    /** MACs of all configured devices (uppercased, blanks dropped). */
    fun configuredMacs(): Set<String> =
        _devices.value.mapNotNull { it.mac.takeIf(String::isNotBlank)?.uppercase() }.toSet()

    // ── Database maintenance ──────────────────────────────────────────────────

    fun dbStats(): HistoryStore.Stats = historyStore.stats()

    /** Delete all history and clear the in-memory charts. Returns rows removed. */
    fun pruneAllHistory(): Int {
        val n = historyStore.pruneAll()
        MonitorState.clearHistory()
        return n
    }

    /** Delete history within an inclusive date window (YYYY-MM-DD or ISO). */
    fun pruneHistoryRange(start: String?, end: String?): Int =
        historyStore.pruneRange(start, end)

    // ── Low-battery alerts ────────────────────────────────────────────────────

    /** Current alert configuration (decrypted from the encrypted store). */
    fun loadAlertConfig(): AlertConfig = alertStore.load()

    /** Persist alert configuration. Re-arms so a new threshold can fire again. */
    fun saveAlertConfig(config: AlertConfig) {
        alertStore.save(config)
        alertStore.setArmed(true)
    }

    /** Send a test alert over the enabled channels; returns a per-channel summary. */
    suspend fun sendTestAlert(config: AlertConfig): String =
        withContext(Dispatchers.IO) {
            AlertDispatcher.sendTest(getApplication(), config).summary()
        }
}
