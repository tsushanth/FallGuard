package com.kreativekoala.fallguard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppState { MONITORING, ALERT_COUNTDOWN, CALLING }

/**
 * In-process shared state between FallGuardService (which owns detection,
 * including while backgrounded) and MainActivity (which just observes and
 * renders it). Same process, so a StateFlow singleton is simpler than
 * binder/AIDL for this MVP.
 */
object FallGuardState {
    val appState = MutableStateFlow(AppState.MONITORING)
    val statusText = MutableStateFlow("Monitoring (motion + sound)")

    // Set by the Activity once permissions are granted; the Service reads it
    // to decide whether audio-based detection is active.
    var audioPermissionGranted = false
}
