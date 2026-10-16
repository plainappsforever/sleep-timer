package com.tim.sleeptimer

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * TimerRepository falls back to `TimerSettings()` / `TimerState()` whenever a DataStore key is
 * absent (first run). These defaults are the actual product spec (SPEC.md "Defaults"), so they
 * are pinned here without needing a Context/DataStore (Robolectric is not in the pinned stack).
 */
class TimerSettingsDefaultsTest {

    @Test
    fun `default settings match the spec`() {
        val defaults = TimerSettings()
        assertEquals(30, defaults.fadeSeconds)
        assertEquals(15, defaults.extendStepMinutes)
        assertEquals(listOf(15, 30, 45, 60, 90), defaults.presets.map { it.minutes })
        assertTrue(defaults.presets.all { it.name == null && it.fadeSeconds == null })
        assertEquals(null, defaults.lastUsedPresetId)
        assertTrue(defaults.restoreVolumeAfterPause)
        assertTrue(defaults.muteFallbackEnabled)
        assertEquals(ThemeMode.SYSTEM, defaults.themeMode)
        assertEquals(30, defaults.lastUsedDurationMinutes)
        assertFalse(defaults.shakeToExtendEnabled)
        assertFalse(defaults.everLateCompletion)
        // P4: every special-access feature is off until the user turns it on (D15).
        assertFalse(defaults.lockAtEnd)
        // P6: Goodnight and its sub-switches off, 15 min, brightness + layer (D15, D42).
        assertFalse(defaults.goodnightEnabled)
        assertEquals(15, defaults.windDownMinutes)
        assertFalse(defaults.goodnightWarmth)
        assertFalse(defaults.goodnightBlackout)
        assertEquals(DimUsing.BOTH, defaults.dimUsing)
        assertEquals(null, TimerState().goodnight)
        assertTrue(OwedRestore().isEmpty)
        assertFalse(OwedRestore(brightness = 180).isEmpty)
        assertFalse(defaults.homeBeforeLock)
        assertFalse(defaults.screenOffSoonerFallback)
        assertFalse(defaults.enhancedCompatibility)
        assertTrue(defaults.presets.all { it.lockAtEnd == null })
        assertTrue(OwedRestore().isEmpty)
        // P5: DND, bedtime volume and the automatic start are off until the user turns them on (D15).
        assertFalse(defaults.dndAtStart)
        assertEquals(7 * 60, defaults.dndUntilMinuteOfDay)
        assertFalse(defaults.bedtimeVolumeEnabled)
        assertEquals(30, defaults.bedtimeVolumePercent)
        assertFalse(defaults.scheduleEnabled)
        assertEquals(Schedule.ALL_DAYS, defaults.scheduleWeekdayMask)
        assertTrue(defaults.presets.all { it.dnd == null && it.bedtimeVolumePercent == null })
        assertFalse(OwedRestore(dndActive = true).isEmpty)
        assertFalse(OwedRestore(streamIndex = 10, streamIndexSet = 4).isEmpty)
    }

    @Test
    fun `default timer state is idle with no captured volume`() {
        val defaults = TimerState()
        assertEquals(RunPhase.IDLE, defaults.phase)
        assertEquals(DoneReason.NONE, defaults.doneReason)
        assertEquals(-1, defaults.originalStreamIndex)
        assertEquals(-1, defaults.lastSetStreamIndex)
        assertFalse(defaults.mutedInsteadOfPaused)
        assertFalse(defaults.isActive)
    }

    @Test
    fun `state isActive is true only while running or fading`() {
        assertTrue(TimerState(phase = RunPhase.RUNNING).isActive)
        assertTrue(TimerState(phase = RunPhase.FADING).isActive)
        assertFalse(TimerState(phase = RunPhase.IDLE).isActive)
        assertFalse(TimerState(phase = RunPhase.DONE).isActive)
    }
}
