package com.offgrid.solardashboard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One named series of (x-index, y) points already aligned to a shared x-axis. */
data class Series(val name: String, val color: Color, val values: List<Double?>)

/**
 * Multi-series line chart drawn on a Canvas. The offline-friendly
 * equivalent of the Chart.js line charts in the web dashboard. Null values
 * break the line (gaps), matching the "only series with non-null" behaviour.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LineChart(title: String, series: List<Series>, modifier: Modifier = Modifier) {
    val nonEmpty = series.filter { s -> s.values.any { it != null } }
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        if (nonEmpty.isEmpty()) {
            Text("No data yet", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 8.dp))
            return@Column
        }

        val allValues = nonEmpty.flatMap { it.values.filterNotNull() }
        val minY = allValues.min()
        val maxY = allValues.max()
        val range = (maxY - minY).let { if (it == 0.0) 1.0 else it }
        val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

        Canvas(Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp)) {
            val w = size.width
            val h = size.height
            // horizontal grid lines
            for (i in 0..4) {
                val y = h * i / 4f
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            for (s in nonEmpty) {
                val n = s.values.size
                if (n < 2) continue
                val path = Path()
                var started = false
                s.values.forEachIndexed { idx, v ->
                    if (v == null) { started = false; return@forEachIndexed }
                    val x = w * idx / (n - 1).toFloat()
                    val y = h - ((v - minY) / range * h).toFloat()
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                drawPath(path, s.color, style = Stroke(width = 2.5f))
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("%.1f".format(maxY), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("%.1f".format(minY), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }

        FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            nonEmpty.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Canvas(Modifier.size(10.dp)) { drawCircle(s.color) }
                    Text(s.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
    }
}
