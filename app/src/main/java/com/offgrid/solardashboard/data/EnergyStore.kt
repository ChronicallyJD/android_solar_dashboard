package com.offgrid.solardashboard.data

import android.content.Context
import androidx.core.content.edit

/**
 * Persists the load-energy totals that back the "$ Saved" estimate so they
 * survive app/service restarts: the current day's running total (reset at local
 * midnight by comparing the stored date to today), the never-resetting lifetime
 * total, the first-seen date, and the recent completed-day totals used for the
 * annual projection.
 */
class EnergyStore(context: Context) {

    private val prefs = context.getSharedPreferences("energy_prefs", Context.MODE_PRIVATE)

    /** Everything restored on startup; fields default to a fresh state for new installs. */
    data class Stored(
        val date: String?,
        val wh: Double,
        val lifetimeWh: Double,
        val firstDate: String?,
        val recentDays: List<Double>,
        /** Whether the one-time history backfill has already run (see EnergyAccumulator.backfill). */
        val backfilled: Boolean,
    )

    fun load(): Stored = Stored(
        date = prefs.getString(KEY_DATE, null),
        wh = prefs.getFloat(KEY_WH, 0f).toDouble(),
        // Lifetime is stored as a String to keep full double precision as it grows.
        lifetimeWh = prefs.getString(KEY_LIFETIME_WH, null)?.toDoubleOrNull() ?: 0.0,
        firstDate = prefs.getString(KEY_FIRST_DATE, null),
        recentDays = prefs.getString(KEY_RECENT_DAYS, "").orEmpty()
            .split(',').mapNotNull { it.toDoubleOrNull() },
        backfilled = prefs.getBoolean(KEY_BACKFILLED, false),
    )

    /** Record that the one-time history backfill has run, so it never repeats. */
    fun markBackfilled() {
        prefs.edit { putBoolean(KEY_BACKFILLED, true) }
    }

    fun save(state: EnergyAccumulator.State) {
        prefs.edit {
            putString(KEY_DATE, state.date)
            putFloat(KEY_WH, state.wh.toFloat())
            putString(KEY_LIFETIME_WH, state.lifetimeWh.toString())
            putString(KEY_FIRST_DATE, state.firstDate)
            putString(KEY_RECENT_DAYS, state.recentDays.joinToString(","))
        }
    }

    companion object {
        private const val KEY_DATE = "load_date"
        private const val KEY_WH = "load_wh"
        private const val KEY_LIFETIME_WH = "lifetime_wh"
        private const val KEY_FIRST_DATE = "first_date"
        private const val KEY_RECENT_DAYS = "recent_days"
        private const val KEY_BACKFILLED = "backfilled"
    }
}
