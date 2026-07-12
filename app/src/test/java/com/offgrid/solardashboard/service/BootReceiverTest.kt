package com.offgrid.solardashboard.service

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the boot/update restart decision. */
class BootReceiverTest {

    @Test fun startsOnBootWhenDevicesConfigured() {
        assertTrue(BootReceiver.shouldStart(Intent.ACTION_BOOT_COMPLETED, 1))
        assertTrue(BootReceiver.shouldStart(Intent.ACTION_BOOT_COMPLETED, 8))
    }

    @Test fun startsOnPackageReplacedWhenDevicesConfigured() {
        assertTrue(BootReceiver.shouldStart(Intent.ACTION_MY_PACKAGE_REPLACED, 1))
    }

    @Test fun doesNotStartWithNoDevices() {
        assertFalse(BootReceiver.shouldStart(Intent.ACTION_BOOT_COMPLETED, 0))
        assertFalse(BootReceiver.shouldStart(Intent.ACTION_MY_PACKAGE_REPLACED, 0))
    }

    @Test fun ignoresUnrelatedActions() {
        assertFalse(BootReceiver.shouldStart(Intent.ACTION_POWER_CONNECTED, 3))
        assertFalse(BootReceiver.shouldStart("com.example.SOMETHING", 3))
        assertFalse(BootReceiver.shouldStart(null, 3))
    }
}
