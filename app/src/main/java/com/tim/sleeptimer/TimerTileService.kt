package com.tim.sleeptimer

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** SPEC F5: idle shows the last preset, tap starts it via the trampoline; running, tap extends. */
class TimerTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { refreshTile() }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val repo = TimerRepository(applicationContext)
            val state = repo.currentState()
            // v2.1 (REVIEW_V2 row 5): a stale run (reboot / force-stop long ago) is idle: start a new timer.
            if (RestoreRules.tileAction(state.isActive, state.deadlineWallClockMillis, System.currentTimeMillis()) == TileAction.EXTEND) {
                applicationContext.startForegroundService(
                    Intent(applicationContext, TimerService::class.java).setAction(TimerActionReceiver.ACTION_EXTEND),
                )
            } else {
                launchTrampoline()
            }
            refreshTile()
        }
    }

    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
    private fun launchTrampoline() {
        val intent = Intent(applicationContext, StartTrampolineActivity::class.java)
            .setAction(StartTrampolineActivity.ACTION_START_PRESET)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private suspend fun refreshTile() {
        val repo = TimerRepository(applicationContext)
        val state = repo.currentState()
        val settings = repo.currentSettings()
        val tile = qsTile ?: return
        if (RestoreRules.tileAction(state.isActive, state.deadlineWallClockMillis, System.currentTimeMillis()) == TileAction.EXTEND) {
            val remainingMin = ((state.deadlineWallClockMillis - System.currentTimeMillis()) / 60_000L)
                .coerceAtLeast(0L)
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.app_name)
            setTileSubtitle(tile, "$remainingMin min left")
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.app_name)
            setTileSubtitle(tile, "${settings.lastUsedDurationMinutes} min")
        }
        tile.updateTile()
    }

    private fun setTileSubtitle(tile: Tile, text: CharSequence) {
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = text
        } else {
            tile.label = "${tile.label} · $text"
        }
    }
}
