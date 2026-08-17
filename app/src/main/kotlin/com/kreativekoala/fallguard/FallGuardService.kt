package com.kreativekoala.fallguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10

/**
 * Runs fall detection while the app is backgrounded. Two independent
 * detection paths feed the same alert flow: accelerometer (fall classifier)
 * and microphone (sustained loud sound — a shout/cry for help). Either one
 * can trigger the countdown; whichever fires first wins, the other keeps
 * listening in case the first was a false alarm and a second signal follows.
 */
class FallGuardService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private val detector = FallDetector()
    private var countdownTimer: CountDownTimer? = null

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var audioJob: Job? = null

    companion object {
        const val CHANNEL_ID = "fallguard_monitoring"
        const val NOTIFICATION_ID = 1
        private const val LOUD_SOUND_DB_THRESHOLD = 85.0 // sustained shout-level input
        private const val LOUD_SOUND_SUSTAIN_MS = 1200L

        // Simple static reference since Activity and Service share one process —
        // avoids binder/AIDL ceremony for this MVP. Cleared in onDestroy.
        var instance: FallGuardService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        sensorManager = getSystemService(SensorManager::class.java)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Monitoring for falls")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (FallGuardState.audioPermissionGranted) {
            startAudioMonitoring()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        sensorManager.unregisterListener(this)
        audioJob?.cancel()
        serviceJob.cancel()
        countdownTimer?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        if (FallGuardState.appState.value != AppState.MONITORING) return
        val result = detector.onSample(
            event.values[0].toDouble(),
            event.values[1].toDouble(),
            event.values[2].toDouble(),
            System.currentTimeMillis(),
        )
        when (result) {
            FallEvent.FallConfirmed -> triggerAlert("Fall detected (motion)")
            FallEvent.FalsePositiveRejected -> {
                FallGuardState.statusText.value = "Impact detected, but no fall (still moving) — ignored"
            }
            null -> Unit
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Suppress("MissingPermission") // caller only starts monitoring after checking FallGuardState.audioPermissionGranted
    private fun startAudioMonitoring() {
        audioJob = serviceScope.launch {
            val sampleRate = 44100
            val minBufSize = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufSize <= 0) return@launch
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize,
            )
            val buffer = ShortArray(minBufSize)
            var loudSince = 0L

            try {
                recorder.startRecording()
                while (serviceJob.isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    val rms = kotlin.math.sqrt(buffer.take(read).sumOf { (it.toDouble() * it) } / read)
                    val db = 20 * log10(if (rms < 1.0) 1.0 else rms)

                    if (FallGuardState.appState.value != AppState.MONITORING) {
                        loudSince = 0L
                        continue
                    }

                    val now = System.currentTimeMillis()
                    if (db > LOUD_SOUND_DB_THRESHOLD) {
                        if (loudSince == 0L) loudSince = now
                        if (now - loudSince > LOUD_SOUND_SUSTAIN_MS) {
                            triggerAlert("Sustained loud sound detected (possible call for help)")
                            loudSince = 0L
                        }
                    } else {
                        loudSince = 0L
                    }
                }
            } finally {
                recorder.stop()
                recorder.release()
            }
        }
    }

    fun simulateFall() = triggerAlert("Simulated fall (demo)")

    private fun triggerAlert(reason: String) {
        if (FallGuardState.appState.value != AppState.MONITORING) return
        FallGuardState.appState.value = AppState.ALERT_COUNTDOWN
        FallGuardState.statusText.value = "$reason. Calling emergency contact in 15s unless cancelled."
        updateNotification("$reason — calling soon unless cancelled")

        countdownTimer = object : CountDownTimer(15_000, 1000) {
            override fun onTick(msLeft: Long) {
                FallGuardState.statusText.value = "$reason. Calling in ${msLeft / 1000}s — tap Cancel if you're OK"
            }

            override fun onFinish() {
                FallGuardState.appState.value = AppState.CALLING
                FallGuardState.statusText.value = "Calling emergency contact"
                updateNotification("Calling emergency contact")
                startActivity(
                    Intent(this@FallGuardService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_TRIGGER_CALL, true),
                )
            }
        }.start()
    }

    fun cancelAlertFromActivity() {
        countdownTimer?.cancel()
        FallGuardState.appState.value = AppState.MONITORING
        FallGuardState.statusText.value = "Alert cancelled by user. Monitoring resumed."
        detector.reset()
        updateNotification("Monitoring for falls")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Fall monitoring", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows while FallGuard is actively monitoring for falls" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FallGuard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}
