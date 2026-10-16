package com.tim.sleeptimer

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class ShortcutPlanTest {
    private val presets = listOf(
        Preset("p1", null, 15),
        Preset("p2", "Nap", 20),
        Preset("p3", null, 45),
        Preset("p4", "Podcast", 60),
        Preset("p5", null, 90),
    )

    @Test
    fun `dynamic shortcuts are the first four presets in order with labels`() {
        val d = ShortcutPlan.dynamic(presets)
        assertEquals(listOf("preset_p1", "preset_p2", "preset_p3", "preset_p4"), d.map { it.id })
        assertEquals("15 min", d[0].shortLabel)
        assertEquals("Start 15 min", d[0].longLabel)
        assertEquals("Nap · 20 min", d[1].shortLabel)
        assertEquals("Start Nap · 20 min", d[1].longLabel)
    }

    @Test
    fun `launcher cap below four wins, fewer presets give fewer shortcuts`() {
        assertEquals(3, ShortcutPlan.dynamic(presets, limit = 3).size)
        assertEquals(4, ShortcutPlan.dynamic(presets, limit = 10).size)
        assertEquals(2, ShortcutPlan.dynamic(presets.take(2)).size)
    }

    @Test
    fun `order changes and edits change the plan`() {
        val moved = Presets.move(presets, "p4", -2)
        assertEquals(listOf("p1", "p4", "p2", "p3"), moved.take(4).let { ShortcutPlan.dynamic(it).map { s -> s.presetId } })
        val edited = Presets.update(presets, Preset("p1", "Quick", 10))
        assertEquals("Quick · 10 min", ShortcutPlan.dynamic(edited)[0].shortLabel)
    }

    @Test
    fun `ids map back to their preset`() {
        assertEquals("p3", ShortcutPlan.presetIdOf(ShortcutPlan.dynamicId("p3")))
        assertEquals("p12", ShortcutPlan.presetIdOf(ShortcutPlan.pinnedId("p12", 1790000000000L)))
        assertNull(ShortcutPlan.presetIdOf("something_else"))
        assertNull(ShortcutPlan.presetIdOf("preset_"))
        assertNull(ShortcutPlan.presetIdOf("pin__5"))
    }

    @Test
    fun `pinned shortcuts of a deleted preset are disabled, existing ones get fresh labels`() {
        val remaining = Presets.remove(presets, "p2")
        val pinned = listOf("pin_p2_1", "pin_p4_2", "preset_p2", "preset_p1", "foreign")
        val r = ShortcutPlan.reconcilePinned(pinned, remaining)
        assertEquals(listOf("pin_p2_1", "preset_p2"), r.disableIds)
        assertEquals(listOf("pin_p4_2", "preset_p1"), r.update.map { it.id })
        assertEquals("Podcast · 60 min", r.update[0].shortLabel)
    }

    @Test
    fun `a pin made for an old preset never revives when the id is reused`() {
        // p2 deleted, a new p2 added later: the old pin has already been disabled at delete time,
        // and a new pin gets a different id, so the two never collide.
        assertEquals(false, ShortcutPlan.pinnedId("p2", 100L) == ShortcutPlan.pinnedId("p2", 200L))
    }
}
