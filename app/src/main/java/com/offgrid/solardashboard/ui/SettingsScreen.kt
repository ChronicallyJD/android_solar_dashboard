package com.offgrid.solardashboard.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offgrid.solardashboard.ble.BmsScanner
import com.offgrid.solardashboard.ble.VictronScanner
import com.offgrid.solardashboard.data.DeviceConfig
import com.offgrid.solardashboard.data.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen: manage the device list (add/edit/remove, with BLE discovery),
 * tune polling and history options, and run history-database maintenance. Edits
 * are saved immediately through the [DashboardViewModel].
 */
@Composable
fun SettingsScreen(vm: DashboardViewModel) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Pair<Int?, DeviceConfig>?>(null) }

    // Per-section expand state; Devices starts open, the rest collapsed so the
    // screen is short on arrival and the user opens what they need.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    fun isOpen(title: String) = expanded[title] ?: (title == "Devices")
    fun toggle(title: String) { expanded[title] = !isOpen(title) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
        CollapsibleSection("Devices", isOpen("Devices"), { toggle("Devices") },
            subtitle = "${devices.size} configured", first = true) {
            devices.forEachIndexed { i, dev ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(dev.name, fontWeight = FontWeight.Bold)
                            Text("${dev.kind.uppercase()}${dev.deviceType?.let { " · $it" } ?: ""} · ${dev.mac}",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        TextButton(onClick = { editing = i to dev }) { Text("Edit") }
                        IconButton(onClick = { vm.removeDevice(i) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editing = null to DeviceConfig("bms", "", "") }) { Text("Add BMS") }
                Button(onClick = { editing = null to DeviceConfig("victron", "", "", deviceType = "mppt") }) { Text("Add Victron") }
            }
        }

        CollapsibleSection("Polling & History", isOpen("Polling & History"), { toggle("Polling & History") }) {
            var bmsInt by remember(settings) { mutableStateOf(settings.bmsIntervalSec.toString()) }
            var vicInt by remember(settings) { mutableStateOf(settings.victronIntervalSec.toString()) }
            var scan by remember(settings) { mutableStateOf(settings.scanTimeoutSec.toString()) }
            var maxHist by remember(settings) { mutableStateOf(settings.maxHistory.toString()) }
            var retention by remember(settings) { mutableStateOf(settings.retentionDays.toString()) }
            var rate by remember(settings) { mutableStateOf(settings.electricityRateCents.toString()) }
            var histEnabled by remember(settings) { mutableStateOf(settings.historyEnabled) }

            NumField("BMS interval (s, min 30)", bmsInt) { bmsInt = it }
            NumField("Victron interval (s, min 10)", vicInt) { vicInt = it }
            NumField("Scan window (s)", scan) { scan = it }
            NumField("Chart history points", maxHist) { maxHist = it }
            NumField("History retention (days, 0=forever)", retention) { retention = it }
            NumField("Electricity rate (cents/kWh, for \$ Saved)", rate) { rate = it }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Persist history to database", Modifier.weight(1f))
                Switch(checked = histEnabled, onCheckedChange = { histEnabled = it })
            }

            Button(onClick = {
                vm.saveSettings(settings.copy(
                    bmsIntervalSec = bmsInt.toIntOrNull()?.coerceAtLeast(30) ?: 120,
                    victronIntervalSec = vicInt.toIntOrNull()?.coerceAtLeast(10) ?: 30,
                    scanTimeoutSec = scan.toIntOrNull()?.coerceAtLeast(3) ?: 10,
                    maxHistory = maxHist.toIntOrNull()?.coerceAtLeast(10) ?: 600,
                    retentionDays = retention.toIntOrNull()?.coerceAtLeast(0) ?: 1095,
                    electricityRateCents = rate.toDoubleOrNull()?.coerceIn(0.0, 200.0)
                        ?: com.offgrid.solardashboard.data.Tariff.DEFAULT_CENTS_PER_KWH,
                    historyEnabled = histEnabled,
                ))
            }, modifier = Modifier.padding(top = 8.dp)) { Text("Save settings") }
        }

        CollapsibleSection("Low-Battery Alerts", isOpen("Low-Battery Alerts"), { toggle("Low-Battery Alerts") }) {
            AlertsSection(vm)
        }

        CollapsibleSection("Database Maintenance", isOpen("Database Maintenance"), { toggle("Database Maintenance") }) {
            DatabaseMaintenanceSection(vm)
        }

        androidx.compose.foundation.layout.Spacer(Modifier.padding(32.dp))
    }

    editing?.let { (index, dev) ->
        DeviceEditorDialog(
            initial = dev,
            vm = vm,
            onDismiss = { editing = null },
            onSave = { updated ->
                if (index == null) vm.addDevice(updated) else vm.updateDevice(index, updated)
                editing = null
            },
        )
    }
}

/**
 * A settings section with a tappable header (chevron + title, optional subtitle)
 * that collapses or expands its [content]. Draws a divider above, skipped for
 * the [first] section so there is no rule under the app bar.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    first: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (!first) Divider(Modifier.padding(vertical = 12.dp))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (expanded) "▾" else "▸", fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(end = 8.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
        subtitle?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
    if (expanded) content()
}

@Composable
private fun AlertsSection(vm: DashboardViewModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var cfg by remember { mutableStateOf(vm.loadAlertConfig()) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    val smsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cfg = cfg.copy(smsEnabled = granted)
        if (!granted) status = "SMS permission denied. Text alerts will not send."
    }
    fun enableSms() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            == PackageManager.PERMISSION_GRANTED
        ) cfg = cfg.copy(smsEnabled = true)
        else smsPermLauncher.launch(Manifest.permission.SEND_SMS)
    }

    Text("Notify you when the average battery state of charge drops below a threshold.",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(vertical = 4.dp))

    SwitchRow("Enable alerts", cfg.enabled) { cfg = cfg.copy(enabled = it) }

    if (cfg.enabled) {
        var threshold by remember(cfg.enabled) { mutableStateOf(cfg.thresholdPct.toString()) }
        NumField("Alert threshold (% SoC)", threshold) {
            threshold = it
            cfg = cfg.copy(thresholdPct = it.toIntOrNull()?.coerceIn(1, 100) ?: 20)
        }

        // Email
        SwitchRow("Email (Gmail)", cfg.emailEnabled) { cfg = cfg.copy(emailEnabled = it) }
        if (cfg.emailEnabled) {
            Field("From Gmail address", cfg.senderGmail) { cfg = cfg.copy(senderGmail = it.trim()) }
            PasswordField("Gmail app password (16 chars)", cfg.appPassword) { cfg = cfg.copy(appPassword = it) }
            Field("Send alerts to (email)", cfg.recipientEmail) { cfg = cfg.copy(recipientEmail = it.trim()) }
            Text("Needs a Gmail App Password (Google account, Security, 2-Step Verification, App passwords). " +
                "Your normal password will not work.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 4.dp))
        }

        // SMS
        SwitchRow("SMS (this phone's number)", cfg.smsEnabled) {
            if (it) enableSms() else cfg = cfg.copy(smsEnabled = false)
        }
        if (cfg.smsEnabled) {
            Field("Send alerts to (phone number)", cfg.smsNumber) { cfg = cfg.copy(smsNumber = it.trim()) }
            Text("Sends from this phone's SIM. Standard message rates apply.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 4.dp))
        }

        // Notification
        SwitchRow("Phone notification", cfg.notifyEnabled) { cfg = cfg.copy(notifyEnabled = it) }

        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveAlertConfig(cfg); status = "Alert settings saved." }) {
                Text("Save alerts")
            }
            OutlinedButton(
                enabled = !testing,
                onClick = {
                    scope.launch {
                        vm.saveAlertConfig(cfg)
                        testing = true; status = "Sending test alert…"
                        status = vm.sendTestAlert(cfg)
                        testing = false
                    }
                },
            ) { Text("Send test alert") }
        }
    } else {
        Button(onClick = { vm.saveAlertConfig(cfg); status = "Alert settings saved." },
            modifier = Modifier.padding(top = 8.dp)) { Text("Save alerts") }
    }

    status?.let {
        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun DatabaseMaintenanceSection(vm: DashboardViewModel) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var stats by remember { mutableStateOf<HistoryStore.Stats?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }

    val activity = LocalContext.current as? FragmentActivity
    // Gate a destructive action behind device auth (biometric / PIN). If the
    // device has no lock enrolled there is nothing to check against, so proceed.
    val authThen: (String, () -> Unit) -> Unit = { reason, action ->
        val act = activity
        if (act == null || !DeviceAuth.isAvailable(act)) action()
        else DeviceAuth.authenticate(
            act, "Confirm it's you", reason,
            onSuccess = action,
            onFail = { status = "Authentication failed. Action cancelled." },
        )
    }

    LaunchedEffect(refresh) {
        stats = withContext(Dispatchers.IO) { vm.dbStats() }
    }

    val s = stats
    Text(
        when {
            s == null -> "Loading…"
            s.rowCount == 0 -> "No stored readings."
            else -> "${s.rowCount} readings · ${s.oldest?.replace('T', ' ')} → ${s.newest?.replace('T', ' ')}"
        },
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(vertical = 6.dp),
    )

    Text("Delete a date range (YYYY-MM-DD; leave a bound blank for open-ended):",
        fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { Field("From", from) { from = it } }
        Box(Modifier.weight(1f)) { Field("To", to) { to = it } }
    }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            enabled = from.isNotBlank() || to.isNotBlank(),
            onClick = {
                authThen("Authenticate to delete history in this date range.") {
                    scope.launch {
                        val n = withContext(Dispatchers.IO) {
                            vm.pruneHistoryRange(from.ifBlank { null }, to.ifBlank { null })
                        }
                        status = "Deleted $n reading(s) in range."
                        from = ""; to = ""; refresh++
                    }
                }
            },
        ) { Text("Delete range") }
        OutlinedButton(onClick = { confirmAll = true }) {
            Text("Clear all", color = SolarColors.Red)
        }
    }
    status?.let {
        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp))
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Clear all history?") },
            text = {
                Text("This permanently deletes all ${s?.rowCount ?: 0} stored readings " +
                    "and clears the charts. This cannot be undone.")
            },
            confirmButton = {
                Button(onClick = {
                    confirmAll = false
                    authThen("Authenticate to permanently clear all history.") {
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { vm.pruneAllHistory() }
                            status = "Cleared $n reading(s)."
                            refresh++
                        }
                    }
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun DeviceEditorDialog(
    initial: DeviceConfig,
    vm: DashboardViewModel,
    onDismiss: () -> Unit,
    onSave: (DeviceConfig) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var mac by remember { mutableStateOf(initial.mac) }
    var bleName by remember { mutableStateOf(initial.bleName ?: "") }
    var encKey by remember { mutableStateOf(initial.encKey ?: "") }
    var password by remember { mutableStateOf(initial.password ?: "") }
    var deviceType by remember { mutableStateOf(initial.deviceType ?: "mppt") }
    var typeMenu by remember { mutableStateOf(false) }
    val isVictron = initial.kind == "victron"

    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var foundVic by remember { mutableStateOf<List<VictronScanner.Discovered>>(emptyList()) }
    var foundBms by remember { mutableStateOf<List<BmsScanner.Discovered>>(emptyList()) }
    var scanNote by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isEmpty()) "Add ${if (isVictron) "Victron" else "BMS"} device" else "Edit device") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field("Display name", name) { name = it }
                Field("MAC address (AA:BB:CC:DD:EE:FF)", mac) { mac = it }
                // MAC addresses are broadcast, so let the user pick from a scan
                // instead of typing (for either device kind).
                ScanButton(scanning) {
                    scope.launch {
                        scanning = true; scanNote = null; foundVic = emptyList(); foundBms = emptyList()
                        // Hide devices already configured, but keep the one being
                        // edited so it can still be re-selected.
                        val exclude = vm.configuredMacs() - initial.mac.uppercase()
                        if (isVictron) foundVic = vm.discoverVictron(exclude)
                        else foundBms = vm.discoverBms(exclude)
                        scanning = false
                        val empty = if (isVictron) foundVic.isEmpty() else foundBms.isEmpty()
                        scanNote = when {
                            empty -> "No new devices found. Nearby devices may already be added, or Bluetooth is off or out of range."
                            isVictron -> "Tap a device to fill its MAC. Then paste its advertisement key below."
                            else -> "Tap your BMS to fill its MAC. Likely batteries are marked."
                        }
                    }
                }
                scanNote?.let {
                    Text(it, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 4.dp))
                }

                if (isVictron) {
                    foundVic.forEach { d ->
                        DiscoveredRow(d.name, d.mac, "suggested: ${d.suggestedType}",
                            selected = d.mac.equals(mac, ignoreCase = true)) {
                            mac = d.mac
                            if (name.isBlank()) name = d.name ?: d.mac
                            deviceType = d.suggestedType
                        }
                    }

                    Field("Advertisement key (32 hex chars)", encKey) { encKey = it }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Type: ", Modifier.padding(end = 8.dp))
                        OutlinedButton(onClick = { typeMenu = true }) { Text(deviceType) }
                        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                            listOf("mppt", "inverter", "monitor", "dcdc", "lithium", "meter").forEach { t ->
                                DropdownMenuItem(text = { Text(t) }, onClick = { deviceType = t; typeMenu = false })
                            }
                        }
                    }
                } else {
                    foundBms.forEach { d ->
                        DiscoveredRow(d.name, d.mac, if (d.likelyBms) "likely battery" else "other device",
                            selected = d.mac.equals(mac, ignoreCase = true)) {
                            mac = d.mac
                            if (name.isBlank()) name = d.name ?: d.mac
                            if (bleName.isBlank()) bleName = d.name ?: ""
                        }
                    }
                    Field("BLE name (optional)", bleName) { bleName = it }
                    Field("Password (default 0000)", password) { password = it }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    initial.copy(
                        name = name.ifBlank { mac },
                        mac = DeviceConfig.normaliseMac(mac),
                        bleName = bleName.ifBlank { null },
                        encKey = DeviceConfig.normaliseKey(encKey),
                        password = password.ifBlank { null },
                        deviceType = if (isVictron) deviceType else null,
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ScanButton(scanning: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        enabled = !scanning,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        if (scanning) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("  Scanning…")
        } else {
            Text("📡  Scan for nearby devices")
        }
    }
}

@Composable
private fun DiscoveredRow(name: String?, mac: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(name ?: "(unnamed)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("$mac · $tag",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
