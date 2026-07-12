package com.offgrid.solardashboard.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers for the Android battery-optimization exemption. When the app is
 * optimized, the system can defer or suspend the background poll while the
 * screen is off, which would delay readings and low-battery alerts. Prompting
 * the user to exempt the app keeps the monitor running reliably.
 */
object PowerOptimization {

    /** True when the app is exempt from battery optimization (Doze allowlist). */
    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Show the system exemption request (a direct Allow/Deny dialog). Falls back
     * to the full battery-optimization settings list if the direct request is
     * unavailable.
     */
    fun requestExemption(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(direct)
        } catch (e: Exception) {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Whether to warn the user: only when devices are configured (so monitoring
     * matters) and the app is not yet exempt. Pure, for unit testing.
     */
    fun shouldWarn(hasDevices: Boolean, isExempt: Boolean): Boolean = hasDevices && !isExempt
}
