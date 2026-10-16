package com.plainapps.nightcap

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test

class ShakeDetectorTest {

    private val g = ShakeDetector.GRAVITY
    private val stepMs = 60L // SENSOR_DELAY_UI ≈ 60 ms per sample

    /** Feeds magnitudes (as total |a| along x with z = 0) at [stepMs] spacing; returns detection times. */
    private fun feed(detector: ShakeDetector, magnitudes: List<Float>, startMs: Long = 0L, dtMs: Long = stepMs): List<Long> {
        val hits = mutableListOf<Long>()
        magnitudes.forEachIndexed { i, m ->
            val t = startMs + i * dtMs
            if (detector.onSample(m, 0f, 0f, t)) hits += t
        }
        return hits
    }

    private fun rest(n: Int) = List(n) { g }
    private val spike = g + 20f // 20 m/s² above gravity

    @Test
    fun `two strong peaks within 800 ms is a shake`() {
        val d = ShakeDetector()
        // rest, peak, 3 samples down (180 ms), peak → second peak 240 ms after the first
        val hits = feed(d, rest(3) + spike + rest(3) + spike + rest(3))
        assertEquals(1, hits.size)
    }

    @Test
    fun `the emulator console pattern 30 0 30 0 at gravity z is a shake`() {
        val d = ShakeDetector()
        var t = 0L
        var hits = 0
        // adb emu sensor set acceleration x:0:9.8, each value held ~250 ms (≈ 4 samples)
        for (x in listOf(0f, 30f, 0f, 30f, 0f)) {
            repeat(4) { if (d.onSample(x, 0f, 9.8f, t)) hits++; t += stepMs }
        }
        assertEquals(1, hits)
    }

    @Test
    fun `a single bump is not a shake`() {
        val d = ShakeDetector()
        assertEquals(emptyList(), feed(d, rest(5) + spike + (g + 25f) + rest(20)))
    }

    @Test
    fun `impact ringing faster than 100 ms counts as one peak`() {
        val d = ShakeDetector()
        // 10 ms sampling: jolt, drops below re-arm for 30 ms, rebounds above the threshold 40 ms later
        val samples = rest(5) + spike + rest(3) + (g + 15f) + rest(50)
        assertEquals(emptyList(), feed(d, samples, dtMs = 10L))
    }

    @Test
    fun `two peaks further apart than 800 ms are not a shake`() {
        val d = ShakeDetector()
        // 15 rest samples = 960 ms between the peaks
        assertEquals(emptyList(), feed(d, rest(3) + spike + rest(15) + spike + rest(3)))
    }

    @Test
    fun `a sustained high reading without falling back is a single peak`() {
        val d = ShakeDetector()
        assertEquals(emptyList(), feed(d, rest(2) + List(20) { spike } + rest(5)))
    }

    @Test
    fun `hovering between re-arm and threshold does not re-arm`() {
        val d = ShakeDetector()
        // peak, drop only to 8 above g (above the 6 re-arm level), peak again → still one peak
        assertEquals(emptyList(), feed(d, rest(2) + spike + (g + 8f) + (g + 8f) + spike + rest(5)))
    }

    @Test
    fun `walking-like motion never triggers`() {
        val d = ShakeDetector()
        // 2 Hz steps, ±6 m/s² around g, plus a sharper heel strike of +9 m/s² every step, for 60 s
        val samples = (0 until 1000).map { i ->
            val t = i * stepMs / 1000.0
            val base = g + 6f * sin(2 * PI * 2.0 * t).toFloat()
            if (i % 8 == 0) g + 9f else base
        }
        assertEquals(emptyList(), feed(d, samples))
    }

    @Test
    fun `a readjusting sleeper turning the phone over does not trigger`() {
        val d = ShakeDetector()
        // Rotation moves gravity between axes; magnitude stays near g with small jolts (+4)
        var hits = 0
        var t = 0L
        for (i in 0 until 50) {
            val angle = i / 50.0 * PI
            val x = (g * sin(angle)).toFloat()
            val z = (g * kotlin.math.cos(angle)).toFloat() + if (i % 10 == 0) 4f else 0f
            if (d.onSample(x, 0f, z, t)) hits++
            t += stepMs
        }
        assertEquals(0, hits)
    }

    @Test
    fun `cooldown of 2 s swallows continued shaking, then a new shake counts`() {
        val d = ShakeDetector()
        val shake = rest(2) + spike + rest(2) + spike
        // shake at 0.., keep shaking during the next ~1.5 s, then pause and shake again after cooldown
        val first = feed(d, shake + rest(2) + spike + rest(2) + spike + rest(2) + spike + rest(2) + spike)
        assertEquals(1, first.size, "continued shaking inside the cooldown must not re-trigger")
        val second = feed(d, shake + rest(2), startMs = first.first() + 2_100L)
        assertEquals(1, second.size, "a shake after the cooldown counts again")
    }

    @Test
    fun `moderate peaks of 10 above g do not count`() {
        val d = ShakeDetector()
        val medium = g + 10f
        assertEquals(emptyList(), feed(d, rest(2) + medium + rest(2) + medium + rest(2) + medium))
    }

    @Test
    fun `reset forgets a half-finished shake`() {
        val d = ShakeDetector()
        assertEquals(emptyList(), feed(d, rest(2) + spike + rest(2)))
        d.reset()
        assertFalse(d.onSample(spike, 0f, 0f, 400L))
    }

    @Test
    fun `window opens five minutes plus the fade before the deadline`() {
        val deadline = 10_000_000L
        assertEquals(deadline - 30_000L - 300_000L, ShakeDetector.windowStartMillis(deadline, 30))
        assertEquals(deadline - 300_000L, ShakeDetector.windowStartMillis(deadline, 0))
    }
}
