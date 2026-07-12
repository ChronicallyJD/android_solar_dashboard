package com.offgrid.solardashboard.data

import android.content.Context
import androidx.core.content.edit

/**
 * Persists the running total of energy delivered to loads for the current day.
 * This backs the "$ Saved" estimate: it survives app/service restarts and is
 * reset at local midnight (the caller compares the stored date to today).
 */
class EnergyStore(context: Context) {

    private val prefs = context.getSharedPreferences("energy_prefs", Context.MODE_PRIVATE)

    /** (stored date as YYYY-MM-DD or null if never written, watt-hours so far). */
    fun load(): Pair<String?, Double> =
        prefs.getString(KEY_DATE, null) to prefs.getFloat(KEY_WH, 0f).toDouble()

    fun save(date: String, wh: Double) {
        prefs.edit {
            putString(KEY_DATE, date)
            putFloat(KEY_WH, wh.toFloat())
        }
    }

    companion object {
        private const val KEY_DATE = "load_date"
        private const val KEY_WH = "load_wh"
    }
}
