package com.plainapps.nightcap.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.plainapps.nightcap.PresetCodec
import com.plainapps.nightcap.TimerRepository
import com.plainapps.nightcap.TimerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only test hook (V2_PLAN "Debug-only test hook"). Never part of the release build: it lives
 * in `src/debug/` and is declared only in the debug manifest.
 *
 *   START --ei seconds N [--es preset_id X]   start a timer (N seconds, optionally as preset X)
 *   SET   --es key K --es value V             change a setting (see [set]; P4: lockAtEnd homeBeforeLock
 *                                              screenOffSooner enhancedCompatibility; P5: dndAtStart
 *                                              dndUntil=HH:MM bedtimeVolume bedtimeVolumePercent schedule
 *                                              scheduleTime=HH:MM scheduleDays=mask(bit0=Mon) schedulePreset;
 *                                              P6: goodnight windDown warmth blackout dimUsing=BOTH|OVERLAY_ONLY)
 *   STOP                                       stop the running timer
 *   ACTION --es action NAME                    fire a notification action (REDUCE, EXTEND, END_NOW, STOP,
 *                                              END_DND), or SHAKE = a detected shake (ignored outside the window)
 *   DUMP                                       log the state as one line, tag SleepTimerDebug
 *   VOLUME --ei index N                        setStreamVolume(MUSIC, N) straight from this background
 *                                              receiver, no FGS: the negative control for Android 17
 *                                              audio hardening (`cmd audio set-enable-hardening 1`)
 *
 * Read results with `adb -s $S logcat -d -s SleepTimerDebug:I TimerService:I`.
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_START -> start(app, intent)
                    ACTION_SET -> set(app, intent)
                    ACTION_STOP -> app.startForegroundService(
                        Intent(app, TimerService::class.java).setAction(com.plainapps.nightcap.TimerActionReceiver.ACTION_STOP),
                    )
                    // Fires a notification action without tapping: --es action REDUCE|EXTEND|END_NOW|STOP
                    ACTION_ACTION -> if (intent.getStringExtra("action") == "END_DND") {
                        com.plainapps.nightcap.RestoreManager.endDnd(app, "debug")
                    } else {
                        app.startForegroundService(
                            Intent(app, TimerService::class.java)
                                .setAction("com.plainapps.nightcap.action." + intent.getStringExtra("action")),
                        )
                    }
                    ACTION_DUMP -> Unit
                    ACTION_VOLUME -> com.plainapps.nightcap.MediaController(app).let { mc ->
                        val before = mc.currentStreamIndex()
                        mc.setStreamIndex(intent.getIntExtra("index", 5))
                        Log.i(TAG, "VOLUME from background: before=$before requested=${intent.getIntExtra("index", 5)} after=${mc.currentStreamIndex()}")
                    }
                }
                dump(app, intent.action.orEmpty())
            } catch (e: Exception) {
                Log.e(TAG, "command failed: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun start(context: Context, intent: Intent) {
        val seconds = intent.getIntExtra("seconds", 60)
        val serviceIntent = Intent(context, TimerService::class.java)
            .setAction(TimerService.ACTION_START)
            // Whole minutes go through the normal extra; sub-minute test runs use the seconds extra.
            .putExtra(TimerService.EXTRA_DURATION_MINUTES, 1)
            .putExtra(TimerService.EXTRA_DEBUG_SECONDS, seconds)
        intent.getStringExtra("preset_id")?.let { serviceIntent.putExtra(TimerService.EXTRA_PRESET_ID, it) }
        context.startForegroundService(serviceIntent)
    }

    private suspend fun set(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val value = intent.getStringExtra("value") ?: return
        val repo = TimerRepository(context)
        val s = repo.currentSettings()
        val updated = when (key) {
            "fade_seconds" -> s.copy(fadeSeconds = value.toInt())
            "extend_step" -> s.copy(extendStepMinutes = value.toInt())
            "mute_fallback" -> s.copy(muteFallbackEnabled = value.toBoolean())
            "restore_volume" -> s.copy(restoreVolumeAfterPause = value.toBoolean())
            "shake" -> s.copy(shakeToExtendEnabled = value.toBoolean())
            // P4. These bypass the in-app disclosure on purpose (test hook); grant the access with adb.
            "lockAtEnd" -> s.copy(lockAtEnd = value.toBoolean())
            "homeBeforeLock" -> s.copy(homeBeforeLock = value.toBoolean())
            "screenOffSooner" -> s.copy(screenOffSoonerFallback = value.toBoolean())
            "enhancedCompatibility" -> s.copy(enhancedCompatibility = value.toBoolean())
            // P5. dndAtStart bypasses the explanation screen (grant with `cmd notification allow_dnd`).
            "dndAtStart" -> s.copy(dndAtStart = value.toBoolean())
            "dndUntil" -> s.copy(dndUntilMinuteOfDay = minuteOfDay(value))
            "bedtimeVolume" -> s.copy(bedtimeVolumeEnabled = value.toBoolean())
            "bedtimeVolumePercent" -> s.copy(bedtimeVolumePercent = value.toInt())
            "schedule" -> s.copy(scheduleEnabled = value.toBoolean())
            "scheduleTime" -> s.copy(scheduleMinuteOfDay = minuteOfDay(value))
            "scheduleDays" -> s.copy(scheduleWeekdayMask = value.toInt())
            "schedulePreset" -> s.copy(schedulePresetId = value)
            // P6. Bypass the explanation screens (grant with appops / the accessibility secure setting).
            "goodnight" -> s.copy(goodnightEnabled = value.toBoolean())
            "windDown" -> s.copy(windDownMinutes = value.toInt())
            "warmth" -> s.copy(goodnightWarmth = value.toBoolean())
            "blackout" -> s.copy(goodnightBlackout = value.toBoolean())
            "dimUsing" -> s.copy(dimUsing = com.plainapps.nightcap.DimUsing.valueOf(value))
            // Raw codec string, e.g. "v1|id=p1,min=1,fade=10;id=p2,min=30".
            "presets" -> PresetCodec.decode(value)?.let { s.copy(presets = it) } ?: s
            else -> s.also { Log.w(TAG, "unknown key $key") }
        }
        repo.saveSettings(updated)
        com.plainapps.nightcap.TimerUi.settingsChanged(context)
        com.plainapps.nightcap.SystemAlarms.syncSchedule(context, 0L, "debug SET")
    }

    private fun minuteOfDay(hhmm: String): Int = hhmm.substringBefore(':').toInt() * 60 + hhmm.substringAfter(':').toInt()

    private suspend fun dump(context: Context, cmd: String) {
        val repo = TimerRepository(context)
        val st = repo.currentState()
        val se = repo.currentSettings()
        val owed = repo.currentOwedRestore()
        val access = com.plainapps.nightcap.SpecialAccess.snapshot(context)
        Log.i(
            TAG,
            "cmd=$cmd phase=${st.phase} done=${st.doneReason} deadline=${st.deadlineWallClockMillis} " +
                "runFade=${st.fadeSeconds} runPreset=${st.presetId} runLabel=${st.presetLabel} " +
                "runRestore=${st.restoreVolumeAfterPause} origIdx=${st.originalStreamIndex} | " +
                "fade=${se.fadeSeconds} extend=${se.extendStepMinutes} restore=${se.restoreVolumeAfterPause} " +
                "mute=${se.muteFallbackEnabled} shake=${se.shakeToExtendEnabled} lastPreset=${se.lastUsedPresetId} lastMin=${se.lastUsedDurationMinutes} " +
                "presets=${PresetCodec.encode(se.presets)} | " +
                "lockAtEnd=${se.lockAtEnd} home=${se.homeBeforeLock} sooner=${se.screenOffSoonerFallback} " +
                "enhanced=${se.enhancedCompatibility} runLock=${st.lockAtEnd} runHome=${st.homeBeforeLock} runSooner=${st.screenOffSooner} " +
                "access=$access a11yBound=${com.plainapps.nightcap.SleepAccessibilityService.instance != null} " +
                "owedTimeout=${owed.screenOffTimeout}/${owed.screenOffTimeoutSet} | " +
                "dndAtStart=${se.dndAtStart} dndUntil=${se.dndUntilMinuteOfDay} bedtime=${se.bedtimeVolumeEnabled}/${se.bedtimeVolumePercent} " +
                "schedule=${se.scheduleEnabled} at=${se.scheduleMinuteOfDay} days=${se.scheduleWeekdayMask} schedPreset=${se.schedulePresetId} " +
                "lastHandled=${repo.scheduleLastHandled()} runPreStart=${st.preStartStreamIndex} runBedtime=${st.bedtimeStreamIndex} " +
                "runTouched=${st.volumeTouchedDuringRun} runDnd=${st.dnd} runScheduled=${st.startedBySchedule} " +
                "owedStream=${owed.streamIndex}/${owed.streamIndexSet} owedDnd=${owed.dndActive}/${owed.dndUntilMillis} dndRule=${repo.dndRuleId()} | " +
                "goodnight=${se.goodnightEnabled} windDown=${se.windDownMinutes} warmth=${se.goodnightWarmth} blackout=${se.goodnightBlackout} dimUsing=${se.dimUsing} " +
                "runGoodnight=${st.goodnight?.let { com.plainapps.nightcap.GoodnightCodec.encode(it) }} " +
                "owedBrightness=${owed.brightness}/${owed.brightnessMode} set=${owed.brightnessSet}/${owed.brightnessPrevSet} stepping=${com.plainapps.nightcap.Goodnight.steppingAlive}",
        )
    }

    companion object {
        const val ACTION_START = "com.plainapps.nightcap.debug.START"
        const val ACTION_SET = "com.plainapps.nightcap.debug.SET"
        const val ACTION_STOP = "com.plainapps.nightcap.debug.STOP"
        const val ACTION_ACTION = "com.plainapps.nightcap.debug.ACTION"
        const val ACTION_DUMP = "com.plainapps.nightcap.debug.DUMP"
        const val ACTION_VOLUME = "com.plainapps.nightcap.debug.VOLUME"
        private const val TAG = "SleepTimerDebug"
    }
}
