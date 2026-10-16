package com.tim.sleeptimer

import kotlin.math.sqrt

/**
 * Pure shake detector (SPEC F12, DECISIONS D18/D33). No Android imports: fed raw accelerometer
 * samples (m/s², timestamps in ms) by [ShakeMonitor], unit-tested with synthetic samples.
 *
 * A "peak" is the acceleration magnitude rising more than [PEAK_EXCESS] above gravity after it had
 * fallen back below [REARM_EXCESS] (hysteresis, so one jolt that hovers around the threshold counts
 * once). Two peaks at least [MIN_PEAK_GAP_MS] and at most [WINDOW_MS] apart = a shake; after a shake
 * nothing counts for [COOLDOWN_MS]. Walking (≈ 2–6 m/s² above g) never reaches a peak; a single bump
 * gives one peak, and its impact ringing (tens of Hz, < 100 ms) is not a second one.
 */
class ShakeDetector(
    private val peakExcess: Float = PEAK_EXCESS,
    private val rearmExcess: Float = REARM_EXCESS,
    private val windowMs: Long = WINDOW_MS,
    private val minPeakGapMs: Long = MIN_PEAK_GAP_MS,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    private var armed = true
    private var lastPeakAt = NONE
    private var cooldownUntil = NONE

    /** Returns true exactly once per detected shake. */
    fun onSample(x: Float, y: Float, z: Float, timestampMs: Long): Boolean {
        val excess = sqrt(x * x + y * y + z * z) - GRAVITY
        if (cooldownUntil != NONE && timestampMs < cooldownUntil) {
            if (excess < rearmExcess) armed = true
            return false
        }
        if (!armed) {
            if (excess < rearmExcess) armed = true
            return false
        }
        if (excess <= peakExcess) return false

        // A new peak.
        armed = false
        val previous = lastPeakAt
        if (previous != NONE) {
            val gap = timestampMs - previous
            if (gap < minPeakGapMs) return false // ringing of the same jolt; keep the first peak
            if (gap <= windowMs) {
                lastPeakAt = NONE
                cooldownUntil = timestampMs + cooldownMs
                return true
            }
        }
        lastPeakAt = timestampMs
        return false
    }

    fun reset() {
        armed = true
        lastPeakAt = NONE
        cooldownUntil = NONE
    }

    companion object {
        const val GRAVITY = 9.80665f
        const val PEAK_EXCESS = 12f
        const val REARM_EXCESS = 6f
        const val WINDOW_MS = 800L
        const val MIN_PEAK_GAP_MS = 100L
        const val COOLDOWN_MS = 2_000L
        private const val NONE = Long.MIN_VALUE

        /** Listening starts this long before the fade begins (D18: "last 5 minutes + the fade"). */
        const val WINDOW_BEFORE_FADE_MS = 5 * 60_000L

        /** When the shake window opens for a run ending at [deadlineMillis] with a [fadeSeconds] fade. */
        fun windowStartMillis(deadlineMillis: Long, fadeSeconds: Int): Long =
            deadlineMillis - fadeSeconds.coerceAtLeast(0) * 1000L - WINDOW_BEFORE_FADE_MS
    }
}
