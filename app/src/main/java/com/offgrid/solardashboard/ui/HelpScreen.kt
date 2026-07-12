package com.offgrid.solardashboard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** In-app user guide, reachable from the top-bar help icon. */
@Composable
fun HelpScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("How to use Solar Dashboard", fontSize = 22.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text("Bluetooth monitoring for your batteries, solar chargers, and inverters. " +
            "It runs on this phone and stores data on this phone.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))

        Section("Getting started")
        Step("1", "Open Settings (gear icon, top right).")
        Step("2", "Tap “Add BMS” for a battery pack, or “Add Victron” for a solar charger, inverter, or monitor.")
        Step("3", "Tap “Scan for nearby devices” and pick yours from the list. This fills in the Bluetooth MAC for you.")
        Step("4", "For Victron devices, paste the advertisement key (see below). For a BMS, set the password only if yours needs one (default 0000).")
        Step("5", "Save. The device appears on the dashboard within one poll cycle.")

        Section("Finding a Victron advertisement key")
        Body("The MAC address is broadcast, but the encryption key is not, so you enter it once. " +
            "In VictronConnect: open the device, tap the gear icon, then Product info, then " +
            "“Instant readout via Bluetooth”, then Show. Copy the 32-character advertisement key and " +
            "paste it into the key field. The app accepts extra spaces or a MAC prefix.")

        Section("Device types (Victron)")
        Body("mppt: SmartSolar or BlueSolar solar charger.\n" +
            "inverter: Phoenix, MultiPlus, Quattro, VE.Bus.\n" +
            "monitor: SmartShunt or BMV battery monitor.\n" +
            "dcdc: Orion DC-DC converter.\n" +
            "The Scan picker suggests a type. Change it with the Type dropdown if needed.")

        Section("Reading the dashboard")
        Body("The top tiles show SOLAR (total PV watts), INVERTERS (total AC output), and " +
            "BATTERY (average state of charge). Each device has a status badge. ONLINE means it " +
            "reported this cycle. PARTIAL means some data plus a warning. OFFLINE means it was not " +
            "reachable this cycle. Scroll down for charts of voltage, current, PV power, and state of charge.")

        Section("Energy card")
        Body("Harnessing is the power your solar is producing right now. Expending is the power your " +
            "inverters are delivering to loads right now. $ Saved is the value of the solar energy " +
            "harvested so far today (the sum of each charger's yield for the day), priced at the " +
            "national average residential electricity rate. It resets with the daily yield and is an " +
            "estimate, not your actual utility rate.")

        Section("Collapsing sections")
        Body("Tap a section header (MPPT Chargers, Inverters, Battery Packs) to collapse or expand " +
            "its cards. The count on the right shows how many devices in that section are online.")

        Section("Updated and next update")
        Body("The line at the top shows when the last reading arrived and the approximate time of the " +
            "next poll. Poll intervals are set in Settings.")

        Section("Settings")
        Body("Set how often devices are polled (BMS and Victron have separate intervals), the scan " +
            "window length, how many chart points to keep, and whether readings are saved to the " +
            "history database and for how long.")

        Section("Database maintenance")
        Body("Under Settings you can delete stored history by date range, or all of it. Deletion is " +
            "permanent and requires unlocking with your fingerprint, face, or PIN first.")

        Section("If a device will not come online")
        Body("Check that Bluetooth is on and you granted the Bluetooth permissions.\n" +
            "Run “Scan for nearby devices” again to confirm the device is in range and the MAC matches.\n" +
            "For Victron, confirm the advertisement key is the one from VictronConnect.\n" +
            "Give it a poll cycle or two. The interval is set in Settings.\n" +
            "Close VictronConnect if it is open, so it does not hold the connection.")

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.secondary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Body(text: String) {
    Text(text, fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun Step(num: String, text: String) {
    androidx.compose.foundation.layout.Row(Modifier.padding(vertical = 4.dp)) {
        Text("$num.", fontWeight = FontWeight.Bold, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp))
        Text(text, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
    }
}
