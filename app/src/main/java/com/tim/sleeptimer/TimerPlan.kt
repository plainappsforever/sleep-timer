package com.tim.sleeptimer

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Pure Kotlin planning logic for the sleep timer: volume ramp schedules, end-time to duration
 * conversion, extend clamping, volume-touch detection and the pause-ladder decision. No Android
 * imports, so this runs under a plain JVM/JUnit test with no emulator.
 */

/** One step of a volume ramp: wait [delayMillis], then set STREAM_MUSIC to [targetIndex]. */
data class FadeStep(val targetIndex: Int, val delayMillis: Long)

/** SESSION = rung 0, enhanced compatibility (D17): direct `transportControls.pause()` on playing sessions. */
enum class PauseRung { SESSION, MEDIA_KEY, AUDIO_FOCUS, MUTE }

data class PauseLadderResult(val stoppedAtRung: PauseRung, val muted: Boolean)

/** v2.1 (REVIEW_V2 row 7): what follows a re-pause after the restore. */
enum class RepauseOutcome { RESTORE_AGAIN, STAY_QUIET, MUTE }

/** v2.1: a volume difference during the fade is nothing, a Bluetooth echo of our own write, or a real touch. */
enum class TouchVerdict { NONE, ECHO, TOUCHED }

object TimerPlan {

    const val MAX_DURATION_MINUTES = 480
    const val MIN_STREAM_INDEX = 1
    const val MUTED_STREAM_INDEX = 0

    /**
     * Builds an evenly-spaced ramp from [fromIndex] to [toIndex] over [durationSeconds],
     * one step per index. Empty if there is nothing to do (zero duration, or already there).
     * Delays sum exactly to durationSeconds * 1000 (remainder millis front-loaded).
     */
    fun rampSteps(fromIndex: Int, toIndex: Int, durationSeconds: Int): List<FadeStep> {
        val steps = abs(toIndex - fromIndex)
        if (durationSeconds <= 0 || steps <= 0) return emptyList()
        val direction = if (toIndex > fromIndex) 1 else -1
        val totalMillis = durationSeconds * 1000L
        val base = totalMillis / steps
        val remainder = totalMillis % steps
        return (1..steps).map { i ->
            val delay = base + if (i <= remainder) 1L else 0L
            FadeStep(targetIndex = fromIndex + direction * i, delayMillis = delay)
        }
    }

    /** Fade-out schedule: [originalIndex] down to [MIN_STREAM_INDEX] (never 0) over [fadeSeconds]. */
    fun fadeSteps(originalIndex: Int, fadeSeconds: Int): List<FadeStep> =
        rampSteps(fromIndex = originalIndex, toIndex = MIN_STREAM_INDEX, durationSeconds = fadeSeconds)

    /** Ramp-up schedule used when the user extends mid-fade: back to [originalIndex] over [seconds]. */
    fun rampUpSteps(currentIndex: Int, originalIndex: Int, seconds: Int = 3): List<FadeStep> =
        rampSteps(fromIndex = currentIndex, toIndex = originalIndex, durationSeconds = seconds)

    /**
     * True if the actual stream index no longer matches the last index we set ourselves —
     * meaning the user (or another app) touched the volume during the fade.
     */
    fun volumeWasTouched(lastSetIndex: Int, actualIndex: Int): Boolean = actualIndex != lastSetIndex

    /**
     * v2.1 (REVIEW_V2 row 4): Bluetooth absolute volume can report the headset's own level back right after our write,
     * often one step off after rounding. A one-step difference seen within [ECHO_WINDOW_MS] of our own write is that
     * echo (the fade adopts it as its new "last set"); anything else that differs is the user (or another app).
     */
    const val ECHO_WINDOW_MS = 1_000L
    const val ECHO_POLL_MS = 100L

    /** Safety net: at most this many automatic (volume-touch) extends per run; +extend taps are not counted. */
    const val MAX_AUTO_EXTENDS = 3

    fun touchVerdict(lastSetIndex: Int, actualIndex: Int, millisSinceOurWrite: Long): TouchVerdict = when {
        actualIndex == lastSetIndex -> TouchVerdict.NONE
        millisSinceOurWrite <= ECHO_WINDOW_MS && abs(actualIndex - lastSetIndex) == 1 -> TouchVerdict.ECHO
        else -> TouchVerdict.TOUCHED
    }

    fun autoExtendAllowed(autoExtendsSoFar: Int): Boolean = autoExtendsSoFar < MAX_AUTO_EXTENDS

    /** Decides which rung of the pause ladder stopped playback, from the two isMusicActive() polls. */
    fun evaluatePauseLadder(activeAfterMediaKey: Boolean, activeAfterAudioFocus: Boolean): PauseLadderResult =
        when {
            !activeAfterMediaKey -> PauseLadderResult(PauseRung.MEDIA_KEY, muted = false)
            !activeAfterAudioFocus -> PauseLadderResult(PauseRung.AUDIO_FOCUS, muted = false)
            else -> PauseLadderResult(PauseRung.MUTE, muted = true)
        }

    /**
     * Ladder with the optional rung 0 (D17): when rung 0 ran ([sessionRungTried]) and playback is quiet
     * afterwards, it stopped there and rungs 1-3 never ran; otherwise the v1 decision applies.
     */
    fun evaluatePauseLadder(
        sessionRungTried: Boolean,
        activeAfterSession: Boolean,
        activeAfterMediaKey: Boolean,
        activeAfterAudioFocus: Boolean,
    ): PauseLadderResult =
        if (sessionRungTried && !activeAfterSession) {
            PauseLadderResult(PauseRung.SESSION, muted = false)
        } else {
            evaluatePauseLadder(activeAfterMediaKey, activeAfterAudioFocus)
        }

    /** Volume should only be restored once playback is confirmed quiet and we did not fall back to mute. */
    fun shouldRestoreVolume(muted: Boolean, isQuietNow: Boolean, restoreEnabled: Boolean = true): Boolean =
        restoreEnabled && !muted && isQuietNow

    /**
     * v2.1 (REVIEW_V2 row 7): one quiet sample after the ladder can be a pause between tracks, buffering or an "Up next"
     * countdown. After the restore the service keeps listening this long; sound while the volume is still the one we
     * restored means the player did not really pause.
     */
    const val POST_RESTORE_WATCH_MS = 20_000L
    const val POST_RESTORE_POLL_MS = 500L

    /** Sound again after our restore, and the volume is still ours (the user did not take over): quiet it and pause again. */
    fun repauseNeeded(soundNow: Boolean, currentIndex: Int, restoredIndex: Int): Boolean = soundNow && currentIndex == restoredIndex

    /**
     * After the re-pause: quiet -> restore once more (only after the first re-pause; a second comeback is not trusted);
     * still playing -> the mute rung (or, with mute off, stay at the faded level) and the volume is owed.
     */
    fun afterRepause(stillPlaying: Boolean, repausesBefore: Int, muteFallbackEnabled: Boolean): RepauseOutcome = when {
        !stillPlaying && repausesBefore == 0 -> RepauseOutcome.RESTORE_AGAIN
        !stillPlaying -> RepauseOutcome.STAY_QUIET
        muteFallbackEnabled -> RepauseOutcome.MUTE
        else -> RepauseOutcome.STAY_QUIET
    }

    /** Bedtime volume (D19): [percent] of [maxIndex], rounded, at least [MIN_STREAM_INDEX]. */
    fun bedtimeIndex(percent: Int, maxIndex: Int): Int =
        Math.round(maxIndex * percent.coerceIn(0, 100) / 100.0).toInt().coerceIn(MIN_STREAM_INDEX, maxOf(MIN_STREAM_INDEX, maxIndex))

    /**
     * The index to set at timer start, or null to leave the volume alone: bedtime volume off, or the
     * current volume is already at or below it (bedtime volume only ever lowers, D39).
     */
    fun bedtimeTarget(currentIndex: Int, percent: Int?, maxIndex: Int): Int? {
        if (percent == null || percent <= 0 || maxIndex <= 0) return null
        val target = bedtimeIndex(percent, maxIndex)
        return target.takeIf { it < currentIndex }
    }

    /**
     * At fade start: the volume is no longer the bedtime index we set, so the user changed it. v2.1: one step off is
     * not counted — it is what a Bluetooth headset's volume echo looks like (REVIEW_V2 row 4), and the pre-start
     * volume is the user's own anyway.
     */
    fun touchedSinceBedtime(bedtimeIndexSet: Int, indexNow: Int): Boolean = bedtimeIndexSet >= 0 && abs(indexNow - bedtimeIndexSet) > 1

    /**
     * Where the volume goes after a confirmed pause (D19 restore rule): nowhere when muted, still
     * playing, or "Restore volume after pause" is off; to the pre-start index when a bedtime volume was
     * applied and the user did not touch the volume during the run; else to the fade-start index (v1).
     */
    fun restoreTargetIndex(
        muted: Boolean,
        isQuietNow: Boolean,
        restoreEnabled: Boolean,
        fadeStartIndex: Int,
        preStartIndex: Int,
        touched: Boolean,
    ): Int? {
        if (!shouldRestoreVolume(muted, isQuietNow, restoreEnabled)) return null
        if (preStartIndex >= 0 && !touched) return preStartIndex
        return fadeStartIndex.takeIf { it >= 0 }
    }

    /**
     * Stop tapped: a fade in progress goes back to its start index (v1); an untouched bedtime volume
     * goes back to the pre-start index only while nothing is playing (no loud jump into a dark room
     * while the user keeps listening; then the bedtime level stays, D39). Null = leave the volume.
     */
    fun stopRestoreIndex(fading: Boolean, fadeStartIndex: Int, preStartIndex: Int, touched: Boolean, isPlaying: Boolean): Int? {
        if (preStartIndex >= 0 && !touched && !isPlaying) return preStartIndex
        if (fading && fadeStartIndex >= 0) return fadeStartIndex
        return null
    }

    /** A run still active this long after its deadline has no end sequence coming (reboot, D10). */
    const val STALE_AFTER_DEADLINE_MS = 10 * 60_000L

    fun isStale(active: Boolean, deadlineMillis: Long, nowMillis: Long): Boolean =
        active && nowMillis > deadlineMillis + STALE_AFTER_DEADLINE_MS

    const val REDUCE_STEP_MINUTES = 5
    private const val REDUCE_MIN_LEAD_SECONDS = 60
    private const val END_NOW_MAX_FADE_SECONDS = 10

    /**
     * The "-5 min" notification action (D26): [deadlineMillis] minus 5 minutes, but never closer than
     * fade + 60 s from [nowMillis], and never later than the current deadline (a deadline that is
     * already that close is left alone rather than pushed out).
     */
    fun reducedDeadline(nowMillis: Long, deadlineMillis: Long, fadeSeconds: Int): Long {
        val floor = nowMillis + (fadeSeconds + REDUCE_MIN_LEAD_SECONDS) * 1000L
        val proposed = deadlineMillis - REDUCE_STEP_MINUTES * 60_000L
        return minOf(deadlineMillis, maxOf(floor, proposed))
    }

    /** "End now" runs the end sequence at once with a fade of at most 10 s (D26). */
    fun endNowFadeSeconds(presetFadeSeconds: Int): Int = presetFadeSeconds.coerceIn(0, END_NOW_MAX_FADE_SECONDS)

    /**
     * Sequence part of the notification line: "Fade 30 s → pause", or "Pause" without a fade, plus
     * " → lock" / " → screen off" when the run ends with a screen action (P4), and "Dim 15 min → " first when the
     * run has a Goodnight wind-down (P6): "Dim 15 min → fade 30 s → pause → lock".
     */
    fun endSequenceSummary(fadeSeconds: Int, screenAction: ScreenAction = ScreenAction.NONE, dimMinutes: Int? = null): String {
        val rest = (if (fadeSeconds > 0) "Fade $fadeSeconds s → pause" else "Pause") + EndActions.summarySuffix(screenAction)
        return if (dimMinutes == null) rest else "Dim $dimMinutes min → " + rest.replaceFirstChar { it.lowercase() }
    }

    /**
     * Minutes from [now] until the next occurrence of [targetHour]:[targetMinute] (rolls to
     * tomorrow if that time today is not after now), clamped to [1, MAX_DURATION_MINUTES].
     */
    fun minutesUntil(now: LocalDateTime, targetHour: Int, targetMinute: Int): Int {
        var target = now.toLocalDate().atTime(targetHour, targetMinute)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val minutes = Duration.between(now, target).toMinutes().toInt()
        return minutes.coerceIn(1, MAX_DURATION_MINUTES)
    }

    /**
     * New deadline after an extend, clamped so total elapsed time since [startMillis] never
     * exceeds [MAX_DURATION_MINUTES].
     */
    fun clampedExtendedDeadline(startMillis: Long, currentDeadlineMillis: Long, extendMillis: Long): Long {
        val proposed = currentDeadlineMillis + extendMillis
        val maxDeadline = startMillis + MAX_DURATION_MINUTES * 60_000L
        return minOf(proposed, maxDeadline)
    }

    /** Clamps a requested duration (minutes) into the product's allowed range. */
    fun clampDurationMinutes(minutes: Int): Int = minutes.coerceIn(1, MAX_DURATION_MINUTES)
}
