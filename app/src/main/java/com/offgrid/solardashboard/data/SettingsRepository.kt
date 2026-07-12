package com.offgrid.solardashboard.data

import android.content.Context
import androidx.core.content.edit

/** Persists device list + app settings in SharedPreferences (JSON). */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("solar_prefs", Context.MODE_PRIVATE)

    fun loadDevices(): List<DeviceConfig> =
        DeviceConfig.listFromJson(prefs.getString(KEY_DEVICES, "") ?: "")

    fun saveDevices(devices: List<DeviceConfig>) {
        prefs.edit { putString(KEY_DEVICES, DeviceConfig.listToJson(devices)) }
    }

    fun loadSettings(): AppSettings = AppSettings(
        bmsIntervalSec = prefs.getInt(KEY_BMS_INT, 120),
        victronIntervalSec = prefs.getInt(KEY_VIC_INT, 30),
        scanTimeoutSec = prefs.getInt(KEY_SCAN, 10),
        maxHistory = prefs.getInt(KEY_MAXHIST, 600),
        theme = prefs.getString(KEY_THEME, "dark") ?: "dark",
        historyEnabled = prefs.getBoolean(KEY_HIST_EN, true),
        retentionDays = prefs.getInt(KEY_RETENTION, 1095),
        welcomeSeen = prefs.getBoolean(KEY_WELCOME, false),
        electricityRateCents = prefs.getFloat(KEY_RATE, Tariff.DEFAULT_CENTS_PER_KWH.toFloat()).toDouble(),
    )

    fun saveSettings(s: AppSettings) {
        prefs.edit {
            putInt(KEY_BMS_INT, s.bmsIntervalSec)
            putInt(KEY_VIC_INT, s.victronIntervalSec)
            putInt(KEY_SCAN, s.scanTimeoutSec)
            putInt(KEY_MAXHIST, s.maxHistory)
            putString(KEY_THEME, s.theme)
            putBoolean(KEY_HIST_EN, s.historyEnabled)
            putInt(KEY_RETENTION, s.retentionDays)
            putBoolean(KEY_WELCOME, s.welcomeSeen)
            putFloat(KEY_RATE, s.electricityRateCents.toFloat())
        }
    }

    companion object {
        private const val KEY_DEVICES = "devices"
        private const val KEY_BMS_INT = "bms_interval"
        private const val KEY_VIC_INT = "victron_interval"
        private const val KEY_SCAN = "scan_timeout"
        private const val KEY_MAXHIST = "max_history"
        private const val KEY_THEME = "theme"
        private const val KEY_HIST_EN = "history_enabled"
        private const val KEY_RETENTION = "retention_days"
        private const val KEY_WELCOME = "welcome_seen"
        private const val KEY_RATE = "electricity_rate_cents"
    }
}
