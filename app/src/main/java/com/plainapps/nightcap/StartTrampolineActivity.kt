package com.plainapps.nightcap

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The one start entry point for the Quick Settings tile, the home-screen widget and app shortcuts
 * (DECISIONS D9, D25): a transparent activity that starts the FGS while visible, then finishes at once.
 * Never shown to the user. `ACTION_START_PRESET` + `preset_id` starts that preset; without the extra it
 * starts the last used one (the tile's behaviour). A preset that no longer exists starts nothing.
 */
class StartTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedId = intent?.getStringExtra(EXTRA_PRESET_ID)
        val repo = TimerRepository(applicationContext)
        CoroutineScope(Dispatchers.Main).launch {
            // A phone setting still owed from an earlier run goes back first (rule 8, D36).
            RestoreManager.applyOwed(applicationContext, "trampoline")
            val settings = repo.currentSettings()
            val preset = if (requestedId != null) {
                settings.presets.firstOrNull { it.id == requestedId }
            } else {
                // Last used preset (its current minutes, in case it was edited since), else last custom duration.
                settings.presets.firstOrNull { it.id == settings.lastUsedPresetId }
            }
            if (requestedId != null && preset == null) {
                Toast.makeText(applicationContext, R.string.shortcut_preset_deleted, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            // Same as the Start button: the first start asks for notifications, the timer runs regardless.
            if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(this@StartTrampolineActivity).areNotificationsEnabled()) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
            val startIntent = Intent(this@StartTrampolineActivity, TimerService::class.java)
                .setAction(TimerService.ACTION_START)
                .putExtra(TimerService.EXTRA_DURATION_MINUTES, preset?.minutes ?: settings.lastUsedDurationMinutes)
                .apply { preset?.let { putExtra(TimerService.EXTRA_PRESET_ID, it.id) } }
            startForegroundService(startIntent)
            finish()
        }
    }

    companion object {
        const val ACTION_START_PRESET = "com.plainapps.nightcap.action.START_PRESET"
        const val EXTRA_PRESET_ID = "preset_id"
    }
}
