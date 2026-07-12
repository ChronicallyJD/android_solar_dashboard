package com.offgrid.solardashboard.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One configured device. Mirrors solar_monitor/config.py DeviceConfig plus the
 * transport kind (bms vs victron) so the poller knows how to read it.
 */
data class DeviceConfig(
    val kind: String,                 // "bms" or "victron"
    val name: String,
    val mac: String,                  // AA:BB:CC:DD:EE:FF (upper, colon form)
    val bleName: String? = null,      // optional BLE advertised name (BMS discovery)
    val encKey: String? = null,       // Victron 32-hex advertisement key
    val password: String? = null,     // JBD BMS password (default "0000")
    val deviceType: String? = null,   // Victron override: mppt|inverter|monitor|dcdc|lithium|meter
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("name", name)
        put("mac", mac)
        putOpt("bleName", bleName)
        putOpt("encKey", encKey)
        putOpt("password", password)
        putOpt("deviceType", deviceType)
    }

    companion object {
        fun fromJson(o: JSONObject) = DeviceConfig(
            kind = o.getString("kind"),
            name = o.getString("name"),
            mac = o.getString("mac"),
            bleName = o.optString("bleName", null.toString()).takeIf { o.has("bleName") && !o.isNull("bleName") },
            encKey = o.optStringOrNull("encKey"),
            password = o.optStringOrNull("password"),
            deviceType = o.optStringOrNull("deviceType"),
        )

        fun normaliseMac(raw: String): String {
            val cleaned = raw.trim().replace("-", "").replace(":", "").uppercase()
            return if (cleaned.length == 12 && cleaned.all { it.isDigit() || it in 'A'..'F' }) {
                cleaned.chunked(2).joinToString(":")
            } else raw.trim().uppercase()
        }

        /**
         * Reduce a pasted advertisement key to its 32 hex characters. Tolerates
         * spaces, dashes and colons, and a "MAC:key" paste (the MAC prefix is
         * dropped by keeping the trailing 32 hex digits). Returns null if blank.
         */
        fun normaliseKey(raw: String?): String? {
            if (raw == null) return null
            val hex = raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.lowercase()
            if (hex.isEmpty()) return null
            return if (hex.length >= 32) hex.takeLast(32) else hex
        }

        fun listToJson(list: List<DeviceConfig>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(s: String): List<DeviceConfig> {
            if (s.isBlank()) return emptyList()
            val arr = JSONArray(s)
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.putOpt(key: String, v: String?) {
    if (v != null) put(key, v)
}

/** App-wide settings, mirroring the [general] section defaults. */
data class AppSettings(
    val bmsIntervalSec: Int = 120,
    val victronIntervalSec: Int = 30,
    val scanTimeoutSec: Int = 10,
    val maxHistory: Int = 600,
    val theme: String = "dark",       // dark | light | business
    val historyEnabled: Boolean = true,
    val retentionDays: Int = 1095,
    val welcomeSeen: Boolean = false, // first-run welcome dismissed
    val electricityRateCents: Double = Tariff.DEFAULT_CENTS_PER_KWH, // cents per kWh for $ Saved
)
