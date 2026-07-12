package com.offgrid.solardashboard.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the electricity-cost savings calculation. */
class TariffTest {

    @Test fun oneKwhAtDefaultRate() {
        // 1000 Wh at 18.8 cents/kWh = $0.188.
        assertEquals(0.188, Tariff.savingsDollars(1000.0, 18.8), 1e-9)
    }

    @Test fun customRate() {
        // 5000 Wh at 15 cents/kWh = $0.75.
        assertEquals(0.75, Tariff.savingsDollars(5000.0, 15.0), 1e-9)
    }

    @Test fun zeroEnergyIsZero() {
        assertEquals(0.0, Tariff.savingsDollars(0.0, 18.8), 1e-9)
    }

    @Test fun zeroRateIsZero() {
        assertEquals(0.0, Tariff.savingsDollars(9999.0, 0.0), 1e-9)
    }

    @Test fun highRate() {
        // 10 kWh at 40 cents/kWh = $4.00.
        assertEquals(4.0, Tariff.savingsDollars(10_000.0, 40.0), 1e-9)
    }
}
