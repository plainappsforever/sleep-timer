package com.plainapps.nightcap

enum class ThemeMode { SYSTEM, DARK }

enum class RunPhase { IDLE, RUNNING, FADING, DONE }

enum class DoneReason { STOPPED_BY_USER, COMPLETED_PAUSED, COMPLETED_MUTED, NONE }

/** Persisted, single source of truth for the in-progress (or just-finished) timer. */
data class TimerState(
    val phase: RunPhase = RunPhase.IDLE,
    val doneReason: DoneReason = DoneReason.NONE,
    /** Wall-clock millis (System.currentTimeMillis basis) the countdown started. */
    val startWallClockMillis: Long = 0L,
    /** Wall-clock millis the countdown will (or did) end. Used for display and reboot detection. */
    val deadlineWallClockMillis: Long = 0L,
    /** STREAM_MUSIC index captured just before the fade began; -1 if not yet captured. */
    val originalStreamIndex: Int = -1,
    /** Last index we ourselves set, for volume-touch detection. */
    val lastSetStreamIndex: Int = -1,
    val mutedInsteadOfPaused: Boolean = false,
    /** Settings snapshotted at start, so a mid-run settings change never changes this run. */
    val fadeSeconds: Int = 30,
    val extendStepMinutes: Int = 15,
    /** Preset this run started from (null = a custom duration with the global fade), and its name if any. */
    val presetId: String? = null,
    val presetLabel: String? = null,
    val restoreVolumeAfterPause: Boolean = true,
    /** P4 snapshot: lock at the end (preset ?: global), Home first, "Turn screen off sooner" fallback. */
    val lockAtEnd: Boolean = false,
    val homeBeforeLock: Boolean = false,
    val screenOffSooner: Boolean = false,
    /** P5 (D19): media index before the bedtime volume, and the bedtime index we set (-1 = not applied). */
    val preStartStreamIndex: Int = -1,
    val bedtimeStreamIndex: Int = -1,
    /** The user changed the volume during this run: the pause then restores the fade-start index (v1 rule). */
    val volumeTouchedDuringRun: Boolean = false,
    /** P5: DND was turned on by this run's start; the run was started by the nightly schedule. */
    val dnd: Boolean = false,
    val startedBySchedule: Boolean = false,
    /** P6 (SPEC F20): this run's Goodnight sequence (null = none); see [GoodnightRun]. */
    val goodnight: GoodnightRun? = null,
    /** v2.1: automatic (volume-touch) extends so far in this run, capped by [TimerPlan.MAX_AUTO_EXTENDS]. */
    val autoExtendCount: Int = 0,
) {
    val isActive: Boolean get() = phase == RunPhase.RUNNING || phase == RunPhase.FADING
}

/** User-configurable settings, independent of any in-progress timer. */
data class TimerSettings(
    val fadeSeconds: Int = 30,
    val extendStepMinutes: Int = 15,
    val presets: List<Preset> = Presets.defaults(),
    /** Preset chosen by the last Start (null = custom duration); the tile/widget start this again. */
    val lastUsedPresetId: String? = null,
    /** After the pause, put the volume back to what it was before the fade (default on). */
    val restoreVolumeAfterPause: Boolean = true,
    val muteFallbackEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val lastUsedDurationMinutes: Int = 30,
    val shakeToExtendEnabled: Boolean = false,
    /** Set once a completed timer's end sequence started more than 60s after its deadline (F6). */
    val everLateCompletion: Boolean = false,
    /** P4 (SPEC F16/F17): all off by default; each needs a special access granted when turned on. */
    val lockAtEnd: Boolean = false,
    val homeBeforeLock: Boolean = false,
    val screenOffSoonerFallback: Boolean = false,
    val enhancedCompatibility: Boolean = false,
    /** P5 (SPEC F18/F19): all off by default; DND needs Do Not Disturb access, granted when turned on. */
    val dndAtStart: Boolean = false,
    val dndUntilMinuteOfDay: Int = 7 * 60,
    val bedtimeVolumeEnabled: Boolean = false,
    val bedtimeVolumePercent: Int = 30,
    val scheduleEnabled: Boolean = false,
    val scheduleMinuteOfDay: Int = 23 * 60,
    val scheduleWeekdayMask: Int = Schedule.ALL_DAYS,
    /** Preset the schedule starts (null or deleted = the first preset). */
    val schedulePresetId: String? = null,
    /** Read-only here (written by ScheduleReceiver): the occurrence last handled, so "Next" matches alarm 104. */
    val scheduleLastHandledMillis: Long? = null,
    /** P6 (SPEC F20): off by default; needs our accessibility overlay or Modify system settings (D42). */
    val goodnightEnabled: Boolean = false,
    val windDownMinutes: Int = GoodnightPlan.DEFAULT_WIND_DOWN_MINUTES,
    val goodnightWarmth: Boolean = false,
    val goodnightBlackout: Boolean = false,
    val dimUsing: DimUsing = DimUsing.BOTH,
)

/**
 * Phone settings we changed and still owe back (V2_PLAN "Architecture", rule 8). Written BEFORE the
 * change, cleared after the restore; survives the end of the run and process death. P4 owns the
 * screen timeout; P5/P6 add their fields.
 */
data class OwedRestore(
    /** The user's SCREEN_OFF_TIMEOUT before "Turn screen off sooner", and the value we wrote. */
    val screenOffTimeout: Int? = null,
    val screenOffTimeoutSet: Int? = null,
    /** P5: media index before the bedtime volume, and the index we set. */
    val streamIndex: Int? = null,
    val streamIndexSet: Int? = null,
    /** P5: our Do Not Disturb is on until [dndUntilMillis] (alarm 103). */
    val dndActive: Boolean = false,
    val dndUntilMillis: Long = 0L,
    /** P6 (SYSTEM method only): the user's brightness + mode before Goodnight, our last and previous write. */
    val brightness: Int? = null,
    val brightnessMode: Int? = null,
    val brightnessSet: Int? = null,
    val brightnessPrevSet: Int? = null,
) {
    val isEmpty: Boolean get() = screenOffTimeout == null && streamIndex == null && !dndActive && brightness == null
}
