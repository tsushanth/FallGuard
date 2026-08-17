package com.kreativekoala.fallguard

import kotlin.math.sqrt

/**
 * Pure, sensor-agnostic fall classifier: free-fall dip followed by an impact spike
 * within a short window, then a post-impact stillness check to reject drops of the
 * phone itself (which don't stay still) vs a body fall (which usually does).
 *
 * Feed it accelerometer magnitude samples in chronological order via [onSample].
 */
class FallDetector(
    private val freeFallThreshold: Double = 3.0, // m/s^2, gravity ~9.8 so this is a real dip
    private val impactThreshold: Double = 20.0, // m/s^2 spike
    private val freeFallWindowMs: Long = 1000,
    private val stillnessWindowMs: Long = 1500,
    private val stillnessThreshold: Double = 2.0, // stddev-ish band around gravity
) {
    private enum class State { WATCHING, FREE_FALL, IMPACT_DETECTED, CONFIRMING_STILLNESS }

    private var state = State.WATCHING
    private var freeFallAtMs: Long = 0
    private var impactAtMs: Long = 0
    private val stillnessSamples = mutableListOf<Double>()

    fun onSample(x: Double, y: Double, z: Double, timestampMs: Long): FallEvent? {
        val magnitude = sqrt(x * x + y * y + z * z)

        when (state) {
            State.WATCHING -> {
                if (magnitude < freeFallThreshold) {
                    state = State.FREE_FALL
                    freeFallAtMs = timestampMs
                }
            }

            State.FREE_FALL -> {
                val elapsed = timestampMs - freeFallAtMs
                if (elapsed > freeFallWindowMs) {
                    state = State.WATCHING // free fall window expired, no impact followed
                } else if (magnitude > impactThreshold) {
                    state = State.IMPACT_DETECTED
                    impactAtMs = timestampMs
                    stillnessSamples.clear()
                }
            }

            State.IMPACT_DETECTED -> {
                state = State.CONFIRMING_STILLNESS
                stillnessSamples.add(magnitude)
            }

            State.CONFIRMING_STILLNESS -> {
                val elapsed = timestampMs - impactAtMs
                stillnessSamples.add(magnitude)
                if (elapsed > stillnessWindowMs) {
                    val avg = stillnessSamples.average()
                    val isStill = stillnessSamples.all { kotlin.math.abs(it - avg) < stillnessThreshold }
                    state = State.WATCHING
                    return if (isStill) FallEvent.FallConfirmed else FallEvent.FalsePositiveRejected
                }
            }
        }
        return null
    }

    fun reset() {
        state = State.WATCHING
        stillnessSamples.clear()
    }
}

sealed class FallEvent {
    data object FallConfirmed : FallEvent()
    data object FalsePositiveRejected : FallEvent()
}
