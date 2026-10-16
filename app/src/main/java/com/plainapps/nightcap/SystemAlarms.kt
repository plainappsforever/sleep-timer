package com.plainapps.nightcap

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.time.ZonedDateTime

/**
 * P5 alarms that live independently of a running timer (V2_PLAN "Pipelines"): 103 = our Do Not Disturb
 * ends at the "until" time, 104 = the next nightly automatic start (D19/D20). Both
 * setExactAndAllowWhileIdle (USE_EXACT_ALARM, D6), never setAlarmClock. Also switches [BootReceiver] on
 * only while something needs re-arming after a reboot or clock change: a schedule, or our DND.
 */
object SystemAlarms {
    private const val TAG = "SystemAlarms"
    private const val REQUEST_CODE_DND_OFF = 103
    private const val REQUEST_CODE_SCHEDULE = 104
    const val EXTRA_SCHEDULED_AT = "scheduled_at"

    /**
     * Arms (or cancels) alarm 104 from the saved schedule. [graceMillis] = 0 for settings changes and app
     * open, [Schedule.GRACE_MILLIS] after boot / clock change / update (Schedule.nextToArm). Returns the
     * armed time, or null when no schedule is on.
     */
    suspend fun syncSchedule(context: Context, graceMillis: Long, reason: String): ZonedDateTime? {
        val app = context.applicationContext
        val repo = TimerRepository(app)
        val s = repo.currentSettings()
        val next = if (s.scheduleEnabled) {
            Schedule.nextToArm(ZonedDateTime.now(), s.scheduleMinuteOfDay, s.scheduleWeekdayMask, repo.scheduleLastHandled(), graceMillis)
        } else {
            null
        }
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (next == null) {
            am.cancel(schedulePendingIntent(app, 0L))
            Log.i(TAG, "schedule off ($reason)")
        } else {
            val at = next.toInstant().toEpochMilli()
            setExact(am, at, schedulePendingIntent(app, at))
            Log.i(TAG, "schedule armed for $next ($reason)")
        }
        refreshBootReceiver(app)
        return next
    }

    fun armDndOff(context: Context, untilMillis: Long) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        setExact(am, untilMillis, dndOffPendingIntent(app))
        updateBootReceiver(app, true)
        Log.i(TAG, "DND off armed for $untilMillis")
    }

    suspend fun cancelDndOff(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(dndOffPendingIntent(app))
        refreshBootReceiver(app)
    }

    /**
     * BootReceiver is needed while a schedule exists, our DND is on, or a Goodnight brightness is owed (P6: a reboot
     * mid-wind-down must not leave the phone at the floor — L13 review), or a run is active (v2.1: a reboot mid-run
     * hands the run back to the service). Every caller uses this one rule.
     */
    suspend fun refreshBootReceiver(context: Context) {
        val app = context.applicationContext
        val repo = TimerRepository(app)
        val owed = repo.currentOwedRestore()
        updateBootReceiver(
            app,
            repo.currentSettings().scheduleEnabled || owed.dndActive || owed.brightness != null || repo.currentState().isActive,
        )
    }

    private fun setExact(am: AlarmManager, atMillis: Long, pi: PendingIntent) {
        val exactAvailable = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exactAvailable) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                return
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm denied: ${e.message}")
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }

    private fun schedulePendingIntent(app: Context, scheduledAt: Long): PendingIntent = PendingIntent.getBroadcast(
        app,
        REQUEST_CODE_SCHEDULE,
        Intent(app, ScheduleReceiver::class.java).putExtra(EXTRA_SCHEDULED_AT, scheduledAt),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun dndOffPendingIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app,
        REQUEST_CODE_DND_OFF,
        Intent(app, TimerActionReceiver::class.java).setAction(TimerActionReceiver.ACTION_END_DND_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun updateBootReceiver(app: Context, needed: Boolean) {
        val state = if (needed) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val component = ComponentName(app, BootReceiver::class.java)
        if (app.packageManager.getComponentEnabledSetting(component) != state) {
            app.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            Log.i(TAG, "BootReceiver ${if (needed) "enabled" else "disabled"}")
        }
    }
}
