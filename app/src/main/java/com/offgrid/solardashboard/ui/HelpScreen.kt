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
        Step("3", "Tap “Scan for nearby devices” and pick yours from the list. This fills in the Bluetooth MAC for you. Devices you have already added are hidden.")
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
            "inverters are delivering to loads right now. $ Saved is the value of the energy your " +
            "loads used so far today, priced at the electricity rate you set under Settings, Polling " +
            "and History (it defaults to the national average). It is based on load energy, not solar " +
            "harvested, so it keeps climbing at night while the battery runs your loads, and it resets " +
            "at midnight. It is an estimate.")

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

        Section("Low-battery alerts")
        Body("Under Settings, Low-Battery Alerts notifies you when the average battery state of charge " +
            "drops below a threshold you set. It sends once when the battery crosses below the threshold, " +
            "then again only after it recovers a few percent above, so you are not messaged repeatedly. " +
            "Three channels can run together:")
        Body("Email: sends through Gmail. Enter your Gmail address, a Gmail App Password, and where to " +
            "send the alert. The App Password is required because Google no longer allows normal passwords " +
            "for this. Create one at your Google account, Security, 2-Step Verification (turn it on if " +
            "needed), then App passwords. Paste the 16-character password into the app.\n" +
            "SMS: sends a text from this phone's own number to a number you enter. It needs the SMS " +
            "permission and cell service, and standard message rates apply.\n" +
            "Phone notification: shows a notification on this phone. No setup needed.")
        Body("Use \"Send test alert\" to confirm your setup over every enabled channel before you rely on it. " +
            "Alert settings, including the Gmail App Password, are stored encrypted on this phone and are " +
            "only ever sent to Gmail's mail server.")
        Body("While alerts are on, you are also notified if every battery pack stops responding at once, " +
            "so you know when the monitor has lost sight of your batteries, not just when they are low. " +
            "This too sends once and clears when a pack is reachable again.")

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
