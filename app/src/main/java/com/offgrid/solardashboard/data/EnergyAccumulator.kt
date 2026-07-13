package com.offgrid.solardashboard.data

/**
 * Pure logic for the load-energy totals that back the "$ Saved" estimate. Kept
 * separate from the service and its clock/storage so the accumulation, the
 * midnight reset, and the lifetime/projection math are unit-testable.
 *
 * Energy for an interval is approximated as current load power times the time
 * elapsed since the previous sample (a right Riemann sum over the poll samples).
 *
 * Three views of the same slices are maintained:
 *  - [State.wh]: energy so far today, reset at local midnight (the "today" line).
 *  - [State.lifetimeWh]: a running total that never resets (the headline).
 *  - [State.recentDays]: the last [RECENT_WINDOW] completed-day totals, used to
 *    project an annual figure ("~$X/year at this rate").
 */
object EnergyAccumulator {

    /** How many completed days feed the annual projection. */
    const val RECENT_WINDOW = 30

    /**
     * @property date        current local day (YYYY-MM-DD)
     * @property wh          energy so far today (resets at midnight)
     * @property lastMillis  last sample time, the baseline for the next interval
     * @property lifetimeWh  running total that never resets
     * @property firstDate   first day observed, for "saved since <date>"
     * @property recentDays  completed-day Wh totals, oldest first, capped at [RECENT_WINDOW]
     */
    data class State(
        val date: String,
        val wh: Double,
        val lastMillis: Long,
        val lifetimeWh: Double = 0.0,
        val firstDate: String = date,
        val recentDays: List<Double> = emptyList(),
    )

    /**
     * Seed the totals from storage on startup. Today's total is kept if the stored
     * value is from [today], otherwise it starts fresh at zero; a stored total from
     * an earlier day (the app was off across midnight) is folded into [recentDays]
     * so the projection still reflects it. The lifetime total and first-seen date
     * carry across unchanged. [nowMillis] becomes the baseline for the next interval.
     */
    fun seed(
        storedDate: String?,
        storedWh: Double,
        today: String,
        nowMillis: Long,
        storedLifetimeWh: Double = 0.0,
        storedFirstDate: String? = null,
        storedRecentDays: List<Double> = emptyList(),
    ): State {
        val sameDay = storedDate == today
        val recent =
            if (!sameDay && storedDate != null && storedWh > 0.0)
                (storedRecentDays + storedWh).takeLast(RECENT_WINDOW)
            else storedRecentDays
        return State(
            date = today,
            wh = if (sameDay) storedWh else 0.0,
            lastMillis = nowMillis,
            lifetimeWh = storedLifetimeWh,
            firstDate = storedFirstDate ?: today,
            recentDays = recent,
        )
    }

    /**
     * Add the load energy for the interval since [State.lastMillis]: [loadW] times
     * the elapsed hours. The slice always feeds [State.lifetimeWh]. On a date change
     * (midnight) today's total resets to zero and the just-completed day is snapshot
     * into [State.recentDays]; the straddling slice is not credited to either day's
     * running total (but still counts toward the lifetime). A non-positive elapsed
     * (clock moved back) contributes nothing.
     */
    fun accumulate(prev: State, nowMillis: Long, today: String, loadW: Double): State {
        val elapsedH = (nowMillis - prev.lastMillis).coerceAtLeast(0L) / 3_600_000.0
        val slice = loadW * elapsedH
        val newLifetime = prev.lifetimeWh + slice
        return if (prev.date != today) {
            val recent =
                if (prev.wh > 0.0) (prev.recentDays + prev.wh).takeLast(RECENT_WINDOW)
                else prev.recentDays
            State(today, 0.0, nowMillis, newLifetime, prev.firstDate, recent)
        } else {
            State(prev.date, prev.wh + slice, nowMillis, newLifetime, prev.firstDate, prev.recentDays)
        }
    }

    /**
     * One-time merge of totals reconstructed from stored history into [state], for
     * the first launch after upgrading so existing installs start with real numbers
     * instead of zero. The lifetime total takes the larger of the carried and the
     * reconstructed value: a real upgrade carries zero so history wins, while a
     * legitimately larger carried total (retention pruned the history) is kept, so
     * the lifetime never regresses below today's total. The recent-day window is
     * filled only when still empty, and the first-seen date is pulled back to the
     * earliest of the two. [historyRecentDays] should already exclude today's
     * partial day.
     */
    fun backfill(
        state: State,
        historyLifetimeWh: Double,
        historyFirstDate: String?,
        historyRecentDays: List<Double>,
    ): State {
        val lifetimeWh = maxOf(state.lifetimeWh, historyLifetimeWh)
        val recentDays =
            if (state.recentDays.isNotEmpty()) state.recentDays
            else historyRecentDays.takeLast(RECENT_WINDOW)
        val firstDate =
            if (historyFirstDate != null && historyFirstDate < state.firstDate) historyFirstDate
            else state.firstDate
        return state.copy(lifetimeWh = lifetimeWh, recentDays = recentDays, firstDate = firstDate)
    }

    /**
     * Projected annual load energy (Wh) from the average of the completed days in
     * [State.recentDays], or null until at least one full day has been recorded.
     * The caller prices this at the electricity rate for the "~$X/year" figure.
     */
    fun projectedAnnualWh(state: State): Double? =
        if (state.recentDays.isEmpty()) null
        else state.recentDays.average() * 365.0
}
