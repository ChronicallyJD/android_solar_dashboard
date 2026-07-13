package com.offgrid.solardashboard.data

import com.offgrid.solardashboard.protocol.DeviceReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide live snapshot of the latest reading per device plus a rolling
 * in-memory history window. Analogous to the Python state.json + in-memory
 * history list, but shared directly between the polling service and the UI.
 */
object MonitorState {

    data class Snapshot(
        val readings: List<DeviceReading> = emptyList(),
        val lastUpdated: String? = null,
        val scanning: Boolean = false,
        // Energy delivered to loads (Wh); backs the "$ Saved" figure. Today resets
        // at midnight; lifetime never resets; projectedAnnual is null until a full
        // day has been recorded. firstDate is the first day observed.
        val loadEnergyTodayWh: Double = 0.0,
        val loadEnergyLifetimeWh: Double = 0.0,
        val loadEnergyProjectedAnnualWh: Double? = null,
        val loadEnergyFirstDate: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    // Per-device rolling history of chart points, trimmed to maxHistory.
    data class HistPoint(
        val timestamp: String,
        val voltageV: Double?,
        val currentA: Double?,
        val pvPowerW: Double?,
        val capacityPct: Int?,
    )

    private val history = LinkedHashMap<String, MutableList<HistPoint>>()
    private val _historyFlow = MutableStateFlow<Map<String, List<HistPoint>>>(emptyMap())
    val historyFlow: StateFlow<Map<String, List<HistPoint>>> = _historyFlow.asStateFlow()

    /**
     * Merge fresh readings into the snapshot (keyed by address, so a slow device
     * keeps its previous value) and append successful ones to the rolling chart
     * history, trimmed to [maxHistory] points per device.
     */
    @Synchronized
    fun update(readings: List<DeviceReading>, maxHistory: Int) {
        // Merge: keep last known reading per address, replace with fresh ones.
        val byAddr = LinkedHashMap<String, DeviceReading>()
        _state.value.readings.forEach { byAddr[it.address] = it }
        readings.forEach { byAddr[it.address] = it }

        val ts = readings.maxByOrNull { it.timestamp }?.timestamp ?: _state.value.lastUpdated
        _state.value = _state.value.copy(readings = byAddr.values.toList(), lastUpdated = ts)

        for (r in readings) {
            if (r.error != null) continue
            val list = history.getOrPut(r.name) { mutableListOf() }
            list.add(HistPoint(r.timestamp, r.voltageV, r.currentA, r.pvPowerW, r.capacityPct))
            while (list.size > maxHistory) list.removeAt(0)
        }
        _historyFlow.value = history.mapValues { it.value.toList() }
    }

    /** Flag whether a BLE scan is in progress, for the "Scanning…" status. */
    @Synchronized
    fun setScanning(scanning: Boolean) {
        _state.value = _state.value.copy(scanning = scanning)
    }

    /** Publish the load-energy totals (Wh) for the "$ Saved" estimate. */
    @Synchronized
    fun setLoadEnergy(todayWh: Double, lifetimeWh: Double, projectedAnnualWh: Double?, firstDate: String?) {
        _state.value = _state.value.copy(
            loadEnergyTodayWh = todayWh,
            loadEnergyLifetimeWh = lifetimeWh,
            loadEnergyProjectedAnnualWh = projectedAnnualWh,
            loadEnergyFirstDate = firstDate,
        )
    }

    /** Seed a device's chart history from the database on launch. */
    @Synchronized
    fun seedHistory(name: String, points: List<HistPoint>) {
        history[name] = points.toMutableList()
        _historyFlow.value = history.mapValues { it.value.toList() }
    }

    /** Drop all in-memory chart history (used after clearing the database). */
    @Synchronized
    fun clearHistory() {
        history.clear()
        _historyFlow.value = emptyMap()
    }
}
