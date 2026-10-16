package com.plainapps.nightcap

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home-screen widget (SPEC F14, DECISIONS D24): plain RemoteViews, vector/shape drawables only,
 * `updatePeriodMillis = 0`. Idle = preset buttons that open [StartTrampolineActivity]; running =
 * "Ends HH:MM", a countdown Chronometer (no per-second updates), +extend and Stop as broadcasts to
 * [TimerActionReceiver]. Redrawn by [TimerUi.refresh] on every state change, on resize and on add.
 */
class TimerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderAsync(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        renderAsync(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    private fun renderAsync(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val repo = TimerRepository(app)
                render(app, manager, ids, repo.currentState(), repo.currentSettings())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun componentName(context: Context) = ComponentName(context, TimerWidgetProvider::class.java)

        fun render(context: Context, manager: AppWidgetManager, ids: IntArray, state: TimerState, settings: TimerSettings) {
            val now = System.currentTimeMillis()
            for (id in ids) {
                val minWidthDp = manager.getAppWidgetOptions(id)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                    .let { if (it <= 0) 250 else it }
                val views = when (val content = WidgetPlan.content(state, settings.presets, now, minWidthDp)) {
                    is WidgetContent.Idle -> idleViews(context, content)
                    is WidgetContent.Running -> runningViews(context, content, now)
                }
                views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
                manager.updateAppWidget(id, views)
            }
        }

        private val buttonIds = intArrayOf(R.id.widget_btn1, R.id.widget_btn2, R.id.widget_btn3)

        private fun idleViews(context: Context, content: WidgetContent.Idle): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_idle)
            buttonIds.forEachIndexed { index, viewId ->
                val button = content.buttons.getOrNull(index)
                if (button == null) {
                    views.setViewVisibility(viewId, android.view.View.GONE)
                } else {
                    views.setViewVisibility(viewId, android.view.View.VISIBLE)
                    views.setTextViewText(viewId, button.title?.let { "$it\n${button.subtitle}" } ?: button.subtitle)
                    views.setOnClickPendingIntent(viewId, startPresetIntent(context, button.presetId))
                }
            }
            return views
        }

        private fun runningViews(context: Context, content: WidgetContent.Running, now: Long): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_running)
            val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(content.deadlineWallClockMillis))
            views.setTextViewText(
                R.id.widget_ends,
                context.getString(if (content.fading) R.string.widget_fading else R.string.widget_ends, timeText),
            )
            views.setChronometerCountDown(R.id.widget_chrono, true)
            views.setChronometer(
                R.id.widget_chrono,
                SystemClock.elapsedRealtime() + (content.deadlineWallClockMillis - now),
                null,
                true,
            )
            views.setTextViewText(R.id.widget_extend, context.getString(R.string.widget_extend, content.extendStepMinutes))
            views.setOnClickPendingIntent(R.id.widget_extend, actionIntent(context, TimerActionReceiver.ACTION_EXTEND, 11))
            views.setOnClickPendingIntent(R.id.widget_stop, actionIntent(context, TimerActionReceiver.ACTION_STOP, 12))
            return views
        }

        private fun startPresetIntent(context: Context, presetId: String): PendingIntent {
            val intent = Intent(context, StartTrampolineActivity::class.java)
                .setAction(StartTrampolineActivity.ACTION_START_PRESET)
                .putExtra(StartTrampolineActivity.EXTRA_PRESET_ID, presetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // One PendingIntent per preset id: the extras differ, so they need distinct request codes.
            return PendingIntent.getActivity(
                context,
                20 + (presetId.filter { it.isDigit() }.toIntOrNull() ?: 0),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, TimerActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
