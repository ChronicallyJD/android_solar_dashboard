package com.offgrid.solardashboard.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the daily load-energy accumulation and midnight reset. */
class EnergyAccumulatorTest {

    private val hourMs = 3_600_000L

    @Test fun seedKeepsStoredWhenSameDay() {
        val s = EnergyAccumulator.seed("2026-07-12", 123.4, "2026-07-12", nowMillis = 1000)
        assertEquals("2026-07-12", s.date)
        assertEquals(123.4, s.wh, 1e-9)
        assertEquals(1000, s.lastMillis)
    }

    @Test fun seedResetsOnNewDay() {
        val s = EnergyAccumulator.seed("2026-07-11", 500.0, "2026-07-12", nowMillis = 1000)
        assertEquals("2026-07-12", s.date)
        assertEquals(0.0, s.wh, 1e-9)
    }

    @Test fun seedResetsWhenNothingStored() {
        val s = EnergyAccumulator.seed(null, 0.0, "2026-07-12", nowMillis = 42)
        assertEquals(0.0, s.wh, 1e-9)
        assertEquals("2026-07-12", s.date)
    }

    @Test fun accumulateAddsPowerTimesElapsedHours() {
        val start = EnergyAccumulator.State("2026-07-12", 0.0, 0L)
        // 1200 W for one hour = 1200 Wh.
        val after = EnergyAccumulator.accumulate(start, hourMs, "2026-07-12", loadW = 1200.0)
        assertEquals(1200.0, after.wh, 1e-6)
        assertEquals(hourMs, after.lastMillis)
    }

    @Test fun accumulateHalfHour() {
        val start = EnergyAccumulator.State("2026-07-12", 100.0, 0L)
        val after = EnergyAccumulator.accumulate(start, hourMs / 2, "2026-07-12", loadW = 1000.0)
        assertEquals(100.0 + 500.0, after.wh, 1e-6)
    }

    @Test fun accumulateResetsAtMidnight() {
        val start = EnergyAccumulator.State("2026-07-12", 800.0, 0L)
        val after = EnergyAccumulator.accumulate(start, hourMs, "2026-07-13", loadW = 1000.0)
        // Date changed: reset to zero, do not credit the straddling slice.
        assertEquals(0.0, after.wh, 1e-9)
        assertEquals("2026-07-13", after.date)
        assertEquals(hourMs, after.lastMillis)
    }

    @Test fun accumulateIgnoresBackwardClock() {
        val start = EnergyAccumulator.State("2026-07-12", 50.0, 10 * hourMs)
        // now is earlier than lastMillis: no negative energy.
        val after = EnergyAccumulator.accumulate(start, 5 * hourMs, "2026-07-12", loadW = 1000.0)
        assertEquals(50.0, after.wh, 1e-9)
    }

    @Test fun accumulateZeroLoadAddsNothing() {
        val start = EnergyAccumulator.State("2026-07-12", 250.0, 0L)
        val after = EnergyAccumulator.accumulate(start, 3 * hourMs, "2026-07-12", loadW = 0.0)
        assertEquals(250.0, after.wh, 1e-9)
    }

    @Test fun sequenceOfSamplesIntegrates() {
        var s = EnergyAccumulator.State("2026-07-12", 0.0, 0L)
        // 1000 W for 1h, then 2000 W for 1h => 3000 Wh.
        s = EnergyAccumulator.accumulate(s, hourMs, "2026-07-12", 1000.0)
        s = EnergyAccumulator.accumulate(s, 2 * hourMs, "2026-07-12", 2000.0)
        assertEquals(3000.0, s.wh, 1e-6)
    }

    @Test fun lifetimeAccumulatesAcrossDays() {
        var s = EnergyAccumulator.State("2026-07-12", 0.0, 0L)
        s = EnergyAccumulator.accumulate(s, hourMs, "2026-07-12", 1000.0)      // +1000 Wh
        // Cross midnight: today resets but lifetime keeps the straddling slice.
        s = EnergyAccumulator.accumulate(s, 2 * hourMs, "2026-07-13", 500.0)   // +500 Wh
        s = EnergyAccumulator.accumulate(s, 3 * hourMs, "2026-07-13", 200.0)   // +200 Wh
        assertEquals(0.0 + 200.0, s.wh, 1e-6)
        assertEquals(1700.0, s.lifetimeWh, 1e-6)
    }

    @Test fun midnightRolloverSnapshotsCompletedDay() {
        val start = EnergyAccumulator.State("2026-07-12", 800.0, 0L, firstDate = "2026-07-12")
        val after = EnergyAccumulator.accumulate(start, hourMs, "2026-07-13", loadW = 1000.0)
        assertEquals(listOf(800.0), after.recentDays)
        assertEquals("2026-07-12", after.firstDate)
    }

    @Test fun rolloverSkipsEmptyDay() {
        val start = EnergyAccumulator.State("2026-07-12", 0.0, 0L)
        val after = EnergyAccumulator.accumulate(start, hourMs, "2026-07-13", loadW = 0.0)
        assertEquals(emptyList<Double>(), after.recentDays)
    }

    @Test fun recentDaysCappedToWindow() {
        var s = EnergyAccumulator.State("2026-01-01", 100.0, 0L, firstDate = "2026-01-01")
        // Roll over more days than the window; oldest snapshots drop off.
        for (d in 2..(EnergyAccumulator.RECENT_WINDOW + 5)) {
            s = EnergyAccumulator.accumulate(s, 0L, "day-$d", loadW = 0.0).copy(wh = 100.0)
        }
        assertEquals(EnergyAccumulator.RECENT_WINDOW, s.recentDays.size)
    }

    @Test fun projectionIsNullBeforeFirstFullDay() {
        val s = EnergyAccumulator.State("2026-07-12", 300.0, 0L)
        assertEquals(null, EnergyAccumulator.projectedAnnualWh(s))
    }

    @Test fun projectionAveragesRecentDays() {
        // Two completed days of 1000 and 2000 Wh average 1500 Wh/day => 547,500 Wh/yr.
        val s = EnergyAccumulator.State("2026-07-14", 0.0, 0L, recentDays = listOf(1000.0, 2000.0))
        assertEquals(1500.0 * 365.0, EnergyAccumulator.projectedAnnualWh(s)!!, 1e-6)
    }

    @Test fun seedFoldsPreviousDayIntoRecentDays() {
        // App was off across midnight: yesterday's stored total joins the window.
        val s = EnergyAccumulator.seed(
            storedDate = "2026-07-12", storedWh = 900.0,
            today = "2026-07-13", nowMillis = 1000,
            storedLifetimeWh = 5000.0, storedFirstDate = "2026-06-01",
            storedRecentDays = listOf(700.0),
        )
        assertEquals(0.0, s.wh, 1e-9)
        assertEquals(5000.0, s.lifetimeWh, 1e-9)
        assertEquals("2026-06-01", s.firstDate)
        assertEquals(listOf(700.0, 900.0), s.recentDays)
    }

    @Test fun seedSameDayKeepsWindowAndFirstDate() {
        val s = EnergyAccumulator.seed(
            storedDate = "2026-07-13", storedWh = 120.0,
            today = "2026-07-13", nowMillis = 1000,
            storedLifetimeWh = 42.0, storedFirstDate = "2026-06-01",
            storedRecentDays = listOf(700.0),
        )
        assertEquals(120.0, s.wh, 1e-9)
        assertEquals(listOf(700.0), s.recentDays)
        assertEquals("2026-06-01", s.firstDate)
    }

    @Test fun seedDefaultsFirstDateToTodayForNewInstall() {
        val s = EnergyAccumulator.seed(null, 0.0, "2026-07-13", nowMillis = 1)
        assertEquals("2026-07-13", s.firstDate)
        assertEquals(0.0, s.lifetimeWh, 1e-9)
        assertEquals(emptyList<Double>(), s.recentDays)
    }

    @Test fun backfillSeedsEmptyStateFromHistory() {
        val fresh = EnergyAccumulator.seed(null, 0.0, "2026-07-13", nowMillis = 1)
        val s = EnergyAccumulator.backfill(
            fresh, historyLifetimeWh = 5000.0,
            historyFirstDate = "2026-06-01", historyRecentDays = listOf(900.0, 1100.0),
        )
        assertEquals(5000.0, s.lifetimeWh, 1e-9)
        assertEquals("2026-06-01", s.firstDate)
        assertEquals(listOf(900.0, 1100.0), s.recentDays)
    }

    @Test fun backfillTakesLargerLifetimeFromHistory() {
        val existing = EnergyAccumulator.State(
            "2026-07-13", 10.0, 0L, lifetimeWh = 200.0,
            firstDate = "2026-07-01", recentDays = listOf(50.0),
        )
        val s = EnergyAccumulator.backfill(
            existing, historyLifetimeWh = 9999.0,
            historyFirstDate = "2026-05-01", historyRecentDays = listOf(1.0, 2.0),
        )
        // History reconstructs a larger total than the tiny carried value: prefer it,
        // so lifetime never sits below today's total. Window kept; first date pulled back.
        assertEquals(9999.0, s.lifetimeWh, 1e-9)
        assertEquals(listOf(50.0), s.recentDays)
        assertEquals("2026-05-01", s.firstDate)
    }

    @Test fun backfillKeepsLargerCarriedLifetime() {
        // Retention pruned the history, so the carried lifetime is the more complete
        // value: keep it rather than regressing to the smaller reconstruction.
        val existing = EnergyAccumulator.State("2026-07-13", 0.0, 0L, lifetimeWh = 9999.0)
        val s = EnergyAccumulator.backfill(existing, 100.0, "2026-07-10", listOf(5.0))
        assertEquals(9999.0, s.lifetimeWh, 1e-9)
    }

    @Test fun backfillCapsRecentDaysToWindow() {
        val fresh = EnergyAccumulator.seed(null, 0.0, "2026-07-13", nowMillis = 1)
        val many = (1..(EnergyAccumulator.RECENT_WINDOW + 10)).map { it.toDouble() }
        val s = EnergyAccumulator.backfill(fresh, 100.0, "2026-01-01", many)
        assertEquals(EnergyAccumulator.RECENT_WINDOW, s.recentDays.size)
        // Keeps the most recent days (largest values here).
        assertEquals(many.takeLast(EnergyAccumulator.RECENT_WINDOW), s.recentDays)
    }
}
