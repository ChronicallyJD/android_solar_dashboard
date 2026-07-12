package com.offgrid.solardashboard.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric tests for alert configuration persistence and the two armed flags.
 * Exercises the store's save/load and default behavior (via the encrypted store
 * or its plain fallback, whichever the environment provides).
 */
@RunWith(RobolectricTestRunner::class)
class AlertStoreTest {

    private lateinit var store: AlertStore

    @Before fun setUp() {
        store = AlertStore(ApplicationProvider.getApplicationContext())
    }

    @Test fun defaultConfigIsDisabledWithSensibleDefaults() {
        val c = store.load()
        assertFalse(c.enabled)
        assertEquals(20, c.thresholdPct)
        assertEquals(5, c.rearmMarginPct)
        assertTrue(c.notifyEnabled)
        assertFalse(c.emailEnabled)
        assertFalse(c.smsEnabled)
    }

    @Test fun savedConfigRoundTrips() {
        val cfg = AlertConfig(
            enabled = true, thresholdPct = 35, rearmMarginPct = 8,
            emailEnabled = true, senderGmail = "me@gmail.com", appPassword = "abcd efgh ijkl mnop",
            recipientEmail = "you@example.com",
            smsEnabled = true, smsNumber = "+15551234567",
            notifyEnabled = false, highTempC = 45,
        )
        store.save(cfg)
        assertEquals(cfg, store.load())
    }

    @Test fun armedDefaultsTrueAndPersists() {
        assertTrue(store.isArmed())
        store.setArmed(false)
        assertFalse(store.isArmed())
        store.setArmed(true)
        assertTrue(store.isArmed())
    }

    @Test fun unreachableArmedDefaultsTrueAndPersists() {
        assertTrue(store.isUnreachableArmed())
        store.setUnreachableArmed(false)
        assertFalse(store.isUnreachableArmed())
    }

    @Test fun armedFlagsAreIndependent() {
        store.setArmed(false)
        store.setUnreachableArmed(true)
        assertFalse(store.isArmed())
        assertTrue(store.isUnreachableArmed())
    }
}
