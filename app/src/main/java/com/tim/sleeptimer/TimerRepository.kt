package com.tim.sleeptimer

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "sleep_timer")

/** DataStore-backed single source of truth for the running timer and for user settings. */
class TimerRepository(private val context: Context) {

    private object Keys {
        val PHASE = stringPreferencesKey("phase")
        val DONE_REASON = stringPreferencesKey("done_reason")
        val START_WALL_CLOCK = longPreferencesKey("start_wall_clock")
        val DEADLINE_WALL_CLOCK = longPreferencesKey("deadline_wall_clock")
        val ORIGINAL_STREAM_INDEX = intPreferencesKey("original_stream_index")
        val LAST_SET_STREAM_INDEX = intPreferencesKey("last_set_stream_index")
        val MUTED_INSTEAD_OF_PAUSED = booleanPreferencesKey("muted_instead_of_paused")
        val RUN_FADE_SECONDS = intPreferencesKey("run_fade_seconds")
        val RUN_EXTEND_STEP_MINUTES = intPreferencesKey("run_extend_step_minutes")
        val RUN_PRESET_ID = stringPreferencesKey("run_preset_id")
        val RUN_PRESET_LABEL = stringPreferencesKey("run_preset_label")
        val RUN_RESTORE_VOLUME = booleanPreferencesKey("run_restore_volume")
        val RUN_LOCK_AT_END = booleanPreferencesKey("run_lock_at_end")
        val RUN_HOME_BEFORE_LOCK = booleanPreferencesKey("run_home_before_lock")
        val RUN_SCREEN_OFF_SOONER = booleanPreferencesKey("run_screen_off_sooner")
        val RUN_PRE_START_INDEX = intPreferencesKey("run_pre_start_index")
        val RUN_BEDTIME_INDEX = intPreferencesKey("run_bedtime_index")
        val RUN_VOLUME_TOUCHED = booleanPreferencesKey("run_volume_touched")
        val RUN_DND = booleanPreferencesKey("run_dnd")
        val RUN_SCHEDULED = booleanPreferencesKey("run_scheduled")
        val RUN_GOODNIGHT = stringPreferencesKey("run_goodnight")
        val RUN_AUTO_EXTENDS = intPreferencesKey("run_auto_extends")

        val FADE_SECONDS = intPreferencesKey("fade_seconds")
        val EXTEND_STEP_MINUTES = intPreferencesKey("extend_step_minutes")
        /** v1 chip minutes ("15,30,…"); read only to migrate into [PRESETS]. */
        val PRESETS_MINUTES = stringPreferencesKey("presets_minutes")
        val PRESETS = stringPreferencesKey("presets_v1")
        val LAST_USED_PRESET_ID = stringPreferencesKey("last_used_preset_id")
        val RESTORE_VOLUME_AFTER_PAUSE = booleanPreferencesKey("restore_volume_after_pause")
        val MUTE_FALLBACK_ENABLED = booleanPreferencesKey("mute_fallback_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LAST_USED_DURATION_MINUTES = intPreferencesKey("last_used_duration_minutes")
        val SHAKE_TO_EXTEND_ENABLED = booleanPreferencesKey("shake_to_extend_enabled")
        val EVER_LATE_COMPLETION = booleanPreferencesKey("ever_late_completion")
        val LOCK_AT_END = booleanPreferencesKey("lock_at_end")
        val HOME_BEFORE_LOCK = booleanPreferencesKey("home_before_lock")
        val SCREEN_OFF_SOONER = booleanPreferencesKey("screen_off_sooner")
        val ENHANCED_COMPATIBILITY = booleanPreferencesKey("enhanced_compatibility")
        val DND_AT_START = booleanPreferencesKey("dnd_at_start")
        val DND_UNTIL_MINUTE = intPreferencesKey("dnd_until_minute")
        val BEDTIME_VOLUME_ENABLED = booleanPreferencesKey("bedtime_volume_enabled")
        val BEDTIME_VOLUME_PERCENT = intPreferencesKey("bedtime_volume_percent")
        val SCHEDULE_ENABLED = booleanPreferencesKey("schedule_enabled")
        val SCHEDULE_MINUTE = intPreferencesKey("schedule_minute")
        val SCHEDULE_DAYS = intPreferencesKey("schedule_days")
        val SCHEDULE_PRESET_ID = stringPreferencesKey("schedule_preset_id")
        /** Scheduled instant (millis) the last firing handled; never started twice (Schedule.nextToArm). */
        val SCHEDULE_LAST_HANDLED = longPreferencesKey("schedule_last_handled")
        /** Id of our AutomaticZenRule "Sleep timer" (API 29+). */
        val DND_RULE_ID = stringPreferencesKey("dnd_rule_id")
        val GOODNIGHT_ENABLED = booleanPreferencesKey("goodnight_enabled")
        val WIND_DOWN_MINUTES = intPreferencesKey("wind_down_minutes")
        val GOODNIGHT_WARMTH = booleanPreferencesKey("goodnight_warmth")
        val GOODNIGHT_BLACKOUT = booleanPreferencesKey("goodnight_blackout")
        val DIM_USING = stringPreferencesKey("dim_using")

        val OWED_SCREEN_OFF_TIMEOUT = intPreferencesKey("owed_screen_off_timeout")
        val OWED_SCREEN_OFF_TIMEOUT_SET = intPreferencesKey("owed_screen_off_timeout_set")
        val OWED_STREAM_INDEX = intPreferencesKey("owed_stream_index")
        val OWED_STREAM_INDEX_SET = intPreferencesKey("owed_stream_index_set")
        val OWED_DND_ACTIVE = booleanPreferencesKey("owed_dnd_active")
        val OWED_DND_UNTIL = longPreferencesKey("owed_dnd_until")
        val OWED_BRIGHTNESS = intPreferencesKey("owed_brightness")
        val OWED_BRIGHTNESS_MODE = intPreferencesKey("owed_brightness_mode")
        val OWED_BRIGHTNESS_SET = intPreferencesKey("owed_brightness_set")
        val OWED_BRIGHTNESS_PREV_SET = intPreferencesKey("owed_brightness_prev_set")
    }

    val timerState: Flow<TimerState> = context.dataStore.data.map { prefs ->
        TimerState(
            phase = prefs[Keys.PHASE]?.let { runCatching { RunPhase.valueOf(it) }.getOrNull() } ?: RunPhase.IDLE,
            doneReason = prefs[Keys.DONE_REASON]?.let { runCatching { DoneReason.valueOf(it) }.getOrNull() } ?: DoneReason.NONE,
            startWallClockMillis = prefs[Keys.START_WALL_CLOCK] ?: 0L,
            deadlineWallClockMillis = prefs[Keys.DEADLINE_WALL_CLOCK] ?: 0L,
            originalStreamIndex = prefs[Keys.ORIGINAL_STREAM_INDEX] ?: -1,
            lastSetStreamIndex = prefs[Keys.LAST_SET_STREAM_INDEX] ?: -1,
            mutedInsteadOfPaused = prefs[Keys.MUTED_INSTEAD_OF_PAUSED] ?: false,
            fadeSeconds = prefs[Keys.RUN_FADE_SECONDS] ?: 30,
            extendStepMinutes = prefs[Keys.RUN_EXTEND_STEP_MINUTES] ?: 15,
            presetId = prefs[Keys.RUN_PRESET_ID],
            presetLabel = prefs[Keys.RUN_PRESET_LABEL],
            restoreVolumeAfterPause = prefs[Keys.RUN_RESTORE_VOLUME] ?: true,
            lockAtEnd = prefs[Keys.RUN_LOCK_AT_END] ?: false,
            homeBeforeLock = prefs[Keys.RUN_HOME_BEFORE_LOCK] ?: false,
            screenOffSooner = prefs[Keys.RUN_SCREEN_OFF_SOONER] ?: false,
            preStartStreamIndex = prefs[Keys.RUN_PRE_START_INDEX] ?: -1,
            bedtimeStreamIndex = prefs[Keys.RUN_BEDTIME_INDEX] ?: -1,
            volumeTouchedDuringRun = prefs[Keys.RUN_VOLUME_TOUCHED] ?: false,
            dnd = prefs[Keys.RUN_DND] ?: false,
            startedBySchedule = prefs[Keys.RUN_SCHEDULED] ?: false,
            goodnight = GoodnightCodec.decode(prefs[Keys.RUN_GOODNIGHT]),
            autoExtendCount = prefs[Keys.RUN_AUTO_EXTENDS] ?: 0,
        )
    }

    val settings: Flow<TimerSettings> = context.dataStore.data.map { prefs ->
        TimerSettings(
            fadeSeconds = prefs[Keys.FADE_SECONDS] ?: 30,
            extendStepMinutes = prefs[Keys.EXTEND_STEP_MINUTES] ?: 15,
            presets = PresetCodec.decode(prefs[Keys.PRESETS]) ?: Presets.migrateLegacyMinutes(prefs[Keys.PRESETS_MINUTES]),
            lastUsedPresetId = prefs[Keys.LAST_USED_PRESET_ID],
            restoreVolumeAfterPause = prefs[Keys.RESTORE_VOLUME_AFTER_PAUSE] ?: true,
            muteFallbackEnabled = prefs[Keys.MUTE_FALLBACK_ENABLED] ?: true,
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            lastUsedDurationMinutes = prefs[Keys.LAST_USED_DURATION_MINUTES] ?: 30,
            shakeToExtendEnabled = prefs[Keys.SHAKE_TO_EXTEND_ENABLED] ?: false,
            everLateCompletion = prefs[Keys.EVER_LATE_COMPLETION] ?: false,
            lockAtEnd = prefs[Keys.LOCK_AT_END] ?: false,
            homeBeforeLock = prefs[Keys.HOME_BEFORE_LOCK] ?: false,
            screenOffSoonerFallback = prefs[Keys.SCREEN_OFF_SOONER] ?: false,
            enhancedCompatibility = prefs[Keys.ENHANCED_COMPATIBILITY] ?: false,
            dndAtStart = prefs[Keys.DND_AT_START] ?: false,
            dndUntilMinuteOfDay = prefs[Keys.DND_UNTIL_MINUTE] ?: (7 * 60),
            bedtimeVolumeEnabled = prefs[Keys.BEDTIME_VOLUME_ENABLED] ?: false,
            bedtimeVolumePercent = prefs[Keys.BEDTIME_VOLUME_PERCENT] ?: 30,
            scheduleEnabled = prefs[Keys.SCHEDULE_ENABLED] ?: false,
            scheduleMinuteOfDay = prefs[Keys.SCHEDULE_MINUTE] ?: (23 * 60),
            scheduleWeekdayMask = prefs[Keys.SCHEDULE_DAYS] ?: Schedule.ALL_DAYS,
            schedulePresetId = prefs[Keys.SCHEDULE_PRESET_ID],
            scheduleLastHandledMillis = prefs[Keys.SCHEDULE_LAST_HANDLED],
            goodnightEnabled = prefs[Keys.GOODNIGHT_ENABLED] ?: false,
            windDownMinutes = prefs[Keys.WIND_DOWN_MINUTES] ?: GoodnightPlan.DEFAULT_WIND_DOWN_MINUTES,
            goodnightWarmth = prefs[Keys.GOODNIGHT_WARMTH] ?: false,
            goodnightBlackout = prefs[Keys.GOODNIGHT_BLACKOUT] ?: false,
            dimUsing = prefs[Keys.DIM_USING]?.let { runCatching { DimUsing.valueOf(it) }.getOrNull() } ?: DimUsing.BOTH,
        )
    }

    val owedRestore: Flow<OwedRestore> = context.dataStore.data.map { prefs ->
        OwedRestore(
            screenOffTimeout = prefs[Keys.OWED_SCREEN_OFF_TIMEOUT],
            screenOffTimeoutSet = prefs[Keys.OWED_SCREEN_OFF_TIMEOUT_SET],
            streamIndex = prefs[Keys.OWED_STREAM_INDEX],
            streamIndexSet = prefs[Keys.OWED_STREAM_INDEX_SET],
            dndActive = prefs[Keys.OWED_DND_ACTIVE] ?: false,
            dndUntilMillis = prefs[Keys.OWED_DND_UNTIL] ?: 0L,
            brightness = prefs[Keys.OWED_BRIGHTNESS],
            brightnessMode = prefs[Keys.OWED_BRIGHTNESS_MODE],
            brightnessSet = prefs[Keys.OWED_BRIGHTNESS_SET],
            brightnessPrevSet = prefs[Keys.OWED_BRIGHTNESS_PREV_SET],
        )
    }

    /** P5: written before the bedtime volume is set (rule 8). */
    suspend fun saveOwedStreamIndex(original: Int, valueWeSet: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.OWED_STREAM_INDEX] = original
            prefs[Keys.OWED_STREAM_INDEX_SET] = valueWeSet
        }
    }

    /** Clears the stream record only if it still is [expected] (null = whatever is there). */
    suspend fun clearOwedStreamIndex(expected: OwedRestore? = null) {
        context.dataStore.edit { prefs ->
            if (expected == null || (prefs[Keys.OWED_STREAM_INDEX] == expected.streamIndex &&
                    prefs[Keys.OWED_STREAM_INDEX_SET] == expected.streamIndexSet)
            ) {
                prefs.remove(Keys.OWED_STREAM_INDEX)
                prefs.remove(Keys.OWED_STREAM_INDEX_SET)
            }
        }
    }

    /** P5: written before DND is turned on (rule 8). */
    suspend fun saveOwedDnd(untilMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.OWED_DND_ACTIVE] = true
            prefs[Keys.OWED_DND_UNTIL] = untilMillis
        }
    }

    /** Clears the DND record only if its until-time is still [expectedUntil] (a newer run may own it). */
    suspend fun clearOwedDnd(expectedUntil: Long? = null) {
        context.dataStore.edit { prefs ->
            if (expectedUntil == null || prefs[Keys.OWED_DND_UNTIL] == expectedUntil) {
                prefs.remove(Keys.OWED_DND_ACTIVE)
                prefs.remove(Keys.OWED_DND_UNTIL)
            }
        }
    }

    /**
     * P6: the SYSTEM brightness record, written (to disk) BEFORE every Goodnight brightness write (rule 8, Dimmer's
     * journal pattern). An existing record keeps the user's original.
     */
    suspend fun saveOwedBrightness(original: Int, originalMode: Int, set: Int, previousSet: Int) {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.OWED_BRIGHTNESS] == null) {
                prefs[Keys.OWED_BRIGHTNESS] = original
                prefs[Keys.OWED_BRIGHTNESS_MODE] = originalMode
            }
            prefs[Keys.OWED_BRIGHTNESS_SET] = set
            prefs[Keys.OWED_BRIGHTNESS_PREV_SET] = previousSet
        }
    }

    suspend fun clearOwedBrightness() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.OWED_BRIGHTNESS)
            prefs.remove(Keys.OWED_BRIGHTNESS_MODE)
            prefs.remove(Keys.OWED_BRIGHTNESS_SET)
            prefs.remove(Keys.OWED_BRIGHTNESS_PREV_SET)
        }
    }

    /** P6: the run's Goodnight part only (stepping state), without rewriting the rest of the run. */
    suspend fun saveGoodnight(run: GoodnightRun?) {
        context.dataStore.edit { prefs ->
            if (run != null) prefs[Keys.RUN_GOODNIGHT] = GoodnightCodec.encode(run) else prefs.remove(Keys.RUN_GOODNIGHT)
        }
    }

    suspend fun dndRuleId(): String? = context.dataStore.data.first()[Keys.DND_RULE_ID]

    suspend fun saveDndRuleId(id: String) {
        context.dataStore.edit { prefs -> prefs[Keys.DND_RULE_ID] = id }
    }

    suspend fun scheduleLastHandled(): Long? = context.dataStore.data.first()[Keys.SCHEDULE_LAST_HANDLED]

    suspend fun saveScheduleLastHandled(millis: Long) {
        context.dataStore.edit { prefs -> prefs[Keys.SCHEDULE_LAST_HANDLED] = millis }
    }

    suspend fun currentOwedRestore(): OwedRestore = owedRestore.first()

    /** Written before the change it guards (rule 8). */
    suspend fun saveOwedScreenOffTimeout(original: Int, valueWeSet: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.OWED_SCREEN_OFF_TIMEOUT] = original
            prefs[Keys.OWED_SCREEN_OFF_TIMEOUT_SET] = valueWeSet
        }
    }

    /** Clears the record only if it still is [expected] (a newer run may have written a new one). */
    suspend fun clearOwedScreenOffTimeout(expected: OwedRestore) {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.OWED_SCREEN_OFF_TIMEOUT] == expected.screenOffTimeout &&
                prefs[Keys.OWED_SCREEN_OFF_TIMEOUT_SET] == expected.screenOffTimeoutSet
            ) {
                prefs.remove(Keys.OWED_SCREEN_OFF_TIMEOUT)
                prefs.remove(Keys.OWED_SCREEN_OFF_TIMEOUT_SET)
            }
        }
    }

    suspend fun currentState(): TimerState = timerState.first()
    suspend fun currentSettings(): TimerSettings = settings.first()

    suspend fun saveState(state: TimerState) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PHASE] = state.phase.name
            prefs[Keys.DONE_REASON] = state.doneReason.name
            prefs[Keys.START_WALL_CLOCK] = state.startWallClockMillis
            prefs[Keys.DEADLINE_WALL_CLOCK] = state.deadlineWallClockMillis
            prefs[Keys.ORIGINAL_STREAM_INDEX] = state.originalStreamIndex
            prefs[Keys.LAST_SET_STREAM_INDEX] = state.lastSetStreamIndex
            prefs[Keys.MUTED_INSTEAD_OF_PAUSED] = state.mutedInsteadOfPaused
            prefs[Keys.RUN_FADE_SECONDS] = state.fadeSeconds
            prefs[Keys.RUN_EXTEND_STEP_MINUTES] = state.extendStepMinutes
            if (state.presetId != null) prefs[Keys.RUN_PRESET_ID] = state.presetId else prefs.remove(Keys.RUN_PRESET_ID)
            if (state.presetLabel != null) prefs[Keys.RUN_PRESET_LABEL] = state.presetLabel else prefs.remove(Keys.RUN_PRESET_LABEL)
            prefs[Keys.RUN_RESTORE_VOLUME] = state.restoreVolumeAfterPause
            prefs[Keys.RUN_LOCK_AT_END] = state.lockAtEnd
            prefs[Keys.RUN_HOME_BEFORE_LOCK] = state.homeBeforeLock
            prefs[Keys.RUN_SCREEN_OFF_SOONER] = state.screenOffSooner
            prefs[Keys.RUN_PRE_START_INDEX] = state.preStartStreamIndex
            prefs[Keys.RUN_BEDTIME_INDEX] = state.bedtimeStreamIndex
            prefs[Keys.RUN_VOLUME_TOUCHED] = state.volumeTouchedDuringRun
            prefs[Keys.RUN_DND] = state.dnd
            prefs[Keys.RUN_SCHEDULED] = state.startedBySchedule
            prefs[Keys.RUN_AUTO_EXTENDS] = state.autoExtendCount
            // P6: RUN_GOODNIGHT is written only by saveGoodnight (Goodnight.update), never here, so a service
            // handler saving an older state copy cannot undo the stepping loop's changes (L13 review).
        }
    }

    suspend fun saveSettings(settings: TimerSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FADE_SECONDS] = settings.fadeSeconds
            prefs[Keys.EXTEND_STEP_MINUTES] = settings.extendStepMinutes
            prefs[Keys.PRESETS] = PresetCodec.encode(settings.presets)
            if (settings.lastUsedPresetId != null) {
                prefs[Keys.LAST_USED_PRESET_ID] = settings.lastUsedPresetId
            } else {
                prefs.remove(Keys.LAST_USED_PRESET_ID)
            }
            prefs[Keys.RESTORE_VOLUME_AFTER_PAUSE] = settings.restoreVolumeAfterPause
            prefs[Keys.MUTE_FALLBACK_ENABLED] = settings.muteFallbackEnabled
            prefs[Keys.THEME_MODE] = settings.themeMode.name
            prefs[Keys.LAST_USED_DURATION_MINUTES] = settings.lastUsedDurationMinutes
            prefs[Keys.SHAKE_TO_EXTEND_ENABLED] = settings.shakeToExtendEnabled
            prefs[Keys.EVER_LATE_COMPLETION] = settings.everLateCompletion
            prefs[Keys.LOCK_AT_END] = settings.lockAtEnd
            prefs[Keys.HOME_BEFORE_LOCK] = settings.homeBeforeLock
            prefs[Keys.SCREEN_OFF_SOONER] = settings.screenOffSoonerFallback
            prefs[Keys.ENHANCED_COMPATIBILITY] = settings.enhancedCompatibility
            prefs[Keys.DND_AT_START] = settings.dndAtStart
            prefs[Keys.DND_UNTIL_MINUTE] = settings.dndUntilMinuteOfDay
            prefs[Keys.BEDTIME_VOLUME_ENABLED] = settings.bedtimeVolumeEnabled
            prefs[Keys.BEDTIME_VOLUME_PERCENT] = settings.bedtimeVolumePercent
            prefs[Keys.SCHEDULE_ENABLED] = settings.scheduleEnabled
            prefs[Keys.SCHEDULE_MINUTE] = settings.scheduleMinuteOfDay
            prefs[Keys.SCHEDULE_DAYS] = settings.scheduleWeekdayMask
            if (settings.schedulePresetId != null) {
                prefs[Keys.SCHEDULE_PRESET_ID] = settings.schedulePresetId
            } else {
                prefs.remove(Keys.SCHEDULE_PRESET_ID)
            }
            prefs[Keys.GOODNIGHT_ENABLED] = settings.goodnightEnabled
            prefs[Keys.WIND_DOWN_MINUTES] = settings.windDownMinutes
            prefs[Keys.GOODNIGHT_WARMTH] = settings.goodnightWarmth
            prefs[Keys.GOODNIGHT_BLACKOUT] = settings.goodnightBlackout
            prefs[Keys.DIM_USING] = settings.dimUsing.name
        }
    }

    suspend fun markLateCompletion() {
        context.dataStore.edit { prefs -> prefs[Keys.EVER_LATE_COMPLETION] = true }
    }
}
