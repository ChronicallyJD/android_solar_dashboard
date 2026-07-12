package com.offgrid.solardashboard

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offgrid.solardashboard.service.MonitorService
import com.offgrid.solardashboard.ui.DashboardScreen
import com.offgrid.solardashboard.ui.DashboardViewModel
import com.offgrid.solardashboard.ui.HelpScreen
import com.offgrid.solardashboard.ui.SettingsScreen
import com.offgrid.solardashboard.ui.SolarTheme
import com.offgrid.solardashboard.ui.WelcomeScreen

/**
 * Single-activity entry point. Hosts the Compose UI and owns the runtime
 * permission request, then starts the background [MonitorService] that does the
 * actual BLE polling. Extends [FragmentActivity] (rather than ComponentActivity)
 * because androidx.biometric's prompt, used to gate destructive settings
 * actions, requires a FragmentActivity host.
 */
class MainActivity : FragmentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Start the service once permissions are resolved (granted or not; the
            // service tolerates missing permissions and simply reports errors).
            MonitorService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        setContent {
            val vm: DashboardViewModel = viewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()
            SolarTheme(settings.theme) { AppScaffold(vm) }
        }
    }

    /** Request the BLE and notification permissions appropriate to the API level. */
    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}

/**
 * Top-level UI shell: a top bar (theme toggle, help, settings) over one of three
 * screens (dashboard, settings, help). Shows the one-time [WelcomeScreen] until
 * the user has dismissed it. `screen` is local navigation state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(vm: DashboardViewModel) {
    var screen by remember { mutableStateOf("dashboard") }
    val settings by vm.settings.collectAsStateWithLifecycle()

    if (!settings.welcomeSeen) {
        WelcomeScreen(onGetStarted = {
            vm.dismissWelcome()
            screen = "settings"
        })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when (screen) {
                        "settings" -> "Settings"; "help" -> "Help"; else -> "Solar Dashboard"
                    })
                },
                colors = TopAppBarDefaults.topAppBarColors(),
                actions = {
                    IconButton(onClick = { vm.cycleTheme() }) {
                        Text(when (settings.theme) {
                            "dark" -> "🌙"; "light" -> "☀️"; else -> "💼"
                        })
                    }
                    IconButton(onClick = { screen = if (screen == "help") "dashboard" else "help" }) {
                        Icon(
                            if (screen == "help") Icons.Filled.Refresh else Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = "Help",
                        )
                    }
                    IconButton(onClick = { screen = if (screen == "settings") "dashboard" else "settings" }) {
                        Icon(
                            if (screen == "settings") Icons.Filled.Refresh else Icons.Filled.Settings,
                            contentDescription = "Toggle settings",
                        )
                    }
                },
            )
        },
    ) { padding ->
        val mod = Modifier.padding(padding)
        androidx.compose.foundation.layout.Box(mod) {
            when (screen) {
                "settings" -> SettingsScreen(vm)
                "help" -> HelpScreen()
                else -> DashboardScreen(vm)
            }
        }
    }
}
