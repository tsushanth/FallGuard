package com.kreativekoala.fallguard

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

private enum class AppState { MONITORING, ALERT_COUNTDOWN, CALLING, FALSE_ALARM_DISMISSED }

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private val detector = FallDetector()

    private var appState by mutableStateOf(AppState.MONITORING)
    private var lastEventLabel by mutableStateOf("No falls detected yet")
    private var countdownTimer: CountDownTimer? = null
    private var emergencyContact = "911" // MVP: hardcoded, replace with a settings screen

    private val requestCallPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService<SensorManager>()!!
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestCallPermission.launch(Manifest.permission.CALL_PHONE)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FallGuardScreen(
                        state = appState,
                        statusText = lastEventLabel,
                        onCancelAlert = { cancelAlert() },
                        onSimulateFall = { simulateFall() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (appState != AppState.MONITORING) return
        val result = detector.onSample(
            event.values[0].toDouble(),
            event.values[1].toDouble(),
            event.values[2].toDouble(),
            System.currentTimeMillis(),
        )
        when (result) {
            FallEvent.FallConfirmed -> triggerAlert()
            FallEvent.FalsePositiveRejected -> lastEventLabel = "Impact detected, but no fall (still moving) — ignored"
            null -> Unit
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun triggerAlert() {
        appState = AppState.ALERT_COUNTDOWN
        lastEventLabel = "Fall detected. Calling emergency contact in 15s unless cancelled."
        countdownTimer = object : CountDownTimer(15_000, 1000) {
            override fun onTick(msLeft: Long) {
                lastEventLabel = "Fall detected. Calling in ${msLeft / 1000}s — tap Cancel if you're OK"
            }

            override fun onFinish() = callEmergencyContact()
        }.start()
    }

    private fun cancelAlert() {
        countdownTimer?.cancel()
        appState = AppState.MONITORING
        lastEventLabel = "Alert cancelled by user. Monitoring resumed."
        detector.reset()
    }

    private fun callEmergencyContact() {
        appState = AppState.CALLING
        lastEventLabel = "Calling $emergencyContact"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$emergencyContact")))
        } else {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$emergencyContact")))
        }
    }

    /** Dev-only hook for demoing on the device without staging a real fall. */
    private fun simulateFall() = triggerAlert()
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
        Text(statusText, style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(32.dp))

        if (state == AppState.ALERT_COUNTDOWN) {
            Button(onClick = onCancelAlert) { Text("I'm OK — Cancel") }
        } else {
            OutlinedButton(onClick = onSimulateFall) { Text("Simulate Fall (demo)") }
        }
    }
}
