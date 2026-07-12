package com.offgrid.solardashboard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.offgrid.solardashboard.MainActivity
import com.offgrid.solardashboard.ble.JbdGattClient
import com.offgrid.solardashboard.ble.VictronScanner
import com.offgrid.solardashboard.ble.nowIso
import com.offgrid.solardashboard.alert.AlertDispatcher
import com.offgrid.solardashboard.data.AlertEvaluator
import com.offgrid.solardashboard.data.AlertStore
import com.offgrid.solardashboard.data.EnergyAccumulator
import com.offgrid.solardashboard.data.EnergyStore
import com.offgrid.solardashboard.data.HistoryStore
import com.offgrid.solardashboard.data.MonitorState
import com.offgrid.solardashboard.data.SettingsRepository
import java.time.LocalDate
import com.offgrid.solardashboard.protocol.DeviceReading
import com.offgrid.solardashboard.protocol.VictronParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service running the poll loop. Android equivalent of the
 * solar_monitor supervisor: one coroutine scans Victron advertisements and
 * reads each BMS over GATT one-at-a-time, on the configured cadence.
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var history: HistoryStore
    private lateinit var scanner: VictronScanner
    private lateinit var jbd: JbdGattClient
    private lateinit var energyStore: EnergyStore
    private lateinit var alertStore: AlertStore

    // Daily load-energy accumulator (Wh delivered to loads) for "$ Saved".
    private var energyState = EnergyAccumulator.State("", 0.0, 0L)

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        history = HistoryStore(this)
        scanner = VictronScanner(this)
        jbd = JbdGattClient(this)
        energyStore = EnergyStore(this)
        alertStore = AlertStore(this)
        startForeground(NOTIF_ID, buildNotification("Starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopJob == null || loopJob?.isActive != true) {
            loopJob = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    /**
     * Restore last-known readings and chart history from the database so the
     * dashboard shows real data on launch instead of "Waiting for first poll…".
     * Runs once, before the first live scan.
     */
    private fun seedFromDb(settings: com.offgrid.solardashboard.data.AppSettings) {
        if (!settings.historyEnabled) return
        try {
            val latest = history.latestReadings()
            if (latest.isNotEmpty()) MonitorState.update(latest, settings.maxHistory)
            for (name in history.deviceNames()) {
                val v = history.series(name, "voltage_v", settings.maxHistory).associate { it.timestamp to it.value }
                val a = history.series(name, "current_a", settings.maxHistory).associate { it.timestamp to it.value }
                val pv = history.series(name, "pv_power_w", settings.maxHistory).associate { it.timestamp to it.value }
                val soc = history.series(name, "capacity_pct", settings.maxHistory).associate { it.timestamp to it.value }
                val stamps = (v.keys + a.keys + pv.keys + soc.keys).sorted()
                val points = stamps.map {
                    MonitorState.HistPoint(it, v[it], a[it], pv[it], soc[it]?.toInt())
                }
                if (points.isNotEmpty()) MonitorState.seedHistory(name, points)
            }
        } catch (e: Exception) {
            Log.w(TAG, "seed from db: ${e.message}")
        }
    }

    /**
     * Restore today's load-energy total from storage so "$ Saved" survives a
     * restart, or start fresh if the stored total is from a previous day.
     */
    private fun seedLoadEnergy(historyEnabled: Boolean) {
        val (storedDate, storedWh) = energyStore.load()
        energyState = EnergyAccumulator.seed(
            storedDate, storedWh, LocalDate.now().toString(), System.currentTimeMillis(),
        )
        energyStore.save(energyState.date, energyState.wh)
        MonitorState.setLoadEnergy(publishedLoadEnergy(historyEnabled))
    }

    /**
     * Update the daily load-energy total. When history is enabled the value is
     * integrated from the persisted inverter samples, which survives a restart.
     * The in-memory accumulator is kept up to date as a fallback for when history
     * is disabled (or a query fails).
     */
    private fun accumulateLoadEnergy(fresh: List<DeviceReading>, historyEnabled: Boolean) {
        val loadW = fresh.filter { it.deviceType == "inverter" }.mapNotNull { it.acOutPowerVa }.sum()
        energyState = EnergyAccumulator.accumulate(
            energyState, System.currentTimeMillis(), LocalDate.now().toString(), loadW,
        )
        energyStore.save(energyState.date, energyState.wh)
        MonitorState.setLoadEnergy(publishedLoadEnergy(historyEnabled))
    }

    /** Prefer the restart-durable DB integration; fall back to the accumulator. */
    private fun publishedLoadEnergy(historyEnabled: Boolean): Double {
        if (!historyEnabled) return energyState.wh
        return try {
            history.loadEnergyTodayWh("${LocalDate.now()}T00:00:00")
        } catch (e: Exception) {
            Log.w(TAG, "load energy from history failed: ${e.message}")
            energyState.wh
        }
    }

    /**
     * Evaluate the battery alerts each cycle: low average state of charge, and
     * all battery packs unreachable. Each fires once on the triggering transition
     * and re-arms on recovery; both armed flags are persisted so a restart does
     * not re-alert while the condition still holds.
     */
    private fun evaluateAlerts() {
        val config = alertStore.load()
        if (!config.enabled) return
        val bmsReadings = MonitorState.state.value.readings.filter { it.deviceType == "bms" }
        val reachable = bmsReadings.filter { it.error == null }
        val socs = reachable.mapNotNull { it.capacityPct }
        val bmsConfigured = settingsRepo.loadDevices().count { it.kind == "bms" }

        // Low state of charge (only when we can read at least one pack).
        if (socs.isNotEmpty()) {
            val avgSoc = socs.average().toInt()
            val d = AlertEvaluator.evaluate(
                avgSoc, config.thresholdPct, config.rearmMarginPct, alertStore.isArmed(),
            )
            if (d.armed != alertStore.isArmed()) alertStore.setArmed(d.armed)
            if (d.fire) {
                Log.i(TAG, "low-battery alert firing at $avgSoc% (threshold ${config.thresholdPct}%)")
                val result = AlertDispatcher.sendLowBattery(this, config, avgSoc)
                if (result.anyFailure) Log.w(TAG, "low-battery alert had failures: ${result.summary()}")
            }
        }

        // All battery packs unreachable.
        if (bmsConfigured > 0) {
            val d = AlertEvaluator.evaluateReachability(
                bmsConfigured, reachable.size, alertStore.isUnreachableArmed(),
            )
            if (d.armed != alertStore.isUnreachableArmed()) alertStore.setUnreachableArmed(d.armed)
            if (d.fire) {
                Log.w(TAG, "all-batteries-unreachable alert firing ($bmsConfigured configured, 0 reachable)")
                val result = AlertDispatcher.sendUnreachable(this, config)
                if (result.anyFailure) Log.w(TAG, "unreachable alert had failures: ${result.summary()}")
            }
        }
    }

    private suspend fun pollLoop() {
        val initial = settingsRepo.loadSettings()
        seedFromDb(initial)
        seedLoadEnergy(initial.historyEnabled)
        while (scope.isActive) {
            val settings = settingsRepo.loadSettings()
            val devices = settingsRepo.loadDevices()
            val victronDevices = devices.filter { it.kind == "victron" }
            val bmsDevices = devices.filter { it.kind == "bms" }
            val fresh = ArrayList<DeviceReading>()

            // ── Victron: single passive scan, then decode each ─────────────────
            if (victronDevices.isNotEmpty()) {
                MonitorState.setScanning(true)
                try {
                    scanner.scan(settings.scanTimeoutSec)
                } catch (e: Exception) {
                    Log.w(TAG, "scan error: ${e.message}")
                }
                MonitorState.setScanning(false)
                Log.i(TAG, "scan saw ${scanner.seenMacs().size} Victron MAC(s); " +
                    victronDevices.joinToString { "${it.mac}=${scanner.payloadsFor(it.mac).size}" })
                val victronReadings = victronDevices.map { dev ->
                    val payloads = scanner.payloadsFor(dev.mac)
                    val reading = if (payloads.isEmpty()) {
                        DeviceReading(
                            address = dev.mac, name = dev.name,
                            deviceType = dev.deviceType ?: "victron", timestamp = nowIso(),
                            error = "Device not seen during scan",
                        )
                    } else {
                        VictronParser.readAdvertisement(
                            dev.mac, scanner.nameFor(dev.mac) ?: dev.name,
                            payloads, dev.encKey, dev.deviceType, nowIso(),
                        ).also { it.deviceType = dev.deviceType ?: it.deviceType }
                    }
                    Log.i(TAG, "victron ${dev.mac} (${dev.deviceType}): " +
                        (reading.error?.let { "ERROR: $it" }
                            ?: "OK V=${reading.voltageV} A=${reading.currentA} PV=${reading.pvPowerW} AC=${reading.acOutPowerVa}"))
                    reading
                }
                fresh.addAll(victronReadings)
                // Publish as soon as the scan completes so the dashboard fills in
                // immediately rather than waiting for the slow BMS sweep below.
                if (victronReadings.isNotEmpty()) MonitorState.update(victronReadings, settings.maxHistory)
            }

            // ── BMS: direct GATT, one at a time ────────────────────────────────
            // Publish each reading the moment it lands: a single slow/offline pack
            // (up to PER_DEVICE_TIMEOUT_MS) must not hold back the whole dashboard.
            for (dev in bmsDevices) {
                val reading = jbd.read(dev.mac, dev.name, dev.password)
                fresh.add(reading)
                MonitorState.update(listOf(reading), settings.maxHistory)
                delay(INTER_DEVICE_GAP_MS)
            }

            if (fresh.isNotEmpty()) {
                if (settings.historyEnabled) {
                    try {
                        history.writeReadings(fresh, settings.retentionDays)
                    } catch (e: Exception) {
                        Log.w(TAG, "history write: ${e.message}")
                    }
                }
                updateNotification(fresh)
            }

            accumulateLoadEnergy(fresh, settings.historyEnabled)
            evaluateAlerts()

            val interval = minOf(
                if (victronDevices.isNotEmpty()) settings.victronIntervalSec else Int.MAX_VALUE,
                if (bmsDevices.isNotEmpty()) settings.bmsIntervalSec else Int.MAX_VALUE,
            ).coerceAtLeast(10)
            delay(interval * 1000L)
        }
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Solar Monitor", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Solar Dashboard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(readings: List<DeviceReading>) {
        val online = readings.count { it.error == null }
        val text = "$online/${readings.size} devices online"
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "solar_monitor"
        private const val NOTIF_ID = 1
        private const val INTER_DEVICE_GAP_MS = 1500L

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Starting a foreground service from the background is restricted
                // on Android 12+. Boot is exempt; other callers may not be. Log
                // rather than crash so a blocked start never takes down the caller.
                Log.w(TAG, "could not start service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
