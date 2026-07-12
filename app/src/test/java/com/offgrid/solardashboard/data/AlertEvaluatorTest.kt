package com.offgrid.solardashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the low-battery alert trigger/re-arm state machine. */
class AlertEvaluatorTest {

    private val threshold = 20
    private val margin = 5

    private fun eval(soc: Int?, armed: Boolean) =
        AlertEvaluator.evaluate(soc, threshold, margin, armed)

    @Test fun firesOnceWhenCrossingBelow() {
        val d = eval(soc = 19, armed = true)
        assertTrue(d.fire)
        assertFalse(d.armed) // disarmed after firing
    }

    @Test fun doesNotRefireWhileBelowAndDisarmed() {
        val d = eval(soc = 10, armed = false)
        assertFalse(d.fire)
        assertFalse(d.armed)
    }

    @Test fun doesNotFireAtExactlyThreshold() {
        // Strictly below threshold fires; at threshold it does not.
        val d = eval(soc = 20, armed = true)
        assertFalse(d.fire)
        assertTrue(d.armed)
    }

    @Test fun rearmsOnlyAfterClimbingAboveThresholdPlusMargin() {
        // At threshold + margin - 1 it stays disarmed.
        assertFalse(eval(soc = 24, armed = false).armed)
        // At threshold + margin it re-arms.
        val d = eval(soc = 25, armed = false)
        assertTrue(d.armed)
        assertFalse(d.fire) // re-arming never sends
    }

    @Test fun stayingHighKeepsArmedWithoutFiring() {
        val d = eval(soc = 80, armed = true)
        assertFalse(d.fire)
        assertTrue(d.armed)
    }

    @Test fun nullSocHoldsStateArmed() {
        val d = eval(soc = null, armed = true)
        assertFalse(d.fire)
        assertTrue(d.armed)
    }

    @Test fun nullSocHoldsStateDisarmed() {
        val d = eval(soc = null, armed = false)
        assertFalse(d.fire)
        assertFalse(d.armed)
    }

    @Test fun fullCycleFiresExactlyOncePerDip() {
        var armed = true
        var fires = 0
        // Drain from 30 down to 10, then recover to 40, then dip again to 15.
        val socTrace = listOf(30, 25, 21, 20, 19, 15, 12, 18, 24, 26, 40, 30, 22, 16)
        for (soc in socTrace) {
            val d = eval(soc, armed)
            if (d.fire) fires++
            armed = d.armed
        }
        // One fire on the first dip below 20, one on the second dip after re-arming.
        assertEquals(2, fires)
    }

    @Test fun customThresholdAndMargin() {
        // threshold 50, margin 10
        assertTrue(AlertEvaluator.evaluate(49, 50, 10, true).fire)
        assertFalse(AlertEvaluator.evaluate(59, 50, 10, false).armed) // needs >= 60 to re-arm
        assertTrue(AlertEvaluator.evaluate(60, 50, 10, false).armed)
    }

    // ── Reachability (all batteries unreachable) ────────────────────────────

    @Test fun reachabilityFiresWhenAllUnreachable() {
        val d = AlertEvaluator.evaluateReachability(bmsConfigured = 3, reachableCount = 0, armed = true)
        assertTrue(d.fire)
        assertFalse(d.armed)
    }

    @Test fun reachabilityDoesNotRefireWhileAllDown() {
        assertFalse(AlertEvaluator.evaluateReachability(3, 0, armed = false).fire)
    }

    @Test fun reachabilityNoFireWhenSomeReachable() {
        val d = AlertEvaluator.evaluateReachability(3, 1, armed = true)
        assertFalse(d.fire)
        assertTrue(d.armed)
    }

    @Test fun reachabilityRearmsWhenOneReturns() {
        val d = AlertEvaluator.evaluateReachability(3, 2, armed = false)
        assertTrue(d.armed)
        assertFalse(d.fire)
    }

    @Test fun reachabilityIgnoredWithNoBmsConfigured() {
        val d = AlertEvaluator.evaluateReachability(bmsConfigured = 0, reachableCount = 0, armed = true)
        assertFalse(d.fire)
        assertTrue(d.armed)
    }

    @Test fun reachabilityFiresOncePerOutage() {
        var armed = true
        var fires = 0
        // 3 reachable, drop to 0, recover to 2, drop to 0 again.
        for (reachable in listOf(3, 2, 0, 0, 0, 1, 2, 0, 0)) {
            val d = AlertEvaluator.evaluateReachability(3, reachable, armed)
            if (d.fire) fires++
            armed = d.armed
        }
        assertEquals(2, fires)
    }
}
