package com.offgrid.solardashboard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.offgrid.solardashboard.data.SettingsRepository

/**
 * Restarts the monitoring service after a device reboot or an app update, so an
 * unattended monitor keeps running (and alerting) without the user reopening the
 * app. Only starts when at least one device is configured, since with no devices
 * there is nothing to poll.
 *
 * Handles:
 *  - ACTION_BOOT_COMPLETED: device finished booting.
 *  - ACTION_MY_PACKAGE_REPLACED: this app was updated (the old process, and its
 *    service, were stopped during the update).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val deviceCount = try {
            SettingsRepository(context).loadDevices().size
        } catch (e: Exception) {
            Log.w(TAG, "loadDevices failed: ${e.message}"); 0
        }
        if (!shouldStart(intent.action, deviceCount)) {
            Log.i(TAG, "${intent.action}: not starting ($deviceCount device(s))")
            return
        }
        Log.i(TAG, "${intent.action}: starting monitor service for $deviceCount device(s)")
        MonitorService.start(context)
    }

    companion object {
        private const val TAG = "BootReceiver"

        /**
         * Start the monitor only for a boot or app-update broadcast, and only
         * when at least one device is configured (nothing to poll otherwise).
         * Pure so it is unit-testable without a device.
         */
        fun shouldStart(action: String?, deviceCount: Int): Boolean =
            (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) &&
                deviceCount > 0
    }
}
