package com.offgrid.solardashboard.data

import androidx.test.core.app.ApplicationProvider
import com.offgrid.solardashboard.protocol.DeviceReading
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric tests for the SQLite-backed history store: writing, latest/series
 * reads, retention, and pruning. Uses a real (in-memory) Android SQLite database.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryStoreTest {

    private lateinit var store: HistoryStore

    @Before fun setUp() {
        store = HistoryStore(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() {
        store.close()
    }

    private fun reading(
        name: String, ts: String, soc: Int? = 80, v: Double? = 53.0,
        pv: Double? = null, error: String? = null,
    ) = DeviceReading(
        address = "AA:$name", name = name, deviceType = "bms", timestamp = ts,
        voltageV = v, currentA = 1.0, powerW = 53.0, capacityPct = soc, pvPowerW = pv,
    ).also { it.error = error }

    @Test fun writeAndLatestReadingPerDevice() {
        store.writeReadings(listOf(
            reading("Batt0", "2026-07-12T10:00:00", soc = 70),
            reading("Batt0", "2026-07-12T11:00:00", soc = 75),
            reading("Batt1", "2026-07-12T10:30:00", soc = 60),
        ), retentionDays = 0)

        val latest = store.latestReadings().associateBy { it.name }
        assertEquals(2, latest.size)
        assertEquals(75, latest["Batt0"]!!.capacityPct)  // most recent for Batt0
        assertEquals(60, latest["Batt1"]!!.capacityPct)
    }

    @Test fun errorReadingsAreNotStored() {
        store.writeReadings(listOf(
            reading("Batt0", "2026-07-12T10:00:00", error = "timeout"),
        ), retentionDays = 0)
        assertEquals(0, store.stats().rowCount)
        assertTrue(store.latestReadings().isEmpty())
    }

    @Test fun seriesReturnsOldestFirstLimited() {
        store.writeReadings(listOf(
            reading("Batt0", "2026-07-12T10:00:00", v = 51.0),
            reading("Batt0", "2026-07-12T11:00:00", v = 52.0),
            reading("Batt0", "2026-07-12T12:00:00", v = 53.0),
        ), retentionDays = 0)
        val series = store.series("Batt0", "voltage_v", limit = 2)
        // Latest 2 by time, returned oldest-first.
        assertEquals(2, series.size)
        assertEquals(52.0, series[0].value, 1e-9)
        assertEquals(53.0, series[1].value, 1e-9)
    }

    @Test fun statsReportsCountAndRange() {
        store.writeReadings(listOf(
            reading("Batt0", "2026-07-12T10:00:00"),
            reading("Batt0", "2026-07-12T12:00:00"),
        ), retentionDays = 0)
        val s = store.stats()
        assertEquals(2, s.rowCount)
        assertEquals("2026-07-12T10:00:00", s.oldest)
        assertEquals("2026-07-12T12:00:00", s.newest)
    }

    @Test fun pruneAllRemovesEverything() {
        store.writeReadings(listOf(
            reading("Batt0", "2026-07-12T10:00:00"),
            reading("Batt1", "2026-07-12T10:00:00"),
        ), retentionDays = 0)
        assertEquals(2, store.pruneAll())
        assertEquals(0, store.stats().rowCount)
    }

    @Test fun pruneRangeInclusiveDates() {
        store.writeReadings(listOf(
            reading("Batt0", "2026-07-10T10:00:00"),
            reading("Batt0", "2026-07-11T10:00:00"),
            reading("Batt0", "2026-07-12T10:00:00"),
        ), retentionDays = 0)
        // Delete 2026-07-11 only (end date bumped to end of day internally).
        val removed = store.pruneRange("2026-07-11", "2026-07-11")
        assertEquals(1, removed)
        assertEquals(2, store.stats().rowCount)
    }

    @Test fun pruneRangeOpenEnded() {
        store.writeReadings(listOf(
            reading("Batt0", "2026-07-10T10:00:00"),
            reading("Batt0", "2026-07-11T10:00:00"),
            reading("Batt0", "2026-07-12T10:00:00"),
        ), retentionDays = 0)
        // Everything on or before 2026-07-11.
        assertEquals(2, store.pruneRange(null, "2026-07-11"))
        assertEquals(1, store.stats().rowCount)
    }

    @Test fun retentionDeletesOldRows() {
        // One ancient row and one recent row; 1-day retention keeps only recent.
        val recent = java.time.LocalDateTime.now().minusHours(1)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        store.writeReadings(listOf(
            reading("Batt0", "2000-01-01T00:00:00"),
            reading("Batt0", recent),
        ), retentionDays = 1)
        assertEquals(1, store.stats().rowCount)
    }

    @Test fun deviceNamesDistinctSorted() {
        store.writeReadings(listOf(
            reading("Zeta", "2026-07-12T10:00:00"),
            reading("Alpha", "2026-07-12T10:00:00"),
            reading("Alpha", "2026-07-12T11:00:00"),
        ), retentionDays = 0)
        assertEquals(listOf("Alpha", "Zeta"), store.deviceNames())
    }

    @Test fun emptyStoreStats() {
        val s = store.stats()
        assertEquals(0, s.rowCount)
        assertNull(s.oldest)
        assertNull(s.newest)
    }
}
