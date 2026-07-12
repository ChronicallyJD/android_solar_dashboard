package com.offgrid.solardashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * First-run welcome / onboarding screen. Shown until the user taps "Get
 * started", which flips [com.offgrid.solardashboard.data.AppSettings.welcomeSeen].
 */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("☀", fontSize = 64.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Solar Dashboard",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Monitor your batteries, solar chargers, and inverters over Bluetooth",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(28.dp))

        Feature("🔋", "Battery packs", "Reads JBD and Vatrer BMS units over Bluetooth. Shows voltage, current, SoC, cells, and faults.")
        Feature("🌞", "Solar and inverters", "Decodes Victron Instant Readout advertisements from SmartSolar MPPTs and Phoenix or MultiPlus inverters. No pairing needed.")
        Feature("📈", "History and trends", "Every reading is stored on the phone so you can chart voltage, current, PV power, and state of charge over time.")
        Feature("🔒", "Offline", "Nothing leaves your phone. Data stays in a database on the device.")

        Spacer(Modifier.height(24.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Text("To get started you'll need:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                "• Each device's Bluetooth MAC address\n" +
                    "• For Victron devices, the 32-character Advertisement key\n" +
                    "   (VictronConnect → gear → Product info → Instant Readout → Show)",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(28.dp))

        Button(onClick = onGetStarted, modifier = Modifier.fillMaxWidth()) {
            Text("Get started", fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
        Text(
            "Opens Settings so you can add your first device.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Feature(icon: String, title: String, body: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(icon, fontSize = 24.sp, modifier = Modifier.padding(end = 14.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                body,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
