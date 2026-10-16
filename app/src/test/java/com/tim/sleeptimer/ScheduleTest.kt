package com.tim.sleeptimer

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ScheduleTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = berlin) =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone)

    private val h2300 = 23 * 60

    @Test
    fun `later today when the time has not passed`() {
        // 2026-09-30 is a Wednesday
        val next = Schedule.nextOccurrence(at(2026, 9, 30, 21, 0), h2300, Schedule.ALL_DAYS)
        assertEquals(at(2026, 9, 30, 23, 0), next)
    }

    @Test
    fun `exactly now or already passed rolls to the next selected day`() {
        assertEquals(at(2026, 10, 1, 23, 0), Schedule.nextOccurrence(at(2026, 9, 30, 23, 0), h2300, Schedule.ALL_DAYS))
        assertEquals(at(2026, 10, 1, 23, 0), Schedule.nextOccurrence(at(2026, 9, 30, 23, 30), h2300, Schedule.ALL_DAYS))
    }

    @Test
    fun `weekdays only skips the weekend`() {
        var mask = 0
        for (d in listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            mask = Schedule.withDay(mask, d, true)
        }
        assertEquals(0x1F, mask)
        // Friday 2026-10-02 23:30 -> Monday 2026-10-05 23:00
        assertEquals(at(2026, 10, 5, 23, 0), Schedule.nextOccurrence(at(2026, 10, 2, 23, 30), h2300, mask))
    }

    @Test
    fun `a single weekday whose time passed today is a week later`() {
        val wednesdayOnly = Schedule.withDay(0, DayOfWeek.WEDNESDAY, true)
        assertEquals(at(2026, 10, 7, 23, 0), Schedule.nextOccurrence(at(2026, 9, 30, 23, 1), h2300, wednesdayOnly))
    }

    @Test
    fun `no day selected means no occurrence`() {
        assertNull(Schedule.nextOccurrence(at(2026, 9, 30, 21, 0), h2300, 0))
        assertEquals(0, Schedule.withDay(Schedule.withDay(0, DayOfWeek.SUNDAY, true), DayOfWeek.SUNDAY, false))
        assertTrue(Schedule.isDaySet(Schedule.ALL_DAYS, DayOfWeek.SUNDAY))
    }

    @Test
    fun `spring forward - a time inside the gap moves later by the gap`() {
        // Europe/Berlin 2026-03-29: 02:00 -> 03:00
        val next = Schedule.nextOccurrence(at(2026, 3, 29, 1, 0), 2 * 60 + 30, Schedule.ALL_DAYS)!!
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30), next.toLocalDateTime())
        // Across the change the wall-clock time stays 23:00 (23 h of real time between the two nights).
        val sat = Schedule.nextOccurrence(at(2026, 3, 28, 22, 0), h2300, Schedule.ALL_DAYS)!!
        val sun = Schedule.nextOccurrence(sat, h2300, Schedule.ALL_DAYS)!!
        assertEquals(LocalDateTime.of(2026, 3, 29, 23, 0), sun.toLocalDateTime())
        assertEquals(23L * 3600, java.time.Duration.between(sat, sun).seconds)
    }

    @Test
    fun `fall back - a time that exists twice fires once, at the first one`() {
        // Europe/Berlin 2026-10-25: 03:00 -> 02:00, so 02:30 happens twice
        val first = Schedule.nextOccurrence(at(2026, 10, 25, 1, 0), 2 * 60 + 30, Schedule.ALL_DAYS)!!
        assertEquals(2 * 3600, first.offset.totalSeconds) // +02:00, the earlier one
        // Re-armed right after firing: the second 02:30 (+01:00) is not another occurrence.
        val after = Schedule.nextOccurrence(first.plusSeconds(60), 2 * 60 + 30, Schedule.ALL_DAYS)!!
        assertEquals(LocalDateTime.of(2026, 10, 26, 2, 30), after.toLocalDateTime())
        val handled = Schedule.nextToArm(first.plusMinutes(61), 2 * 60 + 30, Schedule.ALL_DAYS, first.toInstant().toEpochMilli(), Schedule.GRACE_MILLIS)!!
        assertEquals(LocalDateTime.of(2026, 10, 26, 2, 30), handled.toLocalDateTime())
    }

    @Test
    fun `time zone change keeps the local time`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val nowTokyo = at(2026, 9, 30, 21, 0).withZoneSameInstant(tokyo) // 04:00 on Oct 1 in Tokyo
        assertEquals(at(2026, 10, 1, 23, 0, tokyo), Schedule.nextOccurrence(nowTokyo, h2300, Schedule.ALL_DAYS))
    }

    @Test
    fun `settings change never arms a time that already passed`() {
        val next = Schedule.nextToArm(at(2026, 9, 30, 23, 10), h2300, Schedule.ALL_DAYS, null, 0L)
        assertEquals(at(2026, 10, 1, 23, 0), next)
    }

    @Test
    fun `boot or clock jump shortly after the time arms the missed occurrence once`() {
        val missed = Schedule.nextToArm(at(2026, 9, 30, 23, 10), h2300, Schedule.ALL_DAYS, null, Schedule.GRACE_MILLIS)
        assertEquals(at(2026, 9, 30, 23, 0), missed) // in the past: AlarmManager fires it at once
        val handledMillis = missed!!.toInstant().toEpochMilli()
        assertEquals(
            at(2026, 10, 1, 23, 0),
            Schedule.nextToArm(at(2026, 9, 30, 23, 10), h2300, Schedule.ALL_DAYS, handledMillis, Schedule.GRACE_MILLIS),
        )
        // Too long ago: not armed again.
        assertEquals(
            at(2026, 10, 1, 23, 0),
            Schedule.nextToArm(at(2026, 9, 30, 23, 45), h2300, Schedule.ALL_DAYS, null, Schedule.GRACE_MILLIS),
        )
    }

    @Test
    fun `clock set back after firing does not start the same night twice`() {
        val fired = at(2026, 9, 30, 23, 0).toInstant().toEpochMilli()
        val next = Schedule.nextToArm(at(2026, 9, 30, 22, 30), h2300, Schedule.ALL_DAYS, fired, Schedule.GRACE_MILLIS)
        assertEquals(at(2026, 10, 1, 23, 0), next)
    }

    @Test
    fun `a firing more than the grace late is not timely`() {
        val t = at(2026, 9, 30, 23, 0).toInstant().toEpochMilli()
        assertTrue(Schedule.isTimely(t, t + 5_000))
        assertTrue(Schedule.isTimely(t, t + Schedule.GRACE_MILLIS))
        assertFalse(Schedule.isTimely(t, t + Schedule.GRACE_MILLIS + 1))
    }

    // ---- v2.1 owner decision: DND ends at the morning time or 9 h after the start, whichever is earlier ----

    private val h0700 = 7 * 60

    @Test
    fun `night start - DND ends at the morning time`() {
        assertEquals(at(2026, 10, 1, 7, 0), Schedule.dndUntil(at(2026, 9, 30, 23, 30), h0700))
        assertEquals(at(2026, 10, 1, 7, 0), Schedule.dndUntil(at(2026, 10, 1, 2, 0), h0700))
    }

    @Test
    fun `daytime nap - DND ends 9 hours after the start, not the next morning`() {
        assertEquals(at(2026, 9, 30, 23, 0), Schedule.dndUntil(at(2026, 9, 30, 14, 0), h0700))
        assertEquals(at(2026, 9, 30, 16, 30), Schedule.dndUntil(at(2026, 9, 30, 7, 30), h0700))
    }

    @Test
    fun `exactly 9 hours before the morning time - both rules agree`() {
        assertEquals(at(2026, 10, 1, 7, 0), Schedule.dndUntil(at(2026, 9, 30, 22, 0), h0700))
    }

    @Test
    fun `DND cap counts real hours across a DST change`() {
        // 2026-10-25 03:00 CEST -> 02:00 CET in Berlin: 9 real hours after 20:00 is 04:00 local.
        assertEquals(at(2026, 10, 25, 4, 0), Schedule.dndUntil(at(2026, 10, 24, 20, 0), 8 * 60))
        assertTrue(Schedule.DND_MAX_HOURS == 9L)
    }

    @Test
    fun `an owed volume is retried 10 minutes before the morning time, across midnight too`() {
        assertEquals(6 * 60 + 50, Schedule.owedVolumeRetryMinuteOfDay(7 * 60))
        assertEquals(23 * 60 + 55, Schedule.owedVolumeRetryMinuteOfDay(5))
    }
}
