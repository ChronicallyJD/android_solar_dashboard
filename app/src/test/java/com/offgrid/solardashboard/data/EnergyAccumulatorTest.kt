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
}
