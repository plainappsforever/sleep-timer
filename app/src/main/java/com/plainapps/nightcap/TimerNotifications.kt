package com.plainapps.nightcap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimerNotifications {
    const val CHANNEL_TIMER = "timer"
    const val CHANNEL_RESULT = "result"
    const val NOTIF_ID_TIMER = 1
    const val NOTIF_ID_RESULT = 2

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val timerChannel = NotificationChannel(
            CHANNEL_TIMER,
            context.getString(R.string.notification_channel_timer_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val resultChannel = NotificationChannel(
            CHANNEL_RESULT,
            context.getString(R.string.notification_channel_result_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannels(listOf(timerChannel, resultChannel))
    }

    fun buildRunningNotification(
        context: Context,
        deadlineWallClockMillis: Long,
        extendStepMinutes: Int,
        fading: Boolean,
        fadeSeconds: Int,
        screenAction: ScreenAction = ScreenAction.NONE,
        startedBySchedule: Boolean = false,
        dimMinutes: Int? = null,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(deadlineWallClockMillis))
        val statusText = if (fading) {
            context.getString(R.string.notification_status_fading)
        } else {
            context.getString(R.string.notification_status_running, timeText, TimerPlan.endSequenceSummary(fadeSeconds, screenAction, dimMinutes))
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(statusText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setShowWhen(true)
            .setWhen(deadlineWallClockMillis)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        // P5 (F19): "Nightcap · Started automatically" in the header.
        if (startedBySchedule) builder.setSubText(context.getString(R.string.notification_scheduled))
        // -5 is hidden while fading (D26): the deadline is already too close to move.
        if (!fading) {
            builder.addAction(
                0,
                context.getString(R.string.notification_action_reduce, TimerPlan.REDUCE_STEP_MINUTES),
                actionPendingIntent(context, TimerActionReceiver.ACTION_REDUCE, 4),
            )
        }
        return builder
            .addAction(
                0,
                context.getString(R.string.notification_action_extend, extendStepMinutes),
                actionPendingIntent(context, TimerActionReceiver.ACTION_EXTEND, 1),
            )
            .addAction(
                0,
                context.getString(R.string.notification_action_end_now),
                actionPendingIntent(context, TimerActionReceiver.ACTION_END_NOW, 5),
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .build()
    }

    /** P4: shown only while waiting for the screen to go off after "Turn screen off sooner" (no actions). */
    fun buildScreenOffWaitNotification(context: Context, goodnight: Boolean = false): Notification =
        NotificationCompat.Builder(context, CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(if (goodnight) R.string.notification_status_screen_off_goodnight else R.string.notification_status_screen_off))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

    fun buildResultNotification(
        context: Context,
        muted: Boolean,
        endWallClockMillis: Long,
        dndActive: Boolean = false,
        volumeBackAt: String = "06:50",
    ): Notification {
        val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(endWallClockMillis))
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (!muted) {
            builder.setContentText(context.getString(R.string.notification_paused, timeText))
        } else {
            val restoreIntent = actionPendingIntent(context, TimerActionReceiver.ACTION_RESTORE_VOLUME, 3)
            val text = context.getString(R.string.notification_muted, volumeBackAt)
            builder.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .addAction(0, context.getString(R.string.notification_action_restore), restoreIntent)
        }
        // P5 (D19): our Do Not Disturb stays on until the morning time; end it early from here.
        if (dndActive) {
            builder.addAction(0, context.getString(R.string.dnd_end), actionPendingIntent(context, TimerActionReceiver.ACTION_END_DND, 6))
        }
        return builder.build()
    }

    fun buildExtendedNotification(context: Context, newDeadlineWallClockMillis: Long): Notification {
        val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(newDeadlineWallClockMillis))
        return NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_extended, timeText))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * P5, API 31-32 only (no exact alarm without SCHEDULE_EXACT_ALARM there, so Android refuses the
     * background start): the nightly start becomes one tap on this notification.
     */
    fun buildScheduleFallbackNotification(context: Context, presetId: String, presetLabel: String): Notification {
        val intent = Intent(context, StartTrampolineActivity::class.java)
            .setAction(StartTrampolineActivity.ACTION_START_PRESET)
            .putExtra(StartTrampolineActivity.EXTRA_PRESET_ID, presetId)
        val pi = PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_schedule_tap, presetLabel))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun actionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, TimerActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
