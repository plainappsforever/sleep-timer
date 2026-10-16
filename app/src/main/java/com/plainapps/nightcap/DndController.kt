package com.plainapps.nightcap

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.util.Log
import androidx.core.net.toUri

/**
 * "Do Not Disturb at start" (SPEC F18, DECISIONS D19). API 29+: our own AutomaticZenRule "Sleep timer"
 * (priority-only with the user's own priority policy, so alarms and allowed callers still come through),
 * switched with setAutomaticZenRuleState. API 26-28: setInterruptionFilter(PRIORITY), undone only if the
 * filter is still the one we set. Every call is guarded: without Do Not Disturb access nothing changes.
 * The owed-restore record (RestoreManager) is written by the caller BEFORE [activate].
 */
class DndController(context: Context) {
    private val app = context.applicationContext
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun isAccessGranted(): Boolean = try {
        nm.isNotificationPolicyAccessGranted
    } catch (e: SecurityException) {
        false
    }

    /** Anything but "all interruptions" right now (ours or the user's own DND). */
    fun isDndOn(): Boolean = try {
        nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    } catch (e: SecurityException) {
        false
    }

    /**
     * Turns DND on. [ruleId] is the stored id of our rule (null = none yet); returns the rule id in use
     * (API 29+), "" on API 26-28, or null when nothing was changed. On API 29+ it sends FALSE, then TRUE:
     * the FALSE clears a "snooze" the user may have left on our rule (NotificationManager
     * setAutomaticZenRuleState docs). Always the plain Condition (source unknown), never
     * SOURCE_USER_ACTION: a user-sourced state becomes an override that our own morning FALSE would
     * not clear (L13 review, ZenModeConfig.ZenRule).
     */
    fun activate(ruleId: String?): String? {
        if (!isAccessGranted()) {
            Log.i(TAG, "activate skipped: no Do Not Disturb access")
            return null
        }
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val id = ensureRule(ruleId) ?: return null
                nm.setAutomaticZenRuleState(id, condition(Condition.STATE_FALSE))
                nm.setAutomaticZenRuleState(id, condition(Condition.STATE_TRUE))
                Log.i(TAG, "rule $id -> STATE_TRUE, filter now ${nm.currentInterruptionFilter}")
                id
            } else {
                // The user's own DND is already on: nothing of ours to set, so nothing to undo later.
                if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return null
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                Log.i(TAG, "interruption filter -> PRIORITY")
                ""
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "activate failed: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "activate failed: ${e.message}")
            null
        }
    }

    /** Turns our DND off. False only when access is missing (the caller then drops the record anyway). */
    fun end(ruleId: String?): Boolean {
        if (!isAccessGranted()) return false
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                if (!ruleId.isNullOrEmpty() && nm.getAutomaticZenRule(ruleId) != null) {
                    nm.setAutomaticZenRuleState(ruleId, condition(Condition.STATE_FALSE))
                    Log.i(TAG, "rule $ruleId -> STATE_FALSE, filter now ${nm.currentInterruptionFilter}")
                }
            } else if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                // Only undo what we set: if the user switched to another mode meanwhile, leave it.
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                Log.i(TAG, "interruption filter -> ALL")
            }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "end failed: ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "end failed: ${e.message}")
            false
        }
    }

    private fun ensureRule(storedId: String?): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        if (!storedId.isNullOrEmpty() && nm.getAutomaticZenRule(storedId) != null) return storedId
        // Id lost (data cleared, backup restored) but the rule still exists: reuse it, never a second one.
        nm.automaticZenRules.entries.firstOrNull { it.value.conditionId == CONDITION_ID }?.let { return it.key }
        // Not TYPE_BEDTIME: only the Wellbeing app may own that type (AutomaticZenRule, API 37 source).
        val rule = AutomaticZenRule(
            app.getString(R.string.dnd_rule_name),
            null,
            ComponentName(app, MainActivity::class.java),
            CONDITION_ID,
            null, // the user's own priority policy
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true,
        )
        val id = nm.addAutomaticZenRule(rule)
        Log.i(TAG, "rule added: $id")
        return id
    }

    private fun condition(state: Int): Condition = Condition(CONDITION_ID, app.getString(R.string.dnd_rule_name), state)

    companion object {
        private const val TAG = "DndController"
        val CONDITION_ID: Uri = "condition://com.plainapps.nightcap/sleep_timer".toUri()
    }
}
