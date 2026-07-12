package com.offgrid.solardashboard.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the battery-optimization warning decision. */
class PowerOptimizationTest {

    @Test fun warnsWhenDevicesConfiguredAndNotExempt() {
        assertTrue(PowerOptimization.shouldWarn(hasDevices = true, isExempt = false))
    }

    @Test fun noWarnWhenExempt() {
        assertFalse(PowerOptimization.shouldWarn(hasDevices = true, isExempt = true))
    }

    @Test fun noWarnWithoutDevices() {
        assertFalse(PowerOptimization.shouldWarn(hasDevices = false, isExempt = false))
        assertFalse(PowerOptimization.shouldWarn(hasDevices = false, isExempt = true))
    }
}
