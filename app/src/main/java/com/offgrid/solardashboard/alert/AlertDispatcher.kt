package com.offgrid.solardashboard.alert

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.offgrid.solardashboard.data.AlertConfig
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Sends low-battery alerts over the channels enabled in [AlertConfig]: email via
 * Gmail SMTP, SMS from this device's SIM, and a local notification. Each channel
 * is attempted independently and its failure is logged rather than thrown, so
 * one broken channel does not stop the others. All methods do blocking I/O and
 * must be called off the main thread.
 */
object AlertDispatcher {

    private const val TAG = "AlertDispatcher"
    private const val CHANNEL_ID = "solar_alerts"
    private const val NOTIF_ID = 2

    /** Per-channel outcome, for logging and the settings "test alert" result. */
    data class Result(val email: String?, val sms: String?, val notify: String?) {
        val anyFailure get() = listOfNotNull(email, sms, notify).any { it.startsWith("error") }
        fun summary(): String = listOfNotNull(
            email?.let { "email: $it" },
            sms?.let { "sms: $it" },
            notify?.let { "notification: $it" },
        ).joinToString("; ").ifEmpty { "no channels enabled" }
    }

    /** Fire a real low-battery alert for [soc] against [config]. */
    fun sendLowBattery(context: Context, config: AlertConfig, soc: Int): Result {
        val subject = "Solar Dashboard: battery at $soc%"
        val body = "Battery state of charge has dropped to $soc%, below your " +
            "${config.thresholdPct}% alert threshold."
        return dispatch(context, config, subject, body)
    }

    /** Fire an alert when a sensor reads at or above the high-temperature threshold. */
    fun sendHighTemp(context: Context, config: AlertConfig, name: String, tempC: Double): Result {
        val subject = "Solar Dashboard: high temperature"
        val body = "%s reached %.0f°C, at or above your %d°C alert threshold."
            .format(name, tempC, config.highTempC)
        return dispatch(context, config, subject, body)
    }

    /** Fire an alert when a device reports one or more protection faults. */
    fun sendFault(context: Context, config: AlertConfig, name: String, faults: List<String>): Result {
        val subject = "Solar Dashboard: device fault"
        val body = "$name reported a fault: ${faults.joinToString(", ")}."
        return dispatch(context, config, subject, body)
    }

    /** Fire an alert when every configured battery pack has stopped responding. */
    fun sendUnreachable(context: Context, config: AlertConfig): Result {
        val subject = "Solar Dashboard: batteries unreachable"
        val body = "No battery pack is responding over Bluetooth. The monitor cannot " +
            "read your battery state of charge. Check the packs and the monitoring phone."
        return dispatch(context, config, subject, body)
    }

    /** Send a test message over the enabled channels to verify configuration. */
    fun sendTest(context: Context, config: AlertConfig): Result {
        val subject = "Solar Dashboard test alert"
        val body = "This is a test of your Solar Dashboard low-battery alerts. " +
            "If you received this, the channel is configured correctly."
        return dispatch(context, config, subject, body)
    }

    private fun dispatch(context: Context, config: AlertConfig, subject: String, body: String): Result {
        val email = if (config.emailEnabled) runChannel { sendEmail(config, subject, body) } else null
        val sms = if (config.smsEnabled) runChannel { sendSms(context, config.smsNumber, "$subject. $body") } else null
        val notify = if (config.notifyEnabled) runChannel { notify(context, subject, body) } else null
        val result = Result(email, sms, notify)
        Log.i(TAG, "alert dispatched: ${result.summary()}")
        return result
    }

    private inline fun runChannel(block: () -> Unit): String = try {
        block(); "sent"
    } catch (e: Exception) {
        "error: ${e.message ?: e.javaClass.simpleName}"
    }

    // ── Channels ──────────────────────────────────────────────────────────────

    private fun sendEmail(config: AlertConfig, subject: String, body: String) {
        require(config.senderGmail.isNotBlank()) { "sender Gmail address is empty" }
        require(config.appPassword.isNotBlank()) { "app password is empty" }
        require(config.recipientEmail.isNotBlank()) { "recipient address is empty" }

        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(config.senderGmail, config.appPassword.replace(" ", ""))
        })
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.senderGmail))
            config.recipientEmail.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                addRecipient(Message.RecipientType.TO, InternetAddress(it))
            }
            setSubject(subject)
            setText(body)
        }
        Transport.send(message)
    }

    private fun sendSms(context: Context, number: String, text: String) {
        require(number.isNotBlank()) { "destination number is empty" }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) throw IllegalStateException("SEND_SMS permission not granted")

        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        // Split so messages longer than one SMS segment are delivered intact.
        val parts = sms.divideMessage(text)
        sms.sendMultipartTextMessage(number, null, parts, null, null)
    }

    private fun notify(context: Context, title: String, body: String) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Battery Alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .build()
        mgr.notify(NOTIF_ID, notification)
    }
}
