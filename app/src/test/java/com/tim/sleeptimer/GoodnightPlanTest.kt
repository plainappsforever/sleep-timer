package com.tim.sleeptimer

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** v2 P6: Goodnight invariants (SPEC F20, D21-D23, D42). */
class GoodnightPlanTest {

    private val ps = (0..100).map { it / 100f }

    private fun params(
        start: Int = 180,
        auto: Boolean = false,
        method: BacklightMethod = BacklightMethod.WINDOW,
        overlay: Boolean = true,
        warmth: Boolean = true,
        blackout: Boolean = true,
    ) = GoodnightPlan.Params(start, auto, method, overlay, warmth, blackout)

    private val allParams: List<GoodnightPlan.Params> = buildList {
        for (auto in listOf(false, true)) for (method in BacklightMethod.entries) for (overlay in listOf(false, true))
            for (warmth in listOf(false, true)) for (blackout in listOf(false, true)) for (start in listOf(1, 30, 64, 180, 255))
                add(GoodnightPlan.Params(start, auto, method, overlay, warmth, blackout))
    }

    /** The panel level while we leave the backlight alone: the user's manual value, or an (unknown) auto level of 40. */
    private fun held(p: GoodnightPlan.Params): Int = if (p.autoMode) 40 else p.startBrightness.coerceAtLeast(1)

    /** Physical light of one content colour: backlight fraction × composite luma. */
    private fun light(step: GoodnightPlan.Step, rgb: Triple<Int, Int, Int>, p: GoodnightPlan.Params): Float =
        (step.backlight ?: held(p)) / 255f * GoodnightPlan.compositeLuma(step.overlayArgb, rgb.first, rgb.second, rgb.third)

    private val white = Triple(255, 255, 255)
    private val grey = Triple(128, 128, 128)
    private val black = Triple(0, 0, 0)

    @Test
    fun `backlight never rises over the wind-down, for every mode and method`() {
        for (p in allParams) {
            var last = Int.MAX_VALUE
            var sawNull = false
            for (x in ps) {
                val b = GoodnightPlan.backlight(x, p)
                if (b == null) { sawNull = true; continue }
                assertTrue(b <= last, "rose to $b after $last at $x for $p")
                assertTrue(b >= GoodnightPlan.FLOOR)
                last = b
            }
            if (p.method != BacklightMethod.NONE) assertEquals(GoodnightPlan.FLOOR, GoodnightPlan.backlight(1f, p), "floor at the end for $p")
            if (sawNull && p.method != BacklightMethod.NONE) assertTrue(p.autoMode)
        }
    }

    @Test
    fun `manual mode starts exactly at the user's value and follows a perceptual curve`() {
        val p = params(start = 180)
        assertEquals(180, GoodnightPlan.backlight(0f, p))
        val mid = GoodnightPlan.backlight(0.5f, p)!!
        // Gamma 2.2: halfway in perceived brightness is far below the linear midpoint (90).
        assertTrue(mid in 30..60, "mid=$mid")
        assertEquals(1, GoodnightPlan.backlight(1f, p))
    }

    @Test
    fun `auto mode leaves the backlight alone until the end, then only the floor - never min(saved, 64)`() {
        for (m in listOf(BacklightMethod.WINDOW, BacklightMethod.SYSTEM)) for (overlay in listOf(true, false)) {
            val p = params(auto = true, method = m, overlay = overlay, start = 255)
            assertNull(GoodnightPlan.backlight(0f, p))
            assertNull(GoodnightPlan.backlight(0.99f, p))
            assertEquals(GoodnightPlan.FLOOR, GoodnightPlan.backlight(1f, p))
            // The plan's auto start value would have been min(255, 64) = 64: it is never written (D42, L11).
            assertTrue(ps.none { GoodnightPlan.backlight(it, p) == GoodnightPlan.AUTO_START_CAP })
        }
    }

    @Test
    fun `overlay darkness never decreases over progress or blackout`() {
        for (p in allParams) {
            var last = -1
            for (x in ps) {
                val a = GoodnightPlan.step(x, 0f, p).overlayAlpha
                assertTrue(a >= last, "alpha fell at $x for $p")
                last = a
            }
            for (q in ps) {
                val a = GoodnightPlan.step(1f, q, p).overlayAlpha
                assertTrue(a >= last, "alpha fell in blackout at $q for $p")
                last = a
            }
        }
    }

    @Test
    fun `physical light on white and grey never increases, including the switch to the floor`() {
        for (p in allParams) for (rgb in listOf(white, grey)) {
            var last = Float.MAX_VALUE
            val timeline = ps.map { it to 0f } + ps.map { 1f to it }
            for ((x, q) in timeline) {
                val l = light(GoodnightPlan.step(x, q, p), rgb, p)
                assertTrue(l <= last + 0.5f, "light rose ${last} -> $l at p=$x q=$q for $p on $rgb")
                last = minOf(last, l)
            }
        }
    }

    @Test
    fun `never brighter than the phone was before the wind-down began, on white and grey`() {
        // Auto mode: the backlight is left to auto (held level 40 here) until the floor (D42), so no case is excluded.
        for (p in allParams)
            for (rgb in listOf(white, grey)) {
                val before = held(p) / 255f * GoodnightPlan.compositeLuma(0, rgb.first, rgb.second, rgb.third)
                for (x in ps) for (q in listOf(0f, 0.5f, 1f)) {
                    val l = light(GoodnightPlan.step(x, if (x < 1f) 0f else q, p), rgb, p)
                    assertTrue(l <= before + 0.5f, "brighter than before: $l > $before at $x for $p on $rgb")
                }
            }
    }



    @Test
    fun `warm tint with compensation is never lighter than a neutral layer of the same base darkness on white and grey`() {
        val warm = params(method = BacklightMethod.NONE)
        val neutral = warm.copy(warmth = false)
        for (x in ps) {
            val w = GoodnightPlan.overlayArgb(x, 0f, warm)
            // Neutral layer at the warm layer's BASE alpha (before compensation).
            val base = maxOf(GoodnightPlan.OVERLAY_DIM_MAX_ALPHA * x, GoodnightPlan.TINT_MAX_ALPHA * x)
            val n = GoodnightPlan.argb(Math.round(minOf(GoodnightPlan.MAX_ALPHA, base) * 255f), 0, 0, 0)
            for (rgb in listOf(white, grey)) {
                assertTrue(
                    GoodnightPlan.compositeLuma(w, rgb.first, rgb.second, rgb.third) <=
                        GoodnightPlan.compositeLuma(n, rgb.first, rgb.second, rgb.third) + 1f,
                    "warm lighter than neutral at $x on $rgb",
                )
            }
            assertTrue(GoodnightPlan.overlayArgb(x, 0f, neutral).let { (it and 0xFFFFFF) == 0 }, "neutral layer is black")
        }
        // Over black the tint adds a faint, bounded red (physics of an overlay; Dimmer D-V3-22 bound was < 40).
        val end = GoodnightPlan.overlayArgb(1f, 0f, warm)
        assertTrue(GoodnightPlan.compositeLuma(end, 0, 0, 0) < 30f)
        // Warm colour is Dimmer's dark "Candle" red, never a bright orange.
        assertEquals(GoodnightPlan.WARM_R, (end ushr 16) and 0xFF)
        assertEquals(GoodnightPlan.WARM_G, (end ushr 8) and 0xFF)
    }

    @Test
    fun `blackout only with the overlay and only when switched on`() {
        val noOverlay = params(overlay = false, method = BacklightMethod.SYSTEM)
        assertEquals(0, GoodnightPlan.overlayArgb(1f, 1f, noOverlay))
        val off = params(blackout = false)
        assertEquals(GoodnightPlan.overlayArgb(1f, 0f, off), GoodnightPlan.overlayArgb(1f, 1f, off))
        val on = GoodnightPlan.overlayArgb(1f, 1f, params())
        assertEquals(Math.round(GoodnightPlan.BLACKOUT_ALPHA * 255f), (on ushr 24) and 0xFF)
        assertEquals(0, on and 0xFFFFFF, "blackout is black")
    }

    @Test
    fun `no overlay at the very start of the wind-down in manual mode without warmth`() {
        assertEquals(0, GoodnightPlan.step(0f, 0f, params(warmth = false)).overlayAlpha)
        // Overlay only: the overlay starts clear too and does all of the dimming.
        val only = params(method = BacklightMethod.NONE, warmth = false)
        assertEquals(0, GoodnightPlan.step(0f, 0f, only).overlayAlpha)
        assertEquals(Math.round(GoodnightPlan.OVERLAY_DIM_MAX_ALPHA * 255f), GoodnightPlan.step(1f, 0f, only).overlayAlpha)
    }

    @Test
    fun `run timeline - wind-down ends at fade start and is clamped to the timer`() {
        val run = GoodnightPlan.newRun(0L, 150_000L, 10, 2, warmth = false, blackout = true, dimUsing = DimUsing.BOTH)
        assertEquals(20_000L, run.progress.startMillis)
        assertEquals(140_000L, run.progress.endMillis)
        // No blackout until the end sequence asks for it (after the pause).
        assertEquals(0f, run.black.value(Long.MAX_VALUE - 1))
        val b = run.withBlackoutFrom(200_000L)
        assertEquals(0f, b.black.value(200_000L))
        assertEquals(1f, b.black.value(200_000L + GoodnightPlan.BLACKOUT_MILLIS))
        val short = GoodnightPlan.newRun(0L, 70_000L, 10, 15, false, false, DimUsing.BOTH)
        assertEquals(0L, short.progress.startMillis) // 15 min asked, 60 s possible
        assertEquals(60_000L, short.windDownMillis)
        assertEquals(1, short.windDownMinutesShown)
        assertEquals(0f, run.progress.value(0L))
        assertEquals(0.5f, run.progress.value(80_000L))
        assertEquals(1f, run.progress.value(140_000L))
    }

    @Test
    fun `extend re-plan never lowers progress, holds, then continues to the new end`() {
        val run = GoodnightPlan.newRun(0L, 20 * 60_000L, 30, 15, true, true, DimUsing.BOTH)
        val now = 12 * 60_000L // 7 of 15 wind-down minutes gone
        val before = run.progress.value(now)
        val ext = run.replanned(now, 35 * 60_000L, 30)
        assertEquals(before, ext.progress.value(now), 1e-6f)
        var last = before
        var t = now
        while (t <= 36 * 60_000L) {
            val v = ext.progress.value(t)
            assertTrue(v >= last - 1e-6f, "progress fell at $t")
            last = v
            t += 5_000L
        }
        assertEquals(1f, ext.progress.value(35 * 60_000L - 30_000L))
        // Held at the old value right after the extend.
        assertEquals(before, ext.progress.value(now + 60_000L), 1e-6f)
        // A re-plan never touches the blackout (it only exists after the run is over).
    }

    @Test
    fun `reduce and End now finish the wind-down sooner, never brighter`() {
        val run = GoodnightPlan.newRun(0L, 30 * 60_000L, 30, 15, false, false, DimUsing.BOTH)
        val now = 20 * 60_000L
        val v = run.progress.value(now)
        val endNow = run.replanned(now, now + 10_000L, 10)
        assertEquals(v, endNow.progress.value(now), 1e-6f)
        assertEquals(1f, endNow.progress.value(now + GoodnightPlan.MIN_SEGMENT_MILLIS))
        val reduced = run.replanned(now, 25 * 60_000L, 30)
        assertTrue(reduced.progress.value(now + 60_000L) >= run.progress.value(now + 60_000L) - 1e-6f)
    }

    @Test
    fun `method resolution - window first with the overlay, system otherwise, nothing for overlay only`() {
        assertEquals(BacklightMethod.WINDOW, GoodnightPlan.resolveMethod(DimUsing.BOTH, true, true))
        assertEquals(BacklightMethod.WINDOW, GoodnightPlan.resolveMethod(DimUsing.BOTH, true, false))
        assertEquals(BacklightMethod.SYSTEM, GoodnightPlan.resolveMethod(DimUsing.BOTH, false, true))
        assertEquals(BacklightMethod.NONE, GoodnightPlan.resolveMethod(DimUsing.BOTH, false, false))
        assertEquals(BacklightMethod.SYSTEM, GoodnightPlan.resolveMethod(DimUsing.BOTH, true, true, preferWindow = false))
        assertEquals(BacklightMethod.NONE, GoodnightPlan.resolveMethod(DimUsing.OVERLAY_ONLY, true, true))
        assertTrue(GoodnightPlan.available(DimUsing.BOTH, false, true))
        assertTrue(GoodnightPlan.available(DimUsing.BOTH, true, false))
        assertFalse(GoodnightPlan.available(DimUsing.BOTH, false, false))
        assertFalse(GoodnightPlan.available(DimUsing.OVERLAY_ONLY, false, true))
    }

    @Test
    fun `window value matches the system's own int to float mapping`() {
        assertEquals(-1f, GoodnightPlan.windowBrightness(null))
        assertEquals(0f, GoodnightPlan.windowBrightness(1))
        assertEquals(0f, GoodnightPlan.windowBrightness(0))
        // AOSP's own mapping of 200 is 0.78346; ours sits half a step below, never above.
        assertTrue(GoodnightPlan.windowBrightness(200) <= 0.78346455f)
        assertTrue(GoodnightPlan.windowBrightness(200) >= 0.78346455f - 1f / 254f)
        assertTrue(GoodnightPlan.windowBrightness(255) < 1f)
        // Monotone: a lower level never gives a higher override.
        assertTrue((2..255).all { GoodnightPlan.windowBrightness(it - 1) <= GoodnightPlan.windowBrightness(it) })
    }

    @Test
    fun `restore rules decide from the current values`() {
        // Manual, still ours -> both back (auto mode 1 too).
        assertEquals(BrightnessRestore.Plan(180, 1), BrightnessRestore.plan(180, 1, 1, 3, 1, 0))
        assertEquals(BrightnessRestore.Plan(180, 0), BrightnessRestore.plan(180, 0, 5, 7, 6, 0))
        // In-flight write (previous value still there) and rounding (+-2).
        assertEquals(BrightnessRestore.Plan(180, 0), BrightnessRestore.plan(180, 0, 5, 9, 9, 0))
        // User moved the slider: keep it, restore only the mode.
        assertEquals(BrightnessRestore.Plan(null, 1), BrightnessRestore.plan(180, 1, 1, 3, 120, 0))
        // User switched auto on: our value goes back quietly; theirs stays.
        assertEquals(BrightnessRestore.Plan(180, null), BrightnessRestore.plan(180, 1, 1, 3, 1, 1))
        assertEquals(BrightnessRestore.Plan(null, null), BrightnessRestore.plan(180, 1, 1, 3, 150, 1))
        assertTrue(BrightnessRestore.tookOver(10, 12, 60, 0))
        assertTrue(BrightnessRestore.tookOver(10, 12, 10, 1))
        assertFalse(BrightnessRestore.tookOver(10, 12, 11, 0))
    }

    @Test
    fun `codec round-trips a run and rejects garbage`() {
        val run = GoodnightPlan.newRun(1_000L, 900_000L, 30, 10, true, false, DimUsing.OVERLAY_ONLY)
            .copy(started = true, startBrightness = 180, autoMode = true, method = BacklightMethod.SYSTEM, overlay = true, tookOver = true, bootCount = 7)
            .replanned(500_000L, 1_800_000L, 30)
        val decoded = GoodnightCodec.decode(GoodnightCodec.encode(run))
        assertNotNull(decoded)
        assertEquals(run, decoded)
        assertNull(GoodnightCodec.decode(null))
        assertNull(GoodnightCodec.decode("v1|wd=x"))
        assertNull(GoodnightCodec.decode("v2|wd=1"))
    }
}
