package com.offgrid.solardashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offgrid.solardashboard.protocol.DeviceReading

/** Status badge: ONLINE / OFFLINE / PARTIAL (error present but voltage parsed). */
@Composable
private fun StatusBadge(reading: DeviceReading) {
    val (label, color) = when {
        reading.error == null -> "ONLINE" to SolarColors.Green
        reading.voltageV != null -> "PARTIAL" to SolarColors.Amber
        else -> "OFFLINE" to SolarColors.Red
    }
    Text(
        label,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun TypePill(type: String) {
    val color = when (type) {
        "bms" -> SolarColors.Green
        "mppt" -> SolarColors.Amber
        "inverter" -> SolarColors.Purple
        "monitor" -> SolarColors.Cyan
        else -> SolarColors.Teal
    }
    Text(
        type.uppercase(),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Metric(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier = Modifier.wrapContentWidth()) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = color)
    }
}

@Composable
private fun DeviceCardShell(reading: DeviceReading, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(reading.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    TypePill(reading.deviceType)
                }
                StatusBadge(reading)
            }
            content()
        }
    }
}

@Composable
private fun MetricRow(vararg metrics: Pair<String, String>) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        metrics.forEach { (l, v) -> Metric(l, v) }
    }
}

@Composable
private fun SocBar(pct: Int?, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        LinearProgressIndicator(
            progress = { (pct ?: 0) / 100f },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
            color = SolarColors.socColor(pct),
        )
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp))
    }
}

private fun f(v: Double?, digits: Int, unit: String = ""): String =
    if (v == null) "n/a" else "%.${digits}f%s".format(v, unit)

@Composable
fun DeviceCard(reading: DeviceReading) {
    when (reading.deviceType) {
        "bms" -> BmsCard(reading)
        "monitor" -> MonitorCard(reading)
        "inverter" -> InverterCard(reading)
        else -> MpptCard(reading)
    }
}

@Composable
private fun BmsCard(r: DeviceReading) = DeviceCardShell(r) {
    MetricRow(
        "VOLTS" to f(r.voltageV, 3, "V"),
        "AMPS" to f(r.currentA, 3, "A"),
        "WATTS" to f(r.powerW, 2, "W"),
    )
    SocBar(r.capacityPct, buildString {
        append("SoC ${r.capacityPct?.let { "$it%" } ?: "n/a"}")
        r.remainWh?.let { append(" · ${"%.0f".format(it)} Wh") }
    })
    val cap = buildList {
        if (r.remainAh != null && r.nominalAh != null)
            add("CAP" to "%.1f/%.1f Ah".format(r.remainAh, r.nominalAh))
        r.timeToEmptyH?.let { add("TTE" to hhmm(it)) }
        r.timeToFullH?.let { add("TTF" to hhmm(it)) }
    }
    if (cap.isNotEmpty()) MetricRow(*cap.toTypedArray())
    val info = buildList {
        r.cellCount?.let { add("CELLS" to "$it") }
        r.cycleCount?.let { add("CYCLES" to "$it") }
        if (r.chargeFet != null) add("FET" to "CHG ${chk(r.chargeFet)} DSG ${chk(r.dischargeFet)}")
        r.swVersion?.let { add("FW" to it) }
    }
    if (info.isNotEmpty()) MetricRow(*info.toTypedArray())
    if (r.tempC.isNotEmpty())
        MetricRow("TEMP" to r.tempC.joinToString("  ") { "%.1f°C".format(it) })
    r.faults?.takeIf { it.isNotEmpty() }?.let {
        Text("⚠ ${it.joinToString(", ")}", color = SolarColors.Red, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    }
    r.balanceCells?.let { bal ->
        if (bal.any { it == 1 }) {
            val cells = bal.mapIndexedNotNull { i, b -> if (b == 1) i + 1 else null }
            Text("Balancing: ${cells.joinToString(", ")}", color = SolarColors.Cyan,
                fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
    r.error?.let { ErrLine(it) }
}

@Composable
private fun MonitorCard(r: DeviceReading) = DeviceCardShell(r) {
    MetricRow(
        "VOLTS" to f(r.voltageV, 3, "V"),
        "AMPS" to f(r.currentA, 3, "A"),
        "WATTS" to f(r.powerW, 2, "W"),
    )
    SocBar(r.capacityPct, buildString {
        append("SoC ${r.capacityPct?.let { "$it%" } ?: "n/a"}")
        r.ttgMinutes?.let { append(" · TTG ${it / 60}h ${it % 60}m") }
    })
    r.error?.let { ErrLine(it) }
}

@Composable
private fun InverterCard(r: DeviceReading) = DeviceCardShell(r) {
    val acW = r.acOutPowerVa
    MetricRow(
        "AC OUT" to (if (acW != null) "%.0f W".format(acW) else r.rawLoadIndicator?.let { "~$it" } ?: "n/a"),
        "BATT V" to f(r.voltageV, 2, "V"),
        "BATT A" to f(r.currentA, 2, "A"),
    )
    val row2 = buildList {
        r.inverterState?.let { add("STATE" to it) }
        r.acInSource?.let { add("AC IN" to it) }
        r.acInPowerW?.let { add("AC IN W" to "%.0f".format(it)) }
        r.temperatureC?.let { add("TEMP" to "%.0f°C".format(it)) }
    }
    if (row2.isNotEmpty()) MetricRow(*row2.toTypedArray())
    r.alarmReason?.let {
        Text("⚠ $it", color = SolarColors.Red, fontWeight = FontWeight.Bold,
            fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    }
    r.error?.let { ErrLine(it) }
}

@Composable
private fun MpptCard(r: DeviceReading) = DeviceCardShell(r) {
    MetricRow(
        "BATT V" to f(r.voltageV, 2, "V"),
        "BATT A" to f(r.currentA, 2, "A"),
        "PV" to (r.pvPowerW?.let { "%.0f W".format(it) } ?: "n/a"),
    )
    val row2 = buildList {
        r.chargerState?.let { add("STATE" to it) }
        r.yieldTodayWh?.let { add("YIELD" to "%.0f Wh".format(it)) }
        r.loadCurrentA?.let { add("LOAD" to "%.1f A".format(it)) }
        r.errorCode?.let { add("ERR" to "$it") }
    }
    if (row2.isNotEmpty()) MetricRow(*row2.toTypedArray())
    r.error?.let { ErrLine(it) }
}

@Composable
private fun ErrLine(msg: String) {
    Text(msg, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
}

private fun chk(b: Boolean?) = if (b == true) "✓" else "✗"

private fun hhmm(hours: Double): String {
    val h = hours.toInt()
    val m = ((hours - h) * 60).toInt()
    return "${h}h ${m}m"
}
