package com.tim.sleeptimer

/**
 * v2.1 (REVIEW_V2 rows 1, 3, 5): pure rules for paying owed phone settings and for runs interrupted by process
 * death, a reboot or a force-stop. No Android imports; tests in `RestoreRulesTest`. [RestoreManager] and
 * [TimerService] only read the facts and act on these answers.
 */

/** What to do with an owed media-volume record (bedtime volume, an interrupted fade, a muted end). */
enum class StreamDebtAction { RESTORE, KEEP, DROP }

/** What an interrupted run leaves behind for the media volume. */
sealed interface InterruptedVolume {
    /** Leave any existing record as it is (a countdown: the bedtime record, if any, already says it). */
    data object Keep : InterruptedVolume

    /** "Restore volume after pause" is off: the run would have left the volume where it is. */
    data object Clear : InterruptedVolume

    /** The volume is owed back to [original]; we last set [set] (paid only while it is still [set]). */
    data class Owe(val original: Int, val set: Int) : InterruptedVolume
}

/** v2.1 (REVIEW_V2 row 5): what a tile tap does with the persisted run. */
enum class TileAction { EXTEND, START_NEW }

/** What a fresh service does with the persisted run it finds (watchdog, boot, app open, any restart). */
enum class RecoveryAction { IDLE, CLOSE_STALE, RESUME_COUNTDOWN, FINISH_END_SEQUENCE }

enum class DndDebtAction { NONE, DROP_NO_ACCESS, END_NOW, ARM_ALARM }

object RestoreRules {

    /** Watchdog (alarm 105) after the deadline when nothing re-arms it sooner: fade start + fade + pause ladder fit well inside. */
    const val WATCHDOG_AFTER_DEADLINE_MS = 60_000L

    /** While the end sequence or the screen-off wait runs, the watchdog is re-armed this often ... */
    const val WATCHDOG_REARM_MS = 30_000L

    /** ... always this far ahead, so a killed process comes back at most this long after its death. */
    const val WATCHDOG_LEAD_MS = 45_000L

    /**
     * An owed media volume: the user changed it since we set it -> drop the record (their choice wins); still ours
     * but something is playing -> keep it (paying it would make that playback louder in the dark: D39's Stop rule,
     * now for every record, and the muted-end rule of P5); still ours and quiet -> restore.
     */
    fun streamDebt(owedSet: Int?, current: Int, playing: Boolean): StreamDebtAction = when {
        owedSet == null || current != owedSet -> StreamDebtAction.DROP
        playing -> StreamDebtAction.KEEP
        else -> StreamDebtAction.RESTORE
    }

    /**
     * A run closed without its end sequence (stale after a reboot / force-stop, or orphaned): a fade that had
     * started owes the volume it lowered — the pre-start index of an untouched bedtime run, else the fade-start index
     * (same targets as [TimerPlan.restoreTargetIndex]) — instead of the old behaviour of dropping it (REVIEW_V2 row 1).
     */
    fun interruptedRunVolume(
        fading: Boolean,
        fadeStartIndex: Int,
        lastSetIndex: Int,
        preStartIndex: Int,
        touched: Boolean,
        restoreEnabled: Boolean,
    ): InterruptedVolume {
        if (!fading || fadeStartIndex < 0 || lastSetIndex < 0) return InterruptedVolume.Keep
        if (!restoreEnabled) return InterruptedVolume.Clear
        val target = if (preStartIndex >= 0 && !touched) preStartIndex else fadeStartIndex
        return InterruptedVolume.Owe(target, lastSetIndex)
    }

    /** What a (re)started service does with the persisted run. */
    fun recovery(active: Boolean, deadlineMillis: Long, fadeSeconds: Int, nowMillis: Long): RecoveryAction = when {
        !active -> RecoveryAction.IDLE
        TimerPlan.isStale(true, deadlineMillis, nowMillis) -> RecoveryAction.CLOSE_STALE
        nowMillis >= deadlineMillis - fadeSeconds * 1000L -> RecoveryAction.FINISH_END_SEQUENCE
        else -> RecoveryAction.RESUME_COUNTDOWN
    }

    /**
     * Tile tap: a live run is extended; a stale run (left RUNNING by a reboot or force-stop long ago) counts as idle
     * and a new timer starts — the widget's rule (WidgetPlan), now also the tile's.
     */
    fun tileAction(active: Boolean, deadlineMillis: Long, nowMillis: Long): TileAction =
        if (active && !TimerPlan.isStale(true, deadlineMillis, nowMillis)) TileAction.EXTEND else TileAction.START_NEW

    /**
     * Extend: + [stepMillis] from the deadline, or from now when the deadline has already passed (a run whose end did
     * not happen yet, e.g. just after a reboot) — never a deadline in the past that fires the end at once. Clamped to
     * the 8-hour run limit like before.
     */
    fun extendedDeadline(startMillis: Long, deadlineMillis: Long, nowMillis: Long, stepMillis: Long): Long =
        TimerPlan.clampedExtendedDeadline(startMillis, maxOf(deadlineMillis, nowMillis), stepMillis)

    /** Our Do Not Disturb record: access gone -> drop it (Android removed the rule); until-time passed -> end now; else arm alarm 103. */
    fun dndDebt(owedActive: Boolean, accessGranted: Boolean, untilMillis: Long, nowMillis: Long): DndDebtAction = when {
        !owedActive -> DndDebtAction.NONE
        !accessGranted -> DndDebtAction.DROP_NO_ACCESS
        nowMillis >= untilMillis -> DndDebtAction.END_NOW
        else -> DndDebtAction.ARM_ALARM
    }

    /**
     * Goodnight's screen debt (layer, SYSTEM brightness) is paid when no run is active, or when the active run's
     * Goodnight is orphaned: it started in an earlier boot, or (app open only) no stepping loop runs in this process.
     */
    fun brightnessDue(runActive: Boolean, goodnightPending: Boolean, otherBoot: Boolean, orphanCheck: Boolean, steppingAlive: Boolean): Boolean =
        !runActive || (goodnightPending && (otherBoot || (orphanCheck && !steppingAlive)))
}
