package com.tim.sleeptimer

import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class TimerPlanTest {

    // ---- fadeSteps: original in {1, 5, 15, 25}, fade in {0, 10, 30} ----

    @Test
    fun `fade from 1 is always empty regardless of fade length`() {
        for (fade in listOf(0, 10, 30)) {
            assertTrue(TimerPlan.fadeSteps(originalIndex = 1, fadeSeconds = fade).isEmpty(), "fade=$fade")
        }
    }

    @Test
    fun `fade with zero seconds is always empty regardless of original index`() {
        for (original in listOf(1, 5, 15, 25)) {
            assertTrue(TimerPlan.fadeSteps(originalIndex = original, fadeSeconds = 0).isEmpty(), "original=$original")
        }
    }

    @Test
    fun `fade from 5 over 10s is 4 steps of 2500ms down to 1`() {
        val steps = TimerPlan.fadeSteps(originalIndex = 5, fadeSeconds = 10)
        assertEquals(listOf(4, 3, 2, 1), steps.map { it.targetIndex })
        assertTrue(steps.all { it.delayMillis == 2500L })
        assertEquals(10_000L, steps.sumOf { it.delayMillis })
    }

    @Test
    fun `fade from 5 over 30s is 4 steps of 7500ms down to 1`() {
        val steps = TimerPlan.fadeSteps(originalIndex = 5, fadeSeconds = 30)
        assertEquals(listOf(4, 3, 2, 1), steps.map { it.targetIndex })
        assertTrue(steps.all { it.delayMillis == 7500L })
        assertEquals(30_000L, steps.sumOf { it.delayMillis })
    }

    @Test
    fun `fade from 15 over 10s is 14 steps summing exactly to 10s`() {
        val steps = TimerPlan.fadeSteps(originalIndex = 15, fadeSeconds = 10)
        assertEquals(14, steps.size)
        assertEquals((14 downTo 1).toList(), steps.map { it.targetIndex })
        assertEquals(10_000L, steps.sumOf { it.delayMillis })
    }

    @Test
    fun `fade from 15 over 30s is 14 steps summing exactly to 30s`() {
        val steps = TimerPlan.fadeSteps(originalIndex = 15, fadeSeconds = 30)
        assertEquals(14, steps.size)
        assertEquals(30_000L, steps.sumOf { it.delayMillis })
    }

    @Test
    fun `fade from 25 over 10s is 24 steps summing exactly to 10s`() {
        val steps = TimerPlan.fadeSteps(originalIndex = 25, fadeSeconds = 10)
        assertEquals(24, steps.size)
        assertEquals((24 downTo 1).toList(), steps.map { it.targetIndex })
        assertEquals(10_000L, steps.sumOf { it.delayMillis })
    }

    @Test
    fun `fade from 25 over 30s is 24 steps of exactly 1250ms`() {
        val steps = TimerPlan.fadeSteps(originalIndex = 25, fadeSeconds = 30)
        assertEquals(24, steps.size)
        assertTrue(steps.all { it.delayMillis == 1250L })
        assertEquals(30_000L, steps.sumOf { it.delayMillis })
    }

    @Test
    fun `fade steps never target index 0`() {
        for (original in listOf(1, 5, 15, 25)) {
            for (fade in listOf(0, 10, 30)) {
                assertTrue(TimerPlan.fadeSteps(original, fade).none { it.targetIndex <= 0 })
            }
        }
    }

    // ---- rampUpSteps (extend mid-fade) ----

    @Test
    fun `ramp up returns to original index over 3 seconds by default`() {
        val steps = TimerPlan.rampUpSteps(currentIndex = 2, originalIndex = 5)
        assertEquals(listOf(3, 4, 5), steps.map { it.targetIndex })
        assertEquals(3_000L, steps.sumOf { it.delayMillis })
    }

    @Test
    fun `ramp up is empty if already at the original index`() {
        assertTrue(TimerPlan.rampUpSteps(currentIndex = 5, originalIndex = 5).isEmpty())
    }

    // ---- volume touch detection ----

    // v2.1 (REVIEW_V2 row 4): Bluetooth absolute-volume echo vs. a real touch.
    @Test
    fun `one step off right after our own write is the headset echo, either direction`() {
        assertEquals(TouchVerdict.ECHO, TimerPlan.touchVerdict(lastSetIndex = 6, actualIndex = 5, millisSinceOurWrite = 300))
        assertEquals(TouchVerdict.ECHO, TimerPlan.touchVerdict(lastSetIndex = 6, actualIndex = 7, millisSinceOurWrite = TimerPlan.ECHO_WINDOW_MS))
        assertEquals(TouchVerdict.NONE, TimerPlan.touchVerdict(lastSetIndex = 6, actualIndex = 6, millisSinceOurWrite = 300))
    }

    @Test
    fun `the user raising the volume by one step outside the echo window is a touch`() {
        assertEquals(TouchVerdict.TOUCHED, TimerPlan.touchVerdict(lastSetIndex = 6, actualIndex = 7, millisSinceOurWrite = TimerPlan.ECHO_WINDOW_MS + 1))
        // the fade's per-step check looks long after the write
        assertEquals(TouchVerdict.TOUCHED, TimerPlan.touchVerdict(lastSetIndex = 6, actualIndex = 7, millisSinceOurWrite = Long.MAX_VALUE))
        assertEquals(TouchVerdict.TOUCHED, TimerPlan.touchVerdict(lastSetIndex = 6, actualIndex = 5, millisSinceOurWrite = Long.MAX_VALUE))
    }

    @Test
    fun `two or more steps are a touch even inside the echo window`() {
        assertEquals(TouchVerdict.TOUCHED, TimerPlan.touchVerdict(lastSetIndex = 6, actualIndex = 8, millisSinceOurWrite = 200))
        assertEquals(TouchVerdict.TOUCHED, TimerPlan.touchVerdict(lastSetIndex = 6, actualIndex = 3, millisSinceOurWrite = 200))
    }

    @Test
    fun `an echo adopted after every write never extends, the cap stops a runaway after three`() {
        // Simulate a headset that always reports one step lower 300 ms after each write: the fade adopts each echo.
        var lastSet = 10
        var extends = 0
        for (target in 9 downTo 1) {
            if (TimerPlan.touchVerdict(lastSet, lastSet, Long.MAX_VALUE) == TouchVerdict.TOUCHED) extends++
            lastSet = target
            val reported = target - 1
            if (TimerPlan.touchVerdict(lastSet, reported, 300) == TouchVerdict.ECHO) lastSet = reported
        }
        assertEquals(0, extends)
        assertTrue(TimerPlan.autoExtendAllowed(0))
        assertTrue(TimerPlan.autoExtendAllowed(TimerPlan.MAX_AUTO_EXTENDS - 1))
        assertFalse(TimerPlan.autoExtendAllowed(TimerPlan.MAX_AUTO_EXTENDS))
    }

    @Test
    fun `bedtime volume one step off at fade start is not a touch, two steps is`() {
        assertFalse(TimerPlan.touchedSinceBedtime(bedtimeIndexSet = 5, indexNow = 4))
        assertFalse(TimerPlan.touchedSinceBedtime(bedtimeIndexSet = 5, indexNow = 6))
        assertTrue(TimerPlan.touchedSinceBedtime(bedtimeIndexSet = 5, indexNow = 7))
    }

    @Test
    fun `touch detected when actual index differs from last set index`() {
        assertTrue(TimerPlan.volumeWasTouched(lastSetIndex = 4, actualIndex = 6))
        assertFalse(TimerPlan.volumeWasTouched(lastSetIndex = 4, actualIndex = 4))
    }

    // ---- pause ladder ----

    @Test
    fun `pause ladder stops at media key when it works`() {
        val result = TimerPlan.evaluatePauseLadder(activeAfterMediaKey = false, activeAfterAudioFocus = false)
        assertEquals(PauseRung.MEDIA_KEY, result.stoppedAtRung)
        assertFalse(result.muted)
    }

    @Test
    fun `pause ladder falls to audio focus when media key fails`() {
        val result = TimerPlan.evaluatePauseLadder(activeAfterMediaKey = true, activeAfterAudioFocus = false)
        assertEquals(PauseRung.AUDIO_FOCUS, result.stoppedAtRung)
        assertFalse(result.muted)
    }

    @Test
    fun `pause ladder falls back to mute when both fail`() {
        val result = TimerPlan.evaluatePauseLadder(activeAfterMediaKey = true, activeAfterAudioFocus = true)
        assertEquals(PauseRung.MUTE, result.stoppedAtRung)
        assertTrue(result.muted)
    }

    // ---- restore rule ----

    @Test
    fun `volume restores only when quiet and not muted`() {
        assertTrue(TimerPlan.shouldRestoreVolume(muted = false, isQuietNow = true))
        assertFalse(TimerPlan.shouldRestoreVolume(muted = true, isQuietNow = true))
        assertFalse(TimerPlan.shouldRestoreVolume(muted = false, isQuietNow = false))
    }

    // ---- end-time to duration ----

    @Test
    fun `minutes until a later time today`() {
        val now = LocalDateTime.of(2026, 9, 15, 23, 0)
        assertEquals(30, TimerPlan.minutesUntil(now, 23, 30))
    }

    @Test
    fun `minutes until rolls to tomorrow when the time already passed today`() {
        // 23:00 is after 06:00, so the next occurrence of 06:00 is tomorrow: 7 hours away.
        val now = LocalDateTime.of(2026, 9, 15, 23, 0)
        assertEquals(7 * 60, TimerPlan.minutesUntil(now, 6, 0))
    }

    @Test
    fun `minutes until rolls to tomorrow when exactly at the target time`() {
        val now = LocalDateTime.of(2026, 9, 15, 23, 30)
        // A full 24h away, which is beyond the max duration, so it is clamped.
        assertEquals(TimerPlan.MAX_DURATION_MINUTES, TimerPlan.minutesUntil(now, 23, 30))
    }

    @Test
    fun `minutes until is clamped to the max duration`() {
        val now = LocalDateTime.of(2026, 9, 15, 0, 0)
        // 23:59 tomorrow-relative-to-now is far beyond 480 minutes.
        assertEquals(TimerPlan.MAX_DURATION_MINUTES, TimerPlan.minutesUntil(now, 23, 59))
    }

    // ---- extend clamp ----

    @Test
    fun `extend clamp allows normal extension`() {
        val start = 0L
        val deadline = 30 * 60_000L
        val newDeadline = TimerPlan.clampedExtendedDeadline(start, deadline, 15 * 60_000L)
        assertEquals(45 * 60_000L, newDeadline)
    }

    @Test
    fun `extend clamp caps total elapsed at max duration`() {
        val start = 0L
        val deadline = TimerPlan.MAX_DURATION_MINUTES * 60_000L - 5 * 60_000L
        val newDeadline = TimerPlan.clampedExtendedDeadline(start, deadline, 15 * 60_000L)
        assertEquals(start + TimerPlan.MAX_DURATION_MINUTES * 60_000L, newDeadline)
    }

    // ---- duration clamp ----

    @Test
    fun `duration clamp keeps values in range`() {
        assertEquals(1, TimerPlan.clampDurationMinutes(0))
        assertEquals(1, TimerPlan.clampDurationMinutes(-10))
        assertEquals(480, TimerPlan.clampDurationMinutes(9999))
        assertEquals(60, TimerPlan.clampDurationMinutes(60))
    }

    // ---- -5 min (reducedDeadline) ----

    private val min = 60_000L

    @Test
    fun `reduce takes five minutes off a distant deadline`() {
        val now = 1_000_000L
        assertEquals(now + 25 * min, TimerPlan.reducedDeadline(now, now + 30 * min, fadeSeconds = 30))
    }

    @Test
    fun `reduce never lands closer than fade plus 60 s`() {
        val now = 1_000_000L
        // 3 min left, fade 30 s: 3 - 5 < 0, floor is now + 90 s
        assertEquals(now + 90_000L, TimerPlan.reducedDeadline(now, now + 3 * min, fadeSeconds = 30))
        // 6 min left, fade 120 s: proposed 1 min, floor 3 min
        assertEquals(now + 180_000L, TimerPlan.reducedDeadline(now, now + 6 * min, fadeSeconds = 120))
    }

    @Test
    fun `reduce never pushes a near deadline further out`() {
        val now = 1_000_000L
        val soon = now + 40_000L // already closer than fade + 60 s
        assertEquals(soon, TimerPlan.reducedDeadline(now, soon, fadeSeconds = 30))
        assertEquals(now, TimerPlan.reducedDeadline(now, now, fadeSeconds = 0).coerceAtMost(now))
    }

    @Test
    fun `reduce with no fade keeps a 60 s lead`() {
        val now = 0L
        assertEquals(60_000L, TimerPlan.reducedDeadline(now, 2 * min, fadeSeconds = 0))
    }

    // ---- End now ----

    @Test
    fun `end now fade is the preset fade capped at 10 s`() {
        assertEquals(0, TimerPlan.endNowFadeSeconds(0))
        assertEquals(10, TimerPlan.endNowFadeSeconds(10))
        assertEquals(10, TimerPlan.endNowFadeSeconds(30))
        assertEquals(10, TimerPlan.endNowFadeSeconds(120))
        assertEquals(0, TimerPlan.endNowFadeSeconds(-5))
    }

    // ---- restore volume after pause ----

    @Test
    fun `restore rule honours the restore-after-pause setting`() {
        assertTrue(TimerPlan.shouldRestoreVolume(muted = false, isQuietNow = true))
        assertTrue(TimerPlan.shouldRestoreVolume(muted = false, isQuietNow = true, restoreEnabled = true))
        assertFalse(TimerPlan.shouldRestoreVolume(muted = false, isQuietNow = true, restoreEnabled = false))
        assertFalse(TimerPlan.shouldRestoreVolume(muted = true, isQuietNow = true, restoreEnabled = true))
        assertFalse(TimerPlan.shouldRestoreVolume(muted = false, isQuietNow = false, restoreEnabled = true))
    }

    // ---- notification sequence text ----

    @Test
    fun `sequence summary describes the fade or plain pause`() {
        assertEquals("Fade 30 s \u2192 pause", TimerPlan.endSequenceSummary(30))
        assertEquals("Fade 10 s \u2192 pause", TimerPlan.endSequenceSummary(10))
        assertEquals("Pause", TimerPlan.endSequenceSummary(0))
    }

    // ---- v2 P4: rung 0 (enhanced compatibility, D17) ----

    @Test
    fun `rung 0 that leaves playback quiet stops the ladder at SESSION`() {
        val r = TimerPlan.evaluatePauseLadder(sessionRungTried = true, activeAfterSession = false, activeAfterMediaKey = false, activeAfterAudioFocus = false)
        assertEquals(PauseRung.SESSION, r.stoppedAtRung)
        assertFalse(r.muted)
    }

    @Test
    fun `rung 0 not tried or not enough falls through to the v1 ladder`() {
        assertEquals(PauseRung.MEDIA_KEY, TimerPlan.evaluatePauseLadder(false, true, false, false).stoppedAtRung)
        assertEquals(PauseRung.AUDIO_FOCUS, TimerPlan.evaluatePauseLadder(true, true, true, false).stoppedAtRung)
        val mute = TimerPlan.evaluatePauseLadder(true, true, true, true)
        assertEquals(PauseRung.MUTE, mute.stoppedAtRung)
        assertTrue(mute.muted)
        // Not tried (switch off / no grant): activeAfterSession is ignored.
        assertEquals(PauseRung.MEDIA_KEY, TimerPlan.evaluatePauseLadder(false, false, false, false).stoppedAtRung)
    }

    // ---- P5: bedtime volume and the restore rule (D19) ----

    @Test
    fun `bedtime index is a rounded share of max, never 0`() {
        assertEquals(8, TimerPlan.bedtimeIndex(30, 25))  // 7.5 -> 8
        assertEquals(5, TimerPlan.bedtimeIndex(30, 15))  // 4.5 -> 5
        assertEquals(1, TimerPlan.bedtimeIndex(1, 15))
        assertEquals(15, TimerPlan.bedtimeIndex(100, 15))
    }

    @Test
    fun `bedtime volume only ever lowers`() {
        assertEquals(8, TimerPlan.bedtimeTarget(currentIndex = 20, percent = 30, maxIndex = 25))
        kotlin.test.assertNull(TimerPlan.bedtimeTarget(currentIndex = 8, percent = 30, maxIndex = 25)) // already there
        kotlin.test.assertNull(TimerPlan.bedtimeTarget(currentIndex = 3, percent = 30, maxIndex = 25)) // would raise
        kotlin.test.assertNull(TimerPlan.bedtimeTarget(currentIndex = 20, percent = null, maxIndex = 25)) // off
    }

    @Test
    fun `restore after pause - untouched bedtime run goes back to the pre-start index`() {
        assertEquals(20, TimerPlan.restoreTargetIndex(false, true, true, fadeStartIndex = 8, preStartIndex = 20, touched = false))
    }

    @Test
    fun `restore after pause - touched run goes back to the fade-start index`() {
        assertEquals(11, TimerPlan.restoreTargetIndex(false, true, true, fadeStartIndex = 11, preStartIndex = 20, touched = true))
        assertTrue(TimerPlan.touchedSinceBedtime(bedtimeIndexSet = 8, indexNow = 11))
        assertFalse(TimerPlan.touchedSinceBedtime(bedtimeIndexSet = 8, indexNow = 8))
        assertFalse(TimerPlan.touchedSinceBedtime(bedtimeIndexSet = -1, indexNow = 11))
    }

    @Test
    fun `restore after pause - no bedtime volume keeps the v1 rule`() {
        assertEquals(12, TimerPlan.restoreTargetIndex(false, true, true, fadeStartIndex = 12, preStartIndex = -1, touched = false))
    }

    @Test
    fun `restore after pause - switch off, muted or still playing leave the volume`() {
        kotlin.test.assertNull(TimerPlan.restoreTargetIndex(false, true, restoreEnabled = false, fadeStartIndex = 8, preStartIndex = 20, touched = false))
        kotlin.test.assertNull(TimerPlan.restoreTargetIndex(muted = true, isQuietNow = true, restoreEnabled = true, fadeStartIndex = 8, preStartIndex = 20, touched = false))
        kotlin.test.assertNull(TimerPlan.restoreTargetIndex(false, isQuietNow = false, restoreEnabled = true, fadeStartIndex = 8, preStartIndex = 20, touched = false))
    }

    @Test
    fun `stop - untouched bedtime volume comes back only when nothing plays`() {
        assertEquals(20, TimerPlan.stopRestoreIndex(fading = false, fadeStartIndex = -1, preStartIndex = 20, touched = false, isPlaying = false))
        kotlin.test.assertNull(TimerPlan.stopRestoreIndex(fading = false, fadeStartIndex = -1, preStartIndex = 20, touched = false, isPlaying = true))
        kotlin.test.assertNull(TimerPlan.stopRestoreIndex(fading = false, fadeStartIndex = -1, preStartIndex = 20, touched = true, isPlaying = false))
        // Mid-fade: at least back to the fade-start level (v1).
        assertEquals(8, TimerPlan.stopRestoreIndex(fading = true, fadeStartIndex = 8, preStartIndex = 20, touched = false, isPlaying = true))
        assertEquals(8, TimerPlan.stopRestoreIndex(fading = true, fadeStartIndex = 8, preStartIndex = -1, touched = false, isPlaying = false))
    }

    @Test
    fun `a run far past its deadline is stale, a fading or just-ended one is not`() {
        val deadline = 1_000_000L
        assertFalse(TimerPlan.isStale(true, deadline, deadline + 5_000))
        assertFalse(TimerPlan.isStale(true, deadline, deadline + TimerPlan.STALE_AFTER_DEADLINE_MS))
        assertTrue(TimerPlan.isStale(true, deadline, deadline + TimerPlan.STALE_AFTER_DEADLINE_MS + 1))
        assertFalse(TimerPlan.isStale(false, deadline, deadline + 3_600_000))
    }

    // ---- v2.1 (REVIEW_V2 row 7): a silent moment at the single check is not trusted ----

    @Test
    fun `sound after our restore with our volume still set means the player did not really pause`() {
        assertTrue(TimerPlan.repauseNeeded(soundNow = true, currentIndex = 12, restoredIndex = 12))
        assertFalse(TimerPlan.repauseNeeded(soundNow = false, currentIndex = 12, restoredIndex = 12))
        // the user changed the volume and plays on purpose: leave it
        assertFalse(TimerPlan.repauseNeeded(soundNow = true, currentIndex = 9, restoredIndex = 12))
    }

    @Test
    fun `after a re-pause - quiet restores once more, a second comeback or a failed pause stays quiet or mutes`() {
        assertEquals(RepauseOutcome.RESTORE_AGAIN, TimerPlan.afterRepause(stillPlaying = false, repausesBefore = 0, muteFallbackEnabled = true))
        assertEquals(RepauseOutcome.STAY_QUIET, TimerPlan.afterRepause(stillPlaying = false, repausesBefore = 1, muteFallbackEnabled = true))
        assertEquals(RepauseOutcome.MUTE, TimerPlan.afterRepause(stillPlaying = true, repausesBefore = 0, muteFallbackEnabled = true))
        assertEquals(RepauseOutcome.STAY_QUIET, TimerPlan.afterRepause(stillPlaying = true, repausesBefore = 0, muteFallbackEnabled = false))
    }

    @Test
    fun `the watch after the restore outlasts track gaps and the 10 s focus hold`() {
        assertTrue(TimerPlan.POST_RESTORE_WATCH_MS >= 20_000L)
        assertEquals(0L, TimerPlan.POST_RESTORE_WATCH_MS % TimerPlan.POST_RESTORE_POLL_MS)
    }
}
