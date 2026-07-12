package com.offgrid.solardashboard.data

/** Electricity-cost helpers for the "$ Saved" estimate. */
object Tariff {

    /** United States national average residential rate, cents per kWh. */
    const val DEFAULT_CENTS_PER_KWH = 18.8

    /**
     * Dollar value of [loadWh] watt-hours at [centsPerKwh] cents per kilowatt-hour.
     * 1000 Wh = 1 kWh, and 100 cents = 1 dollar, so the divisor is 100000.
     */
    fun savingsDollars(loadWh: Double, centsPerKwh: Double): Double =
        loadWh * centsPerKwh / 100_000.0
}
