package com.plainapps.nightcap

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * App shortcuts (SPEC F15, DECISIONS D25) through `ShortcutManagerCompat` (androidx.core, no new
 * dependency). What exists is decided by the pure [ShortcutPlan]; this file only talks to the framework.
 */
object Shortcuts {
    private const val TAG = "Shortcuts"

    /** Last published dynamic set, so unchanged presets do not spend the launcher's rate limit. */
    @Volatile private var lastDynamic: List<ShortcutSpec>? = null

    /** Publish the first presets as dynamic shortcuts and reconcile pinned ones with the presets. */
    suspend fun sync(context: Context) {
        try {
            val presets = TimerRepository(context).currentSettings().presets
            val dynamic = ShortcutPlan.dynamic(presets, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context))
            if (dynamic != lastDynamic) {
                ShortcutManagerCompat.setDynamicShortcuts(
                    context,
                    dynamic.mapIndexed { index, spec -> info(context, spec).setRank(index).build() },
                )
                lastDynamic = dynamic
            }
            val pinnedIds = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).map { it.id }
            val reconcile = ShortcutPlan.reconcilePinned(pinnedIds, presets)
            if (reconcile.update.isNotEmpty()) {
                ShortcutManagerCompat.updateShortcuts(context, reconcile.update.map { info(context, it).build() })
            }
            if (reconcile.disableIds.isNotEmpty()) {
                ShortcutManagerCompat.disableShortcuts(
                    context,
                    reconcile.disableIds,
                    context.getString(R.string.shortcut_preset_deleted),
                )
            }
        } catch (e: Exception) {
            // Rate limiting (IllegalStateException) or a launcher without shortcut support: not worth a crash.
            Log.w(TAG, "sync failed: ${e.message}")
        }
    }

    fun isPinSupported(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** "Add to Home screen" in the preset editor: asks the launcher to pin a shortcut that starts [preset]. */
    fun requestPin(context: Context, preset: Preset): Boolean {
        val spec = ShortcutPlan.spec(ShortcutPlan.pinnedId(preset.id, System.currentTimeMillis()), preset)
        return try {
            ShortcutManagerCompat.requestPinShortcut(context, info(context, spec).build(), null)
        } catch (e: Exception) {
            Log.w(TAG, "requestPin failed: ${e.message}")
            false
        }
    }

    private fun info(context: Context, spec: ShortcutSpec): ShortcutInfoCompat.Builder =
        ShortcutInfoCompat.Builder(context, spec.id)
            .setShortLabel(spec.shortLabel)
            .setLongLabel(spec.longLabel)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(
                Intent(context, StartTrampolineActivity::class.java)
                    .setAction(StartTrampolineActivity.ACTION_START_PRESET)
                    .putExtra(StartTrampolineActivity.EXTRA_PRESET_ID, spec.presetId),
            )
}
