package com.plainapps.nightcap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Alarm 104, the nightly automatic start (SPEC F19, D20). Records the occurrence as handled and re-arms
 * the next one first, then starts TimerService with the schedule's preset (exact-alarm FGS start
 * exemption) unless a timer is already running or the alarm arrived more than [Schedule.GRACE_MILLIS] late.
 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val scheduledAt = intent.getLongExtra(SystemAlarms.EXTRA_SCHEDULED_AT, 0L)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val repo = TimerRepository(app)
                val now = System.currentTimeMillis()
                RestoreManager.expireStaleRun(app, "scheduled start")
                if (scheduledAt > 0L) repo.saveScheduleLastHandled(scheduledAt)
                SystemAlarms.syncSchedule(app, 0L, "fired")
                val settings = repo.currentSettings()
                val preset = settings.presets.firstOrNull { it.id == settings.schedulePresetId } ?: settings.presets.first()
                when {
                    !settings.scheduleEnabled -> Log.i(TAG, "fired but the schedule is off")
                    scheduledAt > 0L && !Schedule.isTimely(scheduledAt, now) ->
                        Log.i(TAG, "skipped: ${(now - scheduledAt) / 60_000} min late")
                    repo.currentState().isActive -> Log.i(TAG, "skipped: a timer is already running")
                    else -> {
                        Log.i(TAG, "starting preset ${preset.id} (scheduled $scheduledAt)")
                        TimerNotifications.ensureChannels(app)
                        try {
                            app.startForegroundService(
                                Intent(app, TimerService::class.java)
                                    .setAction(TimerService.ACTION_START)
                                    .putExtra(TimerService.EXTRA_PRESET_ID, preset.id)
                                    .putExtra(TimerService.EXTRA_SCHEDULED, true),
                            )
                        } catch (e: IllegalStateException) {
                            // API 31-32 if the user revoked "Alarms & reminders" (v2.1 declares SCHEDULE_EXACT_ALARM for 31-32,
                            // granted by default): background FGS start refused (L13 review). One tap instead.
                            Log.w(TAG, "background start refused, posting tap-to-start: ${e.message}")
                            try {
                                NotificationManagerCompat.from(app).notify(
                                    TimerNotifications.NOTIF_ID_RESULT,
                                    TimerNotifications.buildScheduleFallbackNotification(app, preset.id, preset.label),
                                )
                            } catch (se: SecurityException) {
                                Log.w(TAG, "no notification permission: ${se.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "scheduled start failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ScheduleReceiver"
    }
}
