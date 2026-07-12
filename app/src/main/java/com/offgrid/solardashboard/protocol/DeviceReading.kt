package com.offgrid.solardashboard.protocol

/**
 * Normalised reading from any monitored device (BMS or Victron).
 * Direct port of solar_monitor/models.py DeviceReading.
 *
 * deviceType values:
 *   "bms"      - JBD / Vatrer battery pack
 *   "mppt"     - Victron Solar Charger
 *   "inverter" - Victron Inverter (Phoenix, MultiPlus, etc.)
 *   "monitor"  - Victron Battery Monitor (SmartShunt, BMV)
 *   "dcdc"     - Victron DC-DC Converter / Orion
 *   "lithium"  - Victron SmartLithium
 *   "meter"    - Victron DC Energy Meter
 *   "victron"  - Unrecognised Victron record type
 */
data class DeviceReading(
    val address: String,
    val name: String,
    var deviceType: String,
    val timestamp: String,

    // Electrical fundamentals
    var voltageV: Double? = null,
    var currentA: Double? = null,
    var powerW: Double? = null,

    // BMS / Battery Monitor fields
    var capacityPct: Int? = null,
    var cellCount: Int? = null,
    var tempC: List<Double> = emptyList(),
    var ttgMinutes: Int? = null,
    var alarmReason: String? = null,
    var alarmBits: Int? = null,
    var remainAh: Double? = null,
    var nominalAh: Double? = null,
    var remainWh: Double? = null,
    var nominalWh: Double? = null,
    var timeToEmptyH: Double? = null,
    var timeToFullH: Double? = null,
    var cycleCount: Int? = null,
    var swVersion: String? = null,
    var productionDate: String? = null,
    var balanceCells: List<Int>? = null,
    var protectionBits: Int? = null,
    var faults: List<String>? = null,
    var chargeFet: Boolean? = null,
    var dischargeFet: Boolean? = null,

    // Solar Charger (MPPT) fields
    var pvPowerW: Double? = null,
    var yieldTodayWh: Double? = null,
    var loadCurrentA: Double? = null,
    var chargerState: String? = null,

    // Inverter fields
    var acOutPowerVa: Double? = null,
    var acOutVoltageV: Double? = null,
    var acOutCurrentA: Double? = null,
    var inverterState: String? = null,

    // VE.Bus Smart Dongle fields
    var acInPowerW: Double? = null,
    var acInSource: String? = null,
    var vebusError: Int? = null,
    var temperatureC: Double? = null,

    // MultiPlus-II 0x07 record diagnostic
    var rawLoadIndicator: Int? = null,

    // Shared error / diagnostic
    var errorCode: Int? = null,
    var error: String? = null,
)
