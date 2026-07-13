package com.offgrid.solardashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offgrid.solardashboard.data.AppSettings
import com.offgrid.solardashboard.data.DeviceConfig
import com.offgrid.solardashboard.data.MonitorState
import com.offgrid.solardashboard.data.Tariff
import com.offgrid.solardashboard.protocol.DeviceReading
import com.offgrid.solardashboard.service.PowerOptimization
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Main monitoring screen. A single scrolling list of: an update-status line, the
 * energy card, the three summary tiles, then a collapsible section of device
 * cards per device type, and finally the history charts. All data is observed
 * from the [DashboardViewModel]; this composable holds only per-section collapse
 * state.
 */
@Composable
fun DashboardScreen(vm: DashboardViewModel) {
    val snap by vm.monitor.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    val readings = snap.readings
    val bms = readings.filter { it.deviceType == "bms" }
    val mppt = readings.filter { it.deviceType == "mppt" }
    val inverters = readings.filter { it.deviceType == "inverter" }
    val others = readings.filter { it.deviceType !in setOf("bms", "mppt", "inverter") }

    // Persist which sections the user has collapsed across recompositions.
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    // Re-check the battery-optimization exemption when the screen resumes (e.g.
    // after returning from the system exemption dialog).
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var powerExempt by remember { mutableStateOf(PowerOptimization.isExempt(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) powerExempt = PowerOptimization.isExempt(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        item {
            if (devices.isEmpty()) EmptyHint()
            else UpdateStatusLine(snap, settings, devices)
        }

        if (PowerOptimization.shouldWarn(devices.isNotEmpty(), powerExempt)) {
            item { BatteryOptimizationBanner(onFix = { PowerOptimization.requestExemption(context) }) }
        }

        if (readings.isNotEmpty()) {
            val totalPv = mppt.mapNotNull { it.pvPowerW }.sum()
            val totalAc = inverters.mapNotNull { it.acOutPowerVa }.sum()
            item {
                EnergyBar(harnessedW = totalPv, expendedW = totalAc,
                    loadEnergyTodayWh = snap.loadEnergyTodayWh,
                    projectedAnnualWh = snap.loadEnergyProjectedAnnualWh,
                    rateCentsPerKwh = settings.electricityRateCents)
            }
            item { OverviewRow(mppt, inverters, bms) }
        }

        section("MPPT Chargers", mppt, collapsed)
        section("Inverters", inverters, collapsed)
        section("Battery Packs", bms, collapsed)
        section("Other Devices", others, collapsed)

        item { HistorySection(vm) }
        item { Spacer48() }
    }
}

private fun LazyListScope.section(
    title: String,
    items: List<DeviceReading>,
    collapsed: MutableMap<String, Boolean>,
) {
    if (items.isEmpty()) return
    val isCollapsed = collapsed[title] == true
    item(key = "header:$title") {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { collapsed[title] = !isCollapsed }
                .padding(top = 14.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (isCollapsed) "▸" else "▾", fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(end = 8.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("${items.count { it.error == null }}/${items.size}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
    if (!isCollapsed) {
        items(items, key = { it.address }) { DeviceCard(it) }
    }
}

/** "Updated HH:MM:SS · next ~HH:MM:SS" plus scanning/waiting states. */
@Composable
private fun UpdateStatusLine(
    snap: MonitorState.Snapshot,
    settings: AppSettings,
    devices: List<DeviceConfig>,
) {
    val text = when {
        snap.lastUpdated != null -> {
            val updated = snap.lastUpdated.substringAfter('T')
            val next = nextUpdateClock(snap.lastUpdated, pollIntervalSec(settings, devices))
            if (next != null) "Updated $updated · next update ~$next" else "Updated $updated"
        }
        snap.scanning -> "Scanning…"
        else -> "Waiting for first poll…"
    }
    Text(text, fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(vertical = 8.dp))
}

/** Effective poll cadence, mirroring MonitorService. */
private fun pollIntervalSec(settings: AppSettings, devices: List<DeviceConfig>): Int {
    val hasVictron = devices.any { it.kind == "victron" }
    val hasBms = devices.any { it.kind == "bms" }
    return minOf(
        if (hasVictron) settings.victronIntervalSec else Int.MAX_VALUE,
        if (hasBms) settings.bmsIntervalSec else Int.MAX_VALUE,
    ).coerceAtLeast(10)
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun nextUpdateClock(lastIso: String, intervalSec: Int): String? = try {
    LocalDateTime.parse(lastIso).plusSeconds(intervalSec.toLong()).format(CLOCK)
} catch (e: Exception) {
    null
}

/**
 * Energy overview: how much power is currently being Harnessed (solar) versus
 * Expended (AC load), plus the "$ Saved" estimate priced at [rateCentsPerKwh].
 * Basing savings on load energy (not solar harvested) credits the grid power you
 * avoided buying: it accrues day and night (battery discharge still serves loads)
 * without double-counting the solar that flowed through the battery.
 *
 * The "$ Saved" section leads with an annual projection from recent days
 * ([projectedAnnualWh], null until a full day is recorded) and shows today's
 * running total ([loadEnergyTodayWh]) below it.
 */
@Composable
private fun EnergyBar(
    harnessedW: Double,
    expendedW: Double,
    loadEnergyTodayWh: Double,
    projectedAnnualWh: Double?,
    rateCentsPerKwh: Double,
) {
    val maxW = maxOf(harnessedW, expendedW, 1.0)
    val expendedFrac = (expendedW / maxW).toFloat()
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("ENERGY", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            EnergyRow("Harnessing", "%.0f W".format(harnessedW), (harnessedW / maxW).toFloat(), SolarColors.Green)
            EnergyRow("Expending", "%.0f W".format(expendedW), expendedFrac, SolarColors.Purple)
            SavingsBlock(loadEnergyTodayWh, projectedAnnualWh, rateCentsPerKwh)
        }
    }
}

/** One labelled bar in the [EnergyBar] card: label, proportional fill, value. */
@Composable
private fun EnergyRow(label: String, value: String, fraction: Float, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.sp, modifier = Modifier.width(80.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Box(
            Modifier
                .weight(1f)
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
            )
        }
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(84.dp).padding(start = 8.dp))
    }
}

/**
 * The "$ Saved" section of the energy card: a "$ Saved" heading, an annual
 * projection when available, and today's running total. All figures are priced
 * at [rateCentsPerKwh].
 */
@Composable
private fun SavingsBlock(
    todayWh: Double,
    projectedAnnualWh: Double?,
    rateCentsPerKwh: Double,
) {
    val today = Tariff.savingsDollars(todayWh, rateCentsPerKwh)
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Text("$ Saved", fontSize = 11.sp, fontWeight = FontWeight.Bold,
        color = SolarColors.Amber, modifier = Modifier.padding(top = 10.dp))
    if (projectedAnnualWh != null) {
        val perYear = Tariff.savingsDollars(projectedAnnualWh, rateCentsPerKwh)
        Text("~$%.0f / year at this rate".format(perYear),
            fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
    }
    Text("$%.2f today".format(today), fontSize = 11.sp, color = muted,
        modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun OverviewRow(mppt: List<DeviceReading>, inv: List<DeviceReading>, bms: List<DeviceReading>) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val totalPv = mppt.mapNotNull { it.pvPowerW }.sum()
        val mpptOnline = mppt.count { it.error == null }
        AggregateCard("SOLAR", "%.0f W".format(totalPv), "$mpptOnline/${mppt.size} online", SolarColors.Amber, Modifier.weight(1f))

        val totalAc = inv.mapNotNull { it.acOutPowerVa }.sum()
        val invOnline = inv.count { it.error == null }
        AggregateCard("INVERTERS", "%.0f W".format(totalAc), "$invOnline/${inv.size} online", SolarColors.Purple, Modifier.weight(1f))

        val socs = bms.mapNotNull { it.capacityPct }
        val avgSoc = if (socs.isNotEmpty()) socs.average().toInt() else null
        val totalWh = bms.mapNotNull { it.remainWh }.sum()
        AggregateCard("BATTERY", avgSoc?.let { "$it%" } ?: "n/a", "%.0f Wh".format(totalWh), SolarColors.socColor(avgSoc), Modifier.weight(1f))
    }
}

@Composable
private fun AggregateCard(label: String, value: String, subtitle: String, color: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, fontSize = 10.sp, color = color, fontWeight = FontWeight.Bold)
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

/** Selectable chart windows. hours = null means show everything loaded. */
private enum class ChartRange(val label: String, val hours: Long?) {
    H1("1h", 1), H6("6h", 6), H24("24h", 24), ALL("All", null)
}

@Composable
private fun HistorySection(vm: DashboardViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    if (history.isEmpty()) return
    var range by remember { mutableStateOf(ChartRange.ALL) }

    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Historical Trends", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ChartRange.values().forEach { r -> RangeChip(r.label, r == range) { range = r } }
            }
        }

        // Cut off points older than the selected window (timestamps are ISO
        // local strings, which sort lexicographically).
        val cutoff = range.hours?.let {
            LocalDateTime.now().minusHours(it).format(CHART_TS)
        }
        val names = history.keys.toList()
        fun seriesFor(pick: (MonitorState.HistPoint) -> Double?) = names.mapIndexed { i, name ->
            val points = history[name]!!.let { pts ->
                if (cutoff == null) pts else pts.filter { it.timestamp >= cutoff }
            }
            Series(name, SolarColors.ChartPalette[i % SolarColors.ChartPalette.size], points.map(pick))
        }
        LineChart("Battery Voltage (V)", seriesFor { it.voltageV })
        LineChart("Battery Current (A)", seriesFor { it.currentA })
        LineChart("PV Power (W)", seriesFor { it.pvPowerW })
        LineChart("State of Charge (%)", seriesFor { it.capacityPct?.toDouble() })
    }
}

private val CHART_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 1f else 0.7f),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun BatteryOptimizationBanner(onFix: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onFix),
        colors = CardDefaults.cardColors(containerColor = SolarColors.Amber.copy(alpha = 0.15f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("⚠ Battery optimization is on", fontWeight = FontWeight.Bold,
                fontSize = 14.sp, color = SolarColors.Amber)
            Text("Android may pause monitoring and alerts when the screen is off. " +
                "Tap to allow the app to keep running in the background.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun EmptyHint() {
    Card(Modifier.fillMaxWidth().padding(top = 24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text("No devices configured", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Open Settings (gear icon) to add your JBD BMS and Victron devices. " +
                "Use \"Scan for nearby devices\" to fill in each MAC, and for Victron add the " +
                "32-character advertisement key from VictronConnect.",
                fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun Spacer48() {
    androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp))
}
