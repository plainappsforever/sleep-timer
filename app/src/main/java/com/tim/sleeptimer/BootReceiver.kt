package com.tim.sleeptimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED, MY_PACKAGE_REPLACED (D20). Disabled in the manifest and
 * enabled by [SystemAlarms] only while a schedule exists, our Do Not Disturb is on, a Goodnight brightness is owed
 * (P6; paid here, Settings.System needs no foreground) or a run is active (v2.1: the run is handed to the service). Re-arms alarm 104
 * (a start missed a moment ago still fires, once) and alarm 103 (a DND whose morning already passed ends
 * now). Changes no volume itself (Android 17 background rules); v2.1: starts the specialUse service for an active
 * run (BOOT_COMPLETED / MY_PACKAGE_REPLACED are FGS-start exemptions; specialUse is not one of the types Android 15
 * bars from BOOT_COMPLETED).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Log.i(TAG, "received ${intent.action}")
                RestoreManager.applyOwedDnd(app, "boot/time change")
                // P6: a Goodnight brightness left by a run of an earlier boot (never while a run of this boot dims).
                RestoreManager.applyOwedBrightness(app, intent.action ?: "boot")
                SystemAlarms.syncSchedule(app, Schedule.GRACE_MILLIS, intent.action ?: "boot")
                // v2.1 (REVIEW_V2 rows 1, 5): a run that was active when the phone restarted (or the app was updated)
                // has lost its alarms and its process. The service takes it over: the countdown resumes with its
                // notification, or an end that is due completes without a screen action (the user is using the phone).
                if ((intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) &&
                    TimerRepository(app).currentState().isActive
                ) {
                    try {
                        app.startForegroundService(
                            Intent(app, TimerService::class.java)
                                .setAction(TimerService.ACTION_RECOVER)
                                .putExtra(TimerService.EXTRA_REASON, intent.action)
                                .putExtra(TimerService.EXTRA_QUIET_END, true),
                        )
                        Log.i(TAG, "active run handed to the service")
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "service start refused: ${e.message}") // app open / tile still recover it
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "re-arm failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
