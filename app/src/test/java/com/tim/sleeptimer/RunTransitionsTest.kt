package com.tim.sleeptimer

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** v2.1 (REVIEW_V2 "missing unit tests" 1): the service's commands as state transitions. */
class RunTransitionsTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    private fun running(deadline: Long = now + 20 * min) = TimerState(
        phase = RunPhase.RUNNING,
        startWallClockMillis = now - 10 * min,
        deadlineWallClockMillis = deadline,
        fadeSeconds = 30,
        extendStepMinutes = 15,
    )

    /** Fading from 12; [lastSet] is where the fade is (1 = fade done, pause ladder running). */
    private fun fading(lastSet: Int) = running(deadline = now + 10_000L).copy(phase = RunPhase.FADING, originalStreamIndex = 12, lastSetStreamIndex = lastSet)

    @Test
    fun `extend during the fade ramps back up to the fade-start volume and counts down again`() {
        val e = assertNotNull(RunTransitions.extend(fading(lastSet = 6), now, currentIndex = 6))
        assertEquals(RunPhase.RUNNING, e.state.phase)
        assertEquals(now + 10_000L + 15 * min, e.state.deadlineWallClockMillis)
        assertEquals((7..12).toList(), e.rampUp.map { it.targetIndex })
        assertEquals(-1, e.state.originalStreamIndex)
        assertEquals(-1, e.state.lastSetStreamIndex)
    }

    @Test
    fun `extend during the pause ladder ramps up from the faded floor`() {
        val e = assertNotNull(RunTransitions.extend(fading(lastSet = 1), now, currentIndex = 1))
        assertEquals((2..12).toList(), e.rampUp.map { it.targetIndex })
        assertEquals(RunPhase.RUNNING, e.state.phase)
    }

    @Test
    fun `two +15s in a row add 30 minutes, capped at 8 hours from the start`() {
        val once = assertNotNull(RunTransitions.extend(running(), now, 10)).state
        val twice = assertNotNull(RunTransitions.extend(once, now + 1_000L, 10)).state
        assertEquals(now + 50 * min, twice.deadlineWallClockMillis)
        assertTrue(twice.isPlainCountdown())
        val nearCap = running(deadline = now - 10 * min + (TimerPlan.MAX_DURATION_MINUTES - 5) * min)
        val capped = assertNotNull(RunTransitions.extend(nearCap, now, 10)).state
        assertEquals(nearCap.startWallClockMillis + TimerPlan.MAX_DURATION_MINUTES * min, capped.deadlineWallClockMillis)
    }

    @Test
    fun `extend of no run or a stale run does nothing`() {
        assertNull(RunTransitions.extend(TimerState(), now, 10))
        assertNull(RunTransitions.extend(running(deadline = now - TimerPlan.STALE_AFTER_DEADLINE_MS - 1), now, 10))
    }

    @Test
    fun `End now during the fade keeps the fade-start volume and ends within 10 s`() {
        val s = assertNotNull(RunTransitions.endNow(fading(lastSet = 6), now))
        assertEquals(RunPhase.FADING, s.phase)
        assertEquals(12, s.originalStreamIndex)
        assertEquals(10, s.fadeSeconds)
        assertEquals(now + 10_000L, s.deadlineWallClockMillis)
        assertNull(RunTransitions.endNow(TimerState(phase = RunPhase.DONE), now))
    }

    @Test
    fun `Stop during the pause ladder puts the fade-start volume back and ends the run`() {
        val stop = RunTransitions.stop(fading(lastSet = 1), currentIndex = 1, isPlaying = false)
        assertEquals(12, stop.volumeTarget)
        assertEquals(RunPhase.DONE, stop.state.phase)
        assertEquals(DoneReason.STOPPED_BY_USER, stop.state.doneReason)
        assertEquals(false, stop.clearOwedStream)
    }

    @Test
    fun `Stop of an untouched bedtime run goes back to the pre-start volume only while nothing plays`() {
        val bedtime = running().copy(preStartStreamIndex = 10, bedtimeStreamIndex = 5)
        assertEquals(10, RunTransitions.stop(bedtime, currentIndex = 5, isPlaying = false).volumeTarget)
        assertNull(RunTransitions.stop(bedtime, currentIndex = 5, isPlaying = true).volumeTarget)
        assertTrue(RunTransitions.stop(bedtime, currentIndex = 5, isPlaying = true).clearOwedStream)
        // the user moved it 3 steps: theirs
        assertNull(RunTransitions.stop(bedtime, currentIndex = 8, isPlaying = false).volumeTarget)
    }

    @Test
    fun `minus 5 only while counting down, never closer than fade plus a minute`() {
        assertEquals(now + 15 * min, RunTransitions.reduce(running(), now)?.deadlineWallClockMillis)
        assertNull(RunTransitions.reduce(fading(lastSet = 6), now))
        assertNull(RunTransitions.reduce(running(deadline = now + 60_000L), now))
    }

    @Test
    fun `an automatic extend is counted and marks the run as touched`() {
        val s = RunTransitions.autoExtend(fading(lastSet = 6).copy(autoExtendCount = 1), now)
        assertEquals(RunPhase.RUNNING, s.phase)
        assertEquals(2, s.autoExtendCount)
        assertTrue(s.volumeTouchedDuringRun)
        assertEquals(now + 10_000L + 15 * min, s.deadlineWallClockMillis)
    }

    private fun TimerState.isPlainCountdown() = phase == RunPhase.RUNNING && originalStreamIndex == -1
}
