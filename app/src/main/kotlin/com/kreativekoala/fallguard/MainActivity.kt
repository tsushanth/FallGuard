package com.kreativekoala.fallguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TRIGGER_CALL = "trigger_call"
    }

    private val emergencyContact = "911" // MVP: hardcoded, replace with a settings screen

    // Two permissions gate real functionality: CALL_PHONE (to actually dial,
    // not just open the dialer) and RECORD_AUDIO (for the loud-sound/shout
    // detection path). POST_NOTIFICATIONS is required on API 33+ for the
    // foreground-service notification to actually show.
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            FallGuardState.audioPermissionGranted = granted[Manifest.permission.RECORD_AUDIO] == true
            startMonitoringService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.getBooleanExtra(EXTRA_TRIGGER_CALL, false)) {
            dialEmergencyContact()
        }

        ensurePermissionsThenStart()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val appState by FallGuardState.appState.collectAsStateWithLifecycle()
                    val statusText by FallGuardState.statusText.collectAsStateWithLifecycle()
                    FallGuardScreen(
                        state = appState,
                        statusText = statusText,
                        onCancelAlert = { cancelAlert() },
                        onSimulateFall = { simulateFall() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TRIGGER_CALL, false)) {
            dialEmergencyContact()
        }
    }

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.CALL_PHONE, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            FallGuardState.audioPermissionGranted = true
            startMonitoringService()
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun startMonitoringService() {
        ContextCompat.startForegroundService(this, Intent(this, FallGuardService::class.java))
    }

    private fun cancelAlert() {
        FallGuardService.instance?.cancelAlertFromActivity()
    }

    private fun simulateFall() {
        FallGuardService.instance?.simulateFall()
    }

    private fun dialEmergencyContact() {
        val action = if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) Intent.ACTION_CALL else Intent.ACTION_DIAL
        startActivity(Intent(action, Uri.parse("tel:$emergencyContact")))
    }
}

@Composable
private fun FallGuardScreen(
    state: AppState,
    statusText: String,
    onCancelAlert: () -> Unit,
    onSimulateFall: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("FallGuard", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Text(statusText, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))

        if (state == AppState.ALERT_COUNTDOWN) {
            Button(onClick = onCancelAlert) { Text("I'm OK — Cancel") }
        } else {
            OutlinedButton(onClick = onSimulateFall) { Text("Simulate Fall (demo)") }
        }
    }
}
