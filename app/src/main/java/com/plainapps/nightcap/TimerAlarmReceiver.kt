package com.plainapps.nightcap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fired by AlarmManager (exact, or the Fallback A watchdog) at deadline − fade − 5s (request code 100),
 * at the shake-window start (101, action SHAKE_WINDOW), or at the Goodnight wind-down start / watchdog (102, WIND_DOWN), or as the run's watchdog (105, WATCHDOG, v2.1). Hands off to TimerService, starting it if
 * the process was killed (exact-alarm start exemption, DECISIONS D6).
 */
class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = when (intent.action) {
            TimerService.ACTION_SHAKE_WINDOW -> TimerService.ACTION_SHAKE_WINDOW
            TimerService.ACTION_WIND_DOWN -> TimerService.ACTION_WIND_DOWN // P6 alarm 102, never the end sequence
            TimerService.ACTION_WATCHDOG -> TimerService.ACTION_WATCHDOG // v2.1 alarm 105, recovery after process death
            else -> TimerService.ACTION_ALARM_FIRED
        }
        val serviceIntent = Intent(context, TimerService::class.java).setAction(action)
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: IllegalStateException) {
            // v2.1 (REVIEW_V2 row 6): an inexact Fallback-A alarm may not start a foreground service from the
            // background (Android 12+). Never crash here: a live process continues with its own ticker, and the
            // watchdog / app open / boot recover the run otherwise.
            Log.w("TimerAlarmReceiver", "service start refused for $action: ${e.message}")
        }
    }
}
