package com.offgrid.solardashboard.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Accent palette shared with the web dashboard chart series and status colors. */
object SolarColors {
    val Cyan = Color(0xFF00E5FF)
    val Green = Color(0xFF39FF8A)
    val Amber = Color(0xFFFFB830)
    val Red = Color(0xFFFF4060)
    val Yellow = Color(0xFFFFE066)
    val Purple = Color(0xFFA78BFA)
    val Orange = Color(0xFFFB923C)
    val Teal = Color(0xFF34D399)

    val ChartPalette = listOf(Cyan, Green, Amber, Red, Yellow, Purple, Orange, Teal)

    // SoC thresholds from _soc_color: >=60 green, >=30 amber, else red.
    fun socColor(pct: Int?): Color = when {
        pct == null -> Color.Gray
        pct >= 60 -> Green
        pct >= 30 -> Amber
        else -> Red
    }
}

private val DarkScheme = darkColorScheme(
    primary = SolarColors.Cyan,
    secondary = SolarColors.Green,
    tertiary = SolarColors.Amber,
    background = Color(0xFF0B1220),
    surface = Color(0xFF111a2b),
    error = SolarColors.Red,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0277BD),
    secondary = Color(0xFF2E7D32),
    tertiary = Color(0xFFEF6C00),
    error = SolarColors.Red,
)

/** Apply the Material 3 color scheme for the named theme (dark, light, business). */
@Composable
fun SolarTheme(themeName: String, content: @Composable () -> Unit) {
    val scheme = when (themeName) {
        "light" -> LightScheme
        "business" -> LightScheme
        else -> DarkScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
