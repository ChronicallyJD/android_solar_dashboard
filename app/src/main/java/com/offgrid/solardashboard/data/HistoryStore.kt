package com.offgrid.solardashboard.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.offgrid.solardashboard.protocol.DeviceReading

/**
 * SQLite long-term store. Android port of solar_monitor/history.py.
 * Only successful readings are persisted; retention is enforced lazily.
 * Chart fields are stored per row; no downsampling (matches Python behaviour).
 */
class HistoryStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE readings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                recorded_at TEXT NOT NULL,
                device_name TEXT NOT NULL,
                device_type TEXT,
                address TEXT,
                voltage_v REAL,
                current_a REAL,
                power_w REAL,
                capacity_pct INTEGER,
                pv_power_w REAL,
                yield_today_wh REAL,
                ac_out_power_va REAL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_recorded_at ON readings(recorded_at)")
        db.execSQL("CREATE INDEX idx_device_name ON readings(device_name)")
        db.execSQL("CREATE INDEX idx_dev_time ON readings(device_name, recorded_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS readings")
        onCreate(db)
    }

    fun writeReadings(readings: List<DeviceReading>, retentionDays: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in readings) {
                if (r.error != null) continue   // only successful readings
                val cv = ContentValues().apply {
                    put("recorded_at", r.timestamp)
                    put("device_name", r.name)
                    put("device_type", r.deviceType)
                    put("address", r.address)
                    put("voltage_v", r.voltageV)
                    put("current_a", r.currentA)
                    put("power_w", r.powerW)
                    put("capacity_pct", r.capacityPct)
                    put("pv_power_w", r.pvPowerW)
                    put("yield_today_wh", r.yieldTodayWh)
                    put("ac_out_power_va", r.acOutPowerVa)
                }
                db.insert("readings", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (retentionDays > 0) enforceRetention(retentionDays)
    }

    private fun enforceRetention(retentionDays: Int) {
        // SQLite datetime math; keep rows newer than now - retentionDays.
        writableDatabase.execSQL(
            "DELETE FROM readings WHERE recorded_at < datetime('now', ?)",
            arrayOf("-$retentionDays days"),
        )
    }

    data class Point(val timestamp: String, val value: Double)

    /** Last [limit] values of [field] for [deviceName], oldest first. */
    fun series(deviceName: String, field: String, limit: Int): List<Point> {
        require(field in ALLOWED_FIELDS) { "field not allowed: $field" }
        val out = ArrayList<Point>()
        readableDatabase.rawQuery(
            "SELECT recorded_at, $field FROM readings " +
                "WHERE device_name = ? AND $field IS NOT NULL " +
                "ORDER BY recorded_at DESC LIMIT ?",
            arrayOf(deviceName, limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(Point(c.getString(0), c.getDouble(1)))
        }
        return out.reversed()
    }

    fun deviceNames(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT device_name FROM readings ORDER BY device_name", null,
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /**
     * Most recent persisted reading per device, so the dashboard can show
     * last-known values immediately on launch, before the first scan.
     * Only the columns we persist are populated; [DeviceReading.error] stays
     * null so cards render as normal (values simply carry the old timestamp).
     */
    fun latestReadings(): List<DeviceReading> {
        val out = ArrayList<DeviceReading>()
        readableDatabase.rawQuery(
            "SELECT recorded_at, device_name, device_type, address, voltage_v, " +
                "current_a, power_w, capacity_pct, pv_power_w, yield_today_wh, ac_out_power_va " +
                "FROM readings WHERE id IN (SELECT MAX(id) FROM readings GROUP BY device_name)",
            null,
        ).use { c ->
            fun dbl(i: Int): Double? = if (c.isNull(i)) null else c.getDouble(i)
            while (c.moveToNext()) {
                out.add(
                    DeviceReading(
                        address = c.getString(3) ?: c.getString(1),
                        name = c.getString(1),
                        deviceType = c.getString(2) ?: "victron",
                        timestamp = c.getString(0),
                        voltageV = dbl(4),
                        currentA = dbl(5),
                        powerW = dbl(6),
                        capacityPct = if (c.isNull(7)) null else c.getInt(7),
                        pvPowerW = dbl(8),
                        yieldTodayWh = dbl(9),
                        acOutPowerVa = dbl(10),
                    )
                )
            }
        }
        return out
    }

    /**
     * Energy delivered to loads since [sinceIso], integrated from the stored
     * inverter AC-output samples (trapezoidal between consecutive samples,
     * summed across inverters at each timestamp). Because the samples are
     * persisted, this value survives a service restart, unlike an in-memory
     * accumulator. Intervals longer than [maxGapMs] are skipped, so a long
     * monitoring outage does not get credited as continuous load. Returns Wh.
     */
    fun loadEnergyTodayWh(sinceIso: String, maxGapMs: Long = 15 * 60 * 1000L): Double {
        val samples = ArrayList<Pair<Long, Double>>() // epoch millis, summed watts
        readableDatabase.rawQuery(
            "SELECT recorded_at, SUM(ac_out_power_va) FROM readings " +
                "WHERE device_type = 'inverter' AND ac_out_power_va IS NOT NULL " +
                "AND recorded_at >= ? GROUP BY recorded_at ORDER BY recorded_at",
            arrayOf(sinceIso),
        ).use { c ->
            while (c.moveToNext()) {
                val t = isoToMillis(c.getString(0)) ?: continue
                samples.add(t to c.getDouble(1))
            }
        }
        var wh = 0.0
        for (i in 1 until samples.size) {
            val dtMs = samples[i].first - samples[i - 1].first
            if (dtMs <= 0 || dtMs > maxGapMs) continue
            val avgW = (samples[i].second + samples[i - 1].second) / 2.0
            wh += avgW * (dtMs / 3_600_000.0)
        }
        return wh
    }

    private fun isoToMillis(s: String): Long? = try {
        java.time.LocalDateTime.parse(s)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    // ── Maintenance ─────────────────────────────────────────────────────────

    data class Stats(val rowCount: Int, val oldest: String?, val newest: String?)

    fun stats(): Stats {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), MIN(recorded_at), MAX(recorded_at) FROM readings", null,
        ).use { c ->
            if (c.moveToNext()) {
                return Stats(c.getInt(0), c.getString(1), c.getString(2))
            }
        }
        return Stats(0, null, null)
    }

    /** Delete all history. Returns the number of rows removed. */
    fun pruneAll(): Int {
        val n = stats().rowCount
        writableDatabase.execSQL("DELETE FROM readings")
        return n
    }

    /**
     * Delete rows recorded within the inclusive [start, end] window. Either
     * bound may be null for an open-ended range (e.g. everything before a date).
     * Dates are compared as ISO strings; a bare "YYYY-MM-DD" end date is bumped
     * to the end of that day so the whole day is included. Returns rows removed.
     */
    fun pruneRange(start: String?, end: String?): Int {
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        if (!start.isNullOrBlank()) { clauses.add("recorded_at >= ?"); args.add(start) }
        if (!end.isNullOrBlank()) {
            val bound = if (end.length == 10) "$end" + "T23:59:59" else end
            clauses.add("recorded_at <= ?"); args.add(bound)
        }
        val where = if (clauses.isEmpty()) "1=1" else clauses.joinToString(" AND ")
        val db = writableDatabase
        val n = db.rawQuery("SELECT COUNT(*) FROM readings WHERE $where", args.toTypedArray())
            .use { if (it.moveToNext()) it.getInt(0) else 0 }
        db.execSQL("DELETE FROM readings WHERE $where", args.toTypedArray())
        return n
    }

    companion object {
        private const val DB_NAME = "solar_history.db"
        private const val DB_VERSION = 1
        val ALLOWED_FIELDS = setOf(
            "voltage_v", "current_a", "power_w", "capacity_pct",
            "pv_power_w", "yield_today_wh", "ac_out_power_va",
        )
    }
}
