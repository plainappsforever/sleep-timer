package com.tim.sleeptimer

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PresetsTest {

    @Test
    fun `fresh install defaults are the v1 chips, unnamed, global fade`() {
        val d = Presets.defaults()
        assertEquals(listOf(15, 30, 45, 60, 90), d.map { it.minutes })
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), d.map { it.id })
        assertTrue(d.all { it.name == null && it.fadeSeconds == null })
    }

    @Test
    fun `codec round-trips names, fades and nulls`() {
        val list = listOf(
            Preset("p1", null, 15),
            Preset("p2", "Podcast", 45, 10),
            Preset("p3", "Nap", 20, 0),
        )
        assertEquals(list, PresetCodec.decode(PresetCodec.encode(list)))
    }

    @Test
    fun `codec escapes separators and percent signs in names`() {
        val nasty = "a,b;c=d|e%f 100% éü →"
        val list = listOf(Preset("p1", nasty.take(Presets.MAX_NAME_LENGTH), 30))
        val encoded = PresetCodec.encode(list)
        // exactly one raw '|' (the version separator) and one entry
        assertEquals(1, encoded.count { it == '|' })
        assertEquals(1, encoded.split(";").size)
        assertEquals(list, PresetCodec.decode(encoded))
    }

    @Test
    fun `codec output carries the version prefix`() {
        assertTrue(PresetCodec.encode(Presets.defaults()).startsWith("v1|"))
    }

    @Test
    fun `decode tolerates unknown fields and missing optional fields`() {
        val decoded = assertNotNull(PresetCodec.decode("v1|id=p1,min=30,lock=1,future=xyz;id=p2,min=45,fade=30,dnd=true"))
        // "lock" became a known field in P4; "future" and "dnd" are still unknown here.
        assertEquals(listOf(Preset("p1", null, 30, lockAtEnd = true), Preset("p2", null, 45, 30)), decoded)
    }

    @Test
    fun `decode rejects garbage and falls back to null`() {
        assertNull(PresetCodec.decode(null))
        assertNull(PresetCodec.decode(""))
        assertNull(PresetCodec.decode("15,30,45"))
        assertNull(PresetCodec.decode("v2|id=p1,min=30"))
        assertNull(PresetCodec.decode("v1|"))
        assertNull(PresetCodec.decode("v1|id=p1"))
        assertNull(PresetCodec.decode("v1|min=30"))
        assertNull(PresetCodec.decode("v1|id=p1,min=abc"))
    }

    @Test
    fun `decode skips broken entries but keeps good ones`() {
        val decoded = assertNotNull(PresetCodec.decode("v1|id=p1,min=x;id=p2,min=45;;id=p3,name=%ZZ,min=5"))
        // p3's malformed name escape drops the name only, not the preset
        assertEquals(listOf(Preset("p2", null, 45), Preset("p3", null, 5)), decoded)
    }

    @Test
    fun `decode clamps out-of-range values and drops duplicate ids`() {
        val decoded = assertNotNull(PresetCodec.decode("v1|id=p1,min=9999,fade=99999;id=p1,min=10;id=p2,min=0"))
        assertEquals(listOf(Preset("p1", null, 480, 600), Preset("p2", null, 1)), decoded)
    }

    @Test
    fun `decode keeps at most eight presets`() {
        val raw = "v1|" + (1..12).joinToString(";") { "id=p$it,min=$it" }
        assertEquals(8, PresetCodec.decode(raw)!!.size)
    }

    @Test
    fun `v1 migration turns chip minutes into unnamed presets`() {
        val migrated = Presets.migrateLegacyMinutes("10,25,50")
        assertEquals(listOf(10, 25, 50), migrated.map { it.minutes })
        assertTrue(migrated.all { it.name == null && it.fadeSeconds == null })
        assertEquals(3, migrated.map { it.id }.toSet().size)
    }

    @Test
    fun `v1 migration falls back to defaults when nothing usable is stored`() {
        assertEquals(Presets.defaults(), Presets.migrateLegacyMinutes(null))
        assertEquals(Presets.defaults(), Presets.migrateLegacyMinutes(""))
        assertEquals(Presets.defaults(), Presets.migrateLegacyMinutes("abc,def"))
    }

    @Test
    fun `v1 migration clamps and caps the list`() {
        val migrated = Presets.migrateLegacyMinutes("0,1000,1,2,3,4,5,6,7,8")
        assertEquals(8, migrated.size)
        assertEquals(listOf(1, 480), migrated.take(2).map { it.minutes })
    }

    @Test
    fun `label shows name and minutes, or just minutes`() {
        assertEquals("30 min", Preset("p1", null, 30).label)
        assertEquals("30 min", Preset("p1", "  ", 30).label)
        assertEquals("Podcast · 45 min", Preset("p1", "Podcast", 45).label)
    }

    @Test
    fun `effective fade uses the preset value or the global one`() {
        assertEquals(30, Preset("p1", null, 30).effectiveFadeSeconds(30))
        assertEquals(10, Preset("p1", null, 30, 10).effectiveFadeSeconds(30))
        assertEquals(0, Preset("p1", null, 30, 0).effectiveFadeSeconds(30))
    }

    @Test
    fun `add appends a preset with a fresh id and stops at eight`() {
        var list = Presets.defaults()
        list = Presets.add(list)
        assertEquals(6, list.size)
        assertEquals("p6", list.last().id)
        list = Presets.add(Presets.add(list))
        assertEquals(8, list.size)
        assertEquals(list, Presets.add(list))
        assertEquals(8, list.map { it.id }.toSet().size)
    }

    @Test
    fun `next id reuses a freed slot`() {
        val list = Presets.defaults().filterNot { it.id == "p2" }
        assertEquals("p2", Presets.nextId(list))
    }

    @Test
    fun `remove keeps at least one preset`() {
        val one = listOf(Preset("p1", null, 30))
        assertEquals(one, Presets.remove(one, "p1"))
        assertEquals(4, Presets.remove(Presets.defaults(), "p3").size)
    }

    @Test
    fun `move reorders and clamps at the ends`() {
        val d = Presets.defaults()
        assertEquals(listOf("p2", "p1", "p3", "p4", "p5"), Presets.move(d, "p1", +1).map { it.id })
        assertEquals(listOf("p1", "p2", "p3", "p5", "p4"), Presets.move(d, "p5", -1).map { it.id })
        assertEquals(d, Presets.move(d, "p1", -1))
        assertEquals(d, Presets.move(d, "p5", +1))
        assertEquals(d, Presets.move(d, "nope", +1))
    }

    @Test
    fun `update sanitizes the edited preset only`() {
        val d = Presets.defaults()
        val updated = Presets.update(d, Preset("p2", "  Nap  ", 9999, 10))
        assertEquals(Preset("p2", "Nap", 480, 10), updated[1])
        assertEquals(d[0], updated[0])
        assertNull(Presets.update(d, Preset("p2", "   ", 30))[1].name)
    }

    // ---- v2 P4: per-preset "Lock screen at end" ----

    @Test
    fun `codec round-trips the lock field including null`() {
        val list = listOf(
            Preset("p1", minutes = 15, lockAtEnd = true),
            Preset("p2", minutes = 30, lockAtEnd = false),
            Preset("p3", minutes = 45),
        )
        val encoded = PresetCodec.encode(list)
        assertTrue(encoded.contains("lock=1") && encoded.contains("lock=0"))
        assertEquals(list, PresetCodec.decode(encoded))
    }

    @Test
    fun `a P1-P3 string without lock decodes to null, a bad lock value too`() {
        val decoded = assertNotNull(PresetCodec.decode("v1|id=p1,min=15,fade=10;id=p2,min=30,lock=maybe"))
        assertEquals(listOf<Boolean?>(null, null), decoded.map { it.lockAtEnd })
    }

    @Test
    fun `codec round-trips the P5 dnd and bedtime volume fields including off and null`() {
        val list = listOf(
            Preset("p1", null, 15, dnd = true, bedtimeVolumePercent = 30),
            Preset("p2", "Nap", 20, dnd = false, bedtimeVolumePercent = 0),
            Preset("p3", null, 45),
        )
        val raw = PresetCodec.encode(list)
        assertTrue(raw.contains("dnd=1,vol=30"))
        assertEquals(list, PresetCodec.decode(raw))
    }

    @Test
    fun `a P4 string without the P5 fields decodes to null for both, bad values too`() {
        val decoded = PresetCodec.decode("v1|id=p1,min=15,lock=1;id=p2,min=30,dnd=x,vol=y")!!
        assertEquals(listOf<Boolean?>(null, null), decoded.map { it.dnd })
        assertEquals(listOf<Int?>(null, null), decoded.map { it.bedtimeVolumePercent })
        assertEquals(true, decoded[0].lockAtEnd)
    }

    @Test
    fun `bedtime volume is clamped to 5-100 with 0 kept as off`() {
        assertEquals(5, Presets.sanitize(Preset("p1", null, 15, bedtimeVolumePercent = 2)).bedtimeVolumePercent)
        assertEquals(100, Presets.sanitize(Preset("p1", null, 15, bedtimeVolumePercent = 180)).bedtimeVolumePercent)
        assertEquals(0, Presets.sanitize(Preset("p1", null, 15, bedtimeVolumePercent = -3)).bedtimeVolumePercent)
    }

    @Test
    fun `effective dnd and bedtime volume - preset wins, null falls back to the global setting`() {
        val plain = Preset("p1", null, 15)
        assertEquals(true, plain.effectiveDnd(true))
        assertEquals(false, plain.copy(dnd = false).effectiveDnd(true))
        assertEquals(true, plain.copy(dnd = true).effectiveDnd(false))
        assertEquals(30, plain.effectiveBedtimePercent(true, 30))
        assertNull(plain.effectiveBedtimePercent(false, 30))
        assertNull(plain.copy(bedtimeVolumePercent = 0).effectiveBedtimePercent(true, 30))
        assertEquals(50, plain.copy(bedtimeVolumePercent = 50).effectiveBedtimePercent(false, 30))
    }

    @Test
    fun `codec round-trips the P6 goodnight field, and a P5 string decodes to null`() {
        val list = listOf(Preset("p1", minutes = 15, goodnight = true), Preset("p2", minutes = 30, goodnight = false), Preset("p3", minutes = 45))
        val raw = PresetCodec.encode(list)
        assertTrue(raw.contains("gn=1"))
        assertEquals(list, PresetCodec.decode(raw))
        assertEquals(null, PresetCodec.decode("v1|id=p1,min=15,dnd=1,vol=30")!!.first().goodnight)
        assertEquals(null, PresetCodec.decode("v1|id=p1,min=15,gn=x")!!.first().goodnight)
        assertTrue(Preset("p1", minutes = 1).effectiveGoodnight(true))
        assertFalse(Preset("p1", minutes = 1, goodnight = false).effectiveGoodnight(true))
    }
}
