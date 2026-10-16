package com.tim.sleeptimer

import kotlin.test.assertEquals
import org.junit.Test

/** v2.1: process death / stale runs / owed records (REVIEW_V2 row 1 and "missing unit tests" 2). */
class RestoreRulesTest {

    private val now = 1_000_000_000L

    // ---- stream debt ----

    @Test
    fun `stream debt - still ours and quiet is restored`() {
        assertEquals(StreamDebtAction.RESTORE, RestoreRules.streamDebt(owedSet = 5, current = 5, playing = false))
    }

    @Test
    fun `stream debt - muted and still playing is kept, so the player does not come back loud`() {
        assertEquals(StreamDebtAction.KEEP, RestoreRules.streamDebt(owedSet = TimerPlan.MUTED_STREAM_INDEX, current = 0, playing = true))
    }

    @Test
    fun `stream debt - a lowered volume is never raised while something plays`() {
        assertEquals(StreamDebtAction.KEEP, RestoreRules.streamDebt(owedSet = 1, current = 1, playing = true))
    }

    @Test
    fun `stream debt - changed by the user is dropped, playing or not`() {
        assertEquals(StreamDebtAction.DROP, RestoreRules.streamDebt(owedSet = 5, current = 9, playing = false))
        assertEquals(StreamDebtAction.DROP, RestoreRules.streamDebt(owedSet = 5, current = 3, playing = true))
        assertEquals(StreamDebtAction.DROP, RestoreRules.streamDebt(owedSet = null, current = 3, playing = false))
    }

    // ---- interrupted run (stale / orphaned) ----

    @Test
    fun `interrupted fade owes the fade-start volume from the last index we set`() {
        assertEquals(
            InterruptedVolume.Owe(original = 12, set = 4),
            RestoreRules.interruptedRunVolume(fading = true, fadeStartIndex = 12, lastSetIndex = 4, preStartIndex = -1, touched = false, restoreEnabled = true),
        )
    }

    @Test
    fun `interrupted fade of an untouched bedtime run owes the pre-start volume`() {
        assertEquals(
            InterruptedVolume.Owe(original = 10, set = 1),
            RestoreRules.interruptedRunVolume(fading = true, fadeStartIndex = 5, lastSetIndex = 1, preStartIndex = 10, touched = false, restoreEnabled = true),
        )
        assertEquals(
            InterruptedVolume.Owe(original = 8, set = 1),
            RestoreRules.interruptedRunVolume(fading = true, fadeStartIndex = 8, lastSetIndex = 1, preStartIndex = 10, touched = true, restoreEnabled = true),
        )
    }

    @Test
    fun `interrupted countdown keeps the bedtime record as it is, restore switch off clears it`() {
        assertEquals(
            InterruptedVolume.Keep,
            RestoreRules.interruptedRunVolume(fading = false, fadeStartIndex = -1, lastSetIndex = -1, preStartIndex = 10, touched = false, restoreEnabled = true),
        )
        assertEquals(
            InterruptedVolume.Clear,
            RestoreRules.interruptedRunVolume(fading = true, fadeStartIndex = 8, lastSetIndex = 3, preStartIndex = -1, touched = false, restoreEnabled = false),
        )
    }

    // ---- recovery of a restarted service ----

    @Test
    fun `recovery - countdown resumes, fade window completes, long past closes, idle stays idle`() {
        val deadline = now + 120_000L
        assertEquals(RecoveryAction.RESUME_COUNTDOWN, RestoreRules.recovery(true, deadline, 30, now))
        assertEquals(RecoveryAction.FINISH_END_SEQUENCE, RestoreRules.recovery(true, deadline, 30, deadline - 30_000L))
        assertEquals(RecoveryAction.FINISH_END_SEQUENCE, RestoreRules.recovery(true, deadline, 30, deadline + 45_000L))
        assertEquals(RecoveryAction.CLOSE_STALE, RestoreRules.recovery(true, deadline, 30, deadline + TimerPlan.STALE_AFTER_DEADLINE_MS + 1))
        assertEquals(RecoveryAction.IDLE, RestoreRules.recovery(false, deadline, 30, now))
    }

    // ---- tile and extend after a reboot / force-stop (REVIEW_V2 row 5, "missing unit tests" 3) ----

    @Test
    fun `tile - a live run extends, a stale run counts as idle and starts a new timer`() {
        val deadline = now + 60_000L
        assertEquals(TileAction.EXTEND, RestoreRules.tileAction(true, deadline, now))
        assertEquals(TileAction.EXTEND, RestoreRules.tileAction(true, deadline, deadline + 5 * 60_000L))
        assertEquals(TileAction.START_NEW, RestoreRules.tileAction(true, deadline, deadline + TimerPlan.STALE_AFTER_DEADLINE_MS + 1))
        assertEquals(TileAction.START_NEW, RestoreRules.tileAction(false, deadline, now))
    }

    @Test
    fun `extend after the deadline passed counts from now, never into the past`() {
        val start = now - 3_600_000L
        val pastDeadline = now - 5 * 60_000L
        assertEquals(now + 15 * 60_000L, RestoreRules.extendedDeadline(start, pastDeadline, now, 15 * 60_000L))
        // a live deadline still extends from the deadline (unchanged behaviour)
        assertEquals(now + 25 * 60_000L, RestoreRules.extendedDeadline(start, now + 10 * 60_000L, now, 15 * 60_000L))
        // the 8-hour limit still holds
        val longAgo = now - (TimerPlan.MAX_DURATION_MINUTES - 5) * 60_000L
        assertEquals(longAgo + TimerPlan.MAX_DURATION_MINUTES * 60_000L, RestoreRules.extendedDeadline(longAgo, now, now, 15 * 60_000L))
    }

    @Test
    fun `watchdog fires after the end sequence would have finished but well before a run counts as stale`() {
        assert(RestoreRules.WATCHDOG_LEAD_MS > RestoreRules.WATCHDOG_REARM_MS)
        assert(RestoreRules.WATCHDOG_AFTER_DEADLINE_MS < TimerPlan.STALE_AFTER_DEADLINE_MS)
    }

    // ---- DND and Goodnight records ("missing unit tests" 2) ----

    @Test
    fun `DND record - access gone drops it, time passed ends it, still ahead re-arms`() {
        assertEquals(DndDebtAction.NONE, RestoreRules.dndDebt(owedActive = false, accessGranted = true, untilMillis = now, nowMillis = now))
        assertEquals(DndDebtAction.DROP_NO_ACCESS, RestoreRules.dndDebt(true, accessGranted = false, untilMillis = now + 1, nowMillis = now))
        assertEquals(DndDebtAction.END_NOW, RestoreRules.dndDebt(true, true, untilMillis = now, nowMillis = now))
        assertEquals(DndDebtAction.ARM_ALARM, RestoreRules.dndDebt(true, true, untilMillis = now + 60_000L, nowMillis = now))
    }

    @Test
    fun `Goodnight screen debt - paid after the run, kept while this boot's run dims, paid for an orphan`() {
        assertEquals(true, RestoreRules.brightnessDue(runActive = false, goodnightPending = true, otherBoot = false, orphanCheck = false, steppingAlive = false))
        assertEquals(false, RestoreRules.brightnessDue(runActive = true, goodnightPending = true, otherBoot = false, orphanCheck = false, steppingAlive = true))
        // reboot: a run of an earlier boot is never resumed, its debt is paid (boot receiver)
        assertEquals(true, RestoreRules.brightnessDue(runActive = true, goodnightPending = true, otherBoot = true, orphanCheck = false, steppingAlive = false))
        // app open: active run without a stepping loop in this process (force-stop) is an orphan
        assertEquals(true, RestoreRules.brightnessDue(runActive = true, goodnightPending = true, otherBoot = false, orphanCheck = true, steppingAlive = false))
        assertEquals(false, RestoreRules.brightnessDue(runActive = true, goodnightPending = true, otherBoot = false, orphanCheck = true, steppingAlive = true))
        assertEquals(false, RestoreRules.brightnessDue(runActive = true, goodnightPending = false, otherBoot = true, orphanCheck = true, steppingAlive = false))
    }
}
