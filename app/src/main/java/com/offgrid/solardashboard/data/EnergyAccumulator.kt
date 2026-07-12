package com.offgrid.solardashboard.data

/**
 * Pure logic for the running daily load-energy total that backs the "$ Saved"
 * estimate. Kept separate from the service and its clock/storage so the
 * accumulation and midnight-reset behavior is unit-testable.
 *
 * Energy for an interval is approximated as current load power times the time
 * elapsed since the previous sample (a right Riemann sum over the poll samples).
 */
object EnergyAccumulator {

    /** date = local day (YYYY-MM-DD), wh = energy so far today, lastMillis = last sample time. */
    data class State(val date: String, val wh: Double, val lastMillis: Long)

    /**
     * Seed today's total from storage on startup. Keeps the stored value if it is
     * from [today]; otherwise starts a fresh day at zero. [nowMillis] becomes the
     * baseline for the next interval.
     */
    fun seed(storedDate: String?, storedWh: Double, today: String, nowMillis: Long): State =
        if (storedDate == today) State(today, storedWh, nowMillis)
        else State(today, 0.0, nowMillis)

    /**
     * Add the load energy for the interval since [State.lastMillis]:
     * [loadW] times the elapsed hours. Resets to zero on a date change (midnight)
     * without crediting the slice that straddled midnight. A non-positive elapsed
     * (clock moved back) contributes nothing.
     */
    fun accumulate(prev: State, nowMillis: Long, today: String, loadW: Double): State {
        if (prev.date != today) return State(today, 0.0, nowMillis)
        val elapsedH = (nowMillis - prev.lastMillis).coerceAtLeast(0L) / 3_600_000.0
        return State(prev.date, prev.wh + loadW * elapsedH, nowMillis)
    }
}
