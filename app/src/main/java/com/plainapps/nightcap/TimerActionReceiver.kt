package com.plainapps.nightcap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Notification action taps (-5, +extend, End now, Stop, Restore volume) are forwarded to TimerService.
 * P5: "End Do Not Disturb" (result notification) and alarm 103 (the DND until-time) are handled here,
 * without starting the service: turning DND off needs no foreground service.
 */
class TimerActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_EXTEND = "com.plainapps.nightcap.action.EXTEND"
        const val ACTION_STOP = "com.plainapps.nightcap.action.STOP"
        const val ACTION_REDUCE = "com.plainapps.nightcap.action.REDUCE"
        const val ACTION_END_NOW = "com.plainapps.nightcap.action.END_NOW"
        const val ACTION_RESTORE_VOLUME = "com.plainapps.nightcap.action.RESTORE_VOLUME"
        const val ACTION_END_DND = "com.plainapps.nightcap.action.END_DND"
        const val ACTION_END_DND_ALARM = "com.plainapps.nightcap.action.END_DND_ALARM"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_END_DND, ACTION_END_DND_ALARM -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        if (intent.action == ACTION_END_DND) {
                            RestoreManager.endDnd(app, "notification")
                            // The result notification's only other content is "Paused at …"; a muted result keeps it.
                            if (!TimerRepository(app).currentState().mutedInsteadOfPaused) {
                                NotificationManagerCompat.from(app).cancel(TimerNotifications.NOTIF_ID_RESULT)
                            }
                        } else {
                            RestoreManager.applyOwedDnd(app, "until-time alarm")
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> app.startForegroundService(Intent(app, TimerService::class.java).setAction(intent.action))
        }
    }
}
