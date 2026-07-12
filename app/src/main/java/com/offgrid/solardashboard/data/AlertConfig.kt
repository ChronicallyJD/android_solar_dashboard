package com.offgrid.solardashboard.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Low-battery alert configuration. Persisted encrypted at rest because it holds
 * a Gmail app password. Alerts fire when the average battery state of charge
 * crosses below [thresholdPct], and re-arm only after it climbs back above
 * [thresholdPct] + [rearmMarginPct] so a battery hovering near the threshold
 * does not send repeated messages.
 */
data class AlertConfig(
    val enabled: Boolean = false,
    val thresholdPct: Int = 20,
    val rearmMarginPct: Int = 5,
    // Email (Gmail SMTP)
    val emailEnabled: Boolean = false,
    val senderGmail: String = "",
    val appPassword: String = "",
    val recipientEmail: String = "",
    // SMS (this device's SIM)
    val smsEnabled: Boolean = false,
    val smsNumber: String = "",
    // Local notification
    val notifyEnabled: Boolean = true,
)

/**
 * Persists [AlertConfig] plus the alert "armed" flag using
 * EncryptedSharedPreferences (Android Keystore). Falls back to plain
 * SharedPreferences only if the Keystore is unavailable, so a device quirk can
 * never disable alerts entirely.
 */
class AlertStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "alert_prefs", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.w(TAG, "encrypted prefs unavailable, using plain: ${e.message}")
        context.getSharedPreferences("alert_prefs_plain", Context.MODE_PRIVATE)
    }

    fun load(): AlertConfig = AlertConfig(
        enabled = prefs.getBoolean(K_ENABLED, false),
        thresholdPct = prefs.getInt(K_THRESHOLD, 20),
        rearmMarginPct = prefs.getInt(K_MARGIN, 5),
        emailEnabled = prefs.getBoolean(K_EMAIL_EN, false),
        senderGmail = prefs.getString(K_SENDER, "") ?: "",
        appPassword = prefs.getString(K_APP_PW, "") ?: "",
        recipientEmail = prefs.getString(K_RECIPIENT, "") ?: "",
        smsEnabled = prefs.getBoolean(K_SMS_EN, false),
        smsNumber = prefs.getString(K_SMS_NUM, "") ?: "",
        notifyEnabled = prefs.getBoolean(K_NOTIFY_EN, true),
    )

    fun save(c: AlertConfig) {
        prefs.edit {
            putBoolean(K_ENABLED, c.enabled)
            putInt(K_THRESHOLD, c.thresholdPct)
            putInt(K_MARGIN, c.rearmMarginPct)
            putBoolean(K_EMAIL_EN, c.emailEnabled)
            putString(K_SENDER, c.senderGmail)
            putString(K_APP_PW, c.appPassword)
            putString(K_RECIPIENT, c.recipientEmail)
            putBoolean(K_SMS_EN, c.smsEnabled)
            putString(K_SMS_NUM, c.smsNumber)
            putBoolean(K_NOTIFY_EN, c.notifyEnabled)
        }
    }

    /** True when the low-SoC alert is armed (ready to fire). Defaults to armed. */
    fun isArmed(): Boolean = prefs.getBoolean(K_ARMED, true)

    fun setArmed(armed: Boolean) = prefs.edit { putBoolean(K_ARMED, armed) }

    /** True when the all-batteries-unreachable alert is armed. Defaults to armed. */
    fun isUnreachableArmed(): Boolean = prefs.getBoolean(K_UNREACH_ARMED, true)

    fun setUnreachableArmed(armed: Boolean) = prefs.edit { putBoolean(K_UNREACH_ARMED, armed) }

    companion object {
        private const val TAG = "AlertStore"
        private const val K_ENABLED = "enabled"
        private const val K_THRESHOLD = "threshold"
        private const val K_MARGIN = "margin"
        private const val K_EMAIL_EN = "email_enabled"
        private const val K_SENDER = "sender"
        private const val K_APP_PW = "app_password"
        private const val K_RECIPIENT = "recipient"
        private const val K_SMS_EN = "sms_enabled"
        private const val K_SMS_NUM = "sms_number"
        private const val K_NOTIFY_EN = "notify_enabled"
        private const val K_ARMED = "armed"
        private const val K_UNREACH_ARMED = "unreachable_armed"
    }
}

/**
 * Pure decision logic for the low-battery alert, kept separate from I/O so it is
 * unit-testable. Given the current state of charge and the armed flag, it says
 * whether to send an alert now and what the armed flag becomes.
 */
object AlertEvaluator {

    data class Decision(val fire: Boolean, val armed: Boolean)

    /**
     * @param soc current average battery state of charge, or null if unknown.
     * @param threshold percent below which an alert fires.
     * @param rearmMargin percent above [threshold] the battery must recover to
     *   before another alert can fire.
     * @param armed whether the alert is currently ready to fire.
     */
    fun evaluate(soc: Int?, threshold: Int, rearmMargin: Int, armed: Boolean): Decision = when {
        soc == null -> Decision(fire = false, armed = armed)                 // no data, hold state
        armed && soc < threshold -> Decision(fire = true, armed = false)      // cross below: fire once
        !armed && soc >= threshold + rearmMargin -> Decision(fire = false, armed = true) // recovered: re-arm
        else -> Decision(fire = false, armed = armed)                         // no change
    }

    /**
     * Decide whether to fire the "all batteries unreachable" alert. Fires once
     * when every configured BMS stops responding, and re-arms as soon as at
     * least one becomes reachable again.
     *
     * @param bmsConfigured number of BMS devices in the config.
     * @param reachableCount how many of them responded this cycle.
     * @param armed whether this alert is currently ready to fire.
     */
    fun evaluateReachability(bmsConfigured: Int, reachableCount: Int, armed: Boolean): Decision = when {
        bmsConfigured == 0 -> Decision(fire = false, armed = armed)           // nothing to watch
        armed && reachableCount == 0 -> Decision(fire = true, armed = false)  // all gone: fire once
        !armed && reachableCount > 0 -> Decision(fire = false, armed = true)  // one back: re-arm
        else -> Decision(fire = false, armed = armed)                         // no change
    }
}
