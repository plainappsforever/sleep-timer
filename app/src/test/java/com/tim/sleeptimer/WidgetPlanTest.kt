package com.tim.sleeptimer

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class WidgetPlanTest {
    private val presets = listOf(
        Preset("p1", null, 15),
        Preset("p2", "Nap", 20),
        Preset("p3", null, 45),
        Preset("p4", "Podcast", 60),
        Preset("p5", null, 90),
    )
    private val now = 1_000_000_000_000L

    @Test
    fun `idle shows the first three presets in user order at default width`() {
        val c = assertIs<WidgetContent.Idle>(WidgetPlan.content(TimerState(), presets, now))
        assertEquals(listOf("p1", "p2", "p3"), c.buttons.map { it.presetId })
    }

    @Test
    fun `button is two lines when named and one line when unnamed`() {
        assertEquals(PresetButton("p2", "Nap", "20 min"), WidgetPlan.button(presets[1]))
        assertEquals(PresetButton("p1", null, "15 min"), WidgetPlan.button(presets[0]))
    }

    @Test
    fun `fewer presets than buttons shows only what exists`() {
        val c = assertIs<WidgetContent.Idle>(WidgetPlan.content(TimerState(), presets.take(2), now))
        assertEquals(2, c.buttons.size)
        val one = assertIs<WidgetContent.Idle>(WidgetPlan.content(TimerState(), presets.take(1), now))
        assertEquals(1, one.buttons.size)
    }

    @Test
    fun `button count follows the widget width`() {
        assertEquals(3, WidgetPlan.buttonCount(250, 8))
        assertEquals(3, WidgetPlan.buttonCount(320, 8))
        assertEquals(2, WidgetPlan.buttonCount(180, 8))
        assertEquals(1, WidgetPlan.buttonCount(110, 8))
        assertEquals(1, WidgetPlan.buttonCount(40, 8))
        assertEquals(2, WidgetPlan.buttonCount(250, 2))
        assertEquals(1, WidgetPlan.buttonCount(250, 0))
    }

    @Test
    fun `running shows the deadline, the run's extend step and the fading flag`() {
        val running = TimerState(phase = RunPhase.RUNNING, deadlineWallClockMillis = now + 600_000, extendStepMinutes = 10)
        val c = assertIs<WidgetContent.Running>(WidgetPlan.content(running, presets, now))
        assertEquals(now + 600_000, c.deadlineWallClockMillis)
        assertEquals(10, c.extendStepMinutes)
        assertEquals(false, c.fading)
        val fading = assertIs<WidgetContent.Running>(WidgetPlan.content(running.copy(phase = RunPhase.FADING), presets, now))
        assertEquals(true, fading.fading)
    }

    @Test
    fun `finished or stopped timer shows idle again`() {
        val done = TimerState(phase = RunPhase.DONE, doneReason = DoneReason.COMPLETED_PAUSED, deadlineWallClockMillis = now - 1000)
        assertIs<WidgetContent.Idle>(WidgetPlan.content(done, presets, now))
        assertIs<WidgetContent.Idle>(WidgetPlan.content(TimerState(phase = RunPhase.IDLE), presets, now))
    }

    @Test
    fun `a stale running state long past its deadline (reboot) shows idle, a fresh overrun still shows running`() {
        val stale = TimerState(phase = RunPhase.RUNNING, deadlineWallClockMillis = now - 5 * 60_000)
        assertIs<WidgetContent.Idle>(WidgetPlan.content(stale, presets, now))
        val justEnding = TimerState(phase = RunPhase.FADING, deadlineWallClockMillis = now - 2_000)
        assertIs<WidgetContent.Running>(WidgetPlan.content(justEnding, presets, now))
    }
}
