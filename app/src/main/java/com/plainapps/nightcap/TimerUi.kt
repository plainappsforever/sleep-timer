package com.plainapps.nightcap

import android.appwidget.AppWidgetManager
import android.content.Context
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Keeps every surface outside the app screens in step with the persisted state (V2_PLAN "Entry points"):
 * the home-screen widget and the Quick Settings tile. The ongoing notification stays in
 * [TimerService.updateNotification] because only the service can call `startForeground`; the service
 * calls both at every state change. Cheap when no widget is placed and the tile is not added.
 */
object TimerUi {
    private const val TAG = "TimerUi"

    /** Redraw widget + tile from [state] (read from storage when null). */
    suspend fun refresh(context: Context, state: TimerState? = null) {
        val app = context.applicationContext
        try {
            val repo = TimerRepository(app)
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(TimerWidgetProvider.componentName(app))
            if (ids.isNotEmpty()) {
                TimerWidgetProvider.render(app, manager, ids, state ?: repo.currentState(), repo.currentSettings())
            }
            // The tile refreshes itself in onStartListening; this asks for that while it is listening.
            TileService.requestListeningState(app, android.content.ComponentName(app, TimerTileService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed: ${e.message}")
        }
    }

    /** Presets or other settings changed: redraw the widget and rebuild the app shortcuts. */
    suspend fun settingsChanged(context: Context) {
        refresh(context)
        Shortcuts.sync(context.applicationContext)
    }
}
