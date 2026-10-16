package com.tim.sleeptimer

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Nightly automatic start (SPEC F19, DECISIONS D20) and the Do Not Disturb "until" time (D19): pure
 * java.time, no Android imports. Weekday mask: bit 0 = Monday … bit 6 = Sunday.
 */
object Schedule {
    const val ALL_DAYS = 0x7F
    const val MINUTES_PER_DAY = 24 * 60

    /**
     * A re-arm after boot, a clock change or an app update still fires an occurrence that passed at most
     * this long ago and was not handled yet (the phone rebooted over 23:00, the clock jumped forward).
     * A scheduled start that arrives later than this is skipped: the user's evening has moved on.
     */
    const val GRACE_MILLIS = 30 * 60_000L

    fun isDaySet(mask: Int, day: DayOfWeek): Boolean = mask and (1 shl (day.value - 1)) != 0

    fun withDay(mask: Int, day: DayOfWeek, on: Boolean): Int {
        val bit = 1 shl (day.value - 1)
        return (if (on) mask or bit else mask and bit.inv()) and ALL_DAYS
    }

    /**
     * First moment strictly after [from] that falls on a day in [weekdayMask] at [minuteOfDay] local
     * time in [from]'s zone; null when no day is selected. A time that does not exist that day (spring
     * forward) moves later by the gap; a time that exists twice (fall back) is the first one.
     */
    fun nextOccurrence(from: ZonedDateTime, minuteOfDay: Int, weekdayMask: Int): ZonedDateTime? {
        if (weekdayMask and ALL_DAYS == 0) return null
        val m = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        val time = LocalTime.of(m / 60, m % 60)
        for (i in 0..7L) {
            val date = from.toLocalDate().plusDays(i)
            if (!isDaySet(weekdayMask, date.dayOfWeek)) continue
            val candidate = ZonedDateTime.of(date, time, from.zone)
            if (candidate.isAfter(from)) return candidate
        }
        return null
    }

    /**
     * The occurrence to arm the alarm for. [graceMillis] = 0 for a settings change or app open (never
     * fire a time that already passed), [GRACE_MILLIS] after boot / clock change / app update (an
     * occurrence missed a moment ago is armed in the past, so AlarmManager fires it at once). Never an
     * occurrence at or before [lastHandledMillis], so one occurrence can't start two timers.
     */
    fun nextToArm(
        now: ZonedDateTime,
        minuteOfDay: Int,
        weekdayMask: Int,
        lastHandledMillis: Long?,
        graceMillis: Long,
    ): ZonedDateTime? {
        var from = now.minusNanos(graceMillis.coerceAtLeast(0L) * 1_000_000L)
        if (lastHandledMillis != null && lastHandledMillis >= from.toInstant().toEpochMilli()) {
            from = ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastHandledMillis), now.zone)
        }
        return nextOccurrence(from, minuteOfDay, weekdayMask)
    }

    /** v2.1 owner decision (REVIEW_V2 row 3): Do Not Disturb that a timer switched on lasts at most this long. */
    const val DND_MAX_HOURS = 9L

    /**
     * When the DND switched on at [start] ends: the next [morningMinuteOfDay] or [DND_MAX_HOURS] after [start],
     * whichever is EARLIER — a night ends at the morning time, a daytime nap never silences the evening.
     */
    fun dndUntil(start: ZonedDateTime, morningMinuteOfDay: Int): ZonedDateTime {
        val cap = start.plusHours(DND_MAX_HOURS)
        val morning = nextOccurrence(start, morningMinuteOfDay, ALL_DAYS) ?: return cap
        return if (morning.isBefore(cap)) morning else cap
    }

    /** v2.1 (REVIEW_V2 row 8): an owed media volume is tried again 10 minutes before the morning time. */
    fun owedVolumeRetryMinuteOfDay(morningMinuteOfDay: Int): Int = Math.floorMod(morningMinuteOfDay - 10, MINUTES_PER_DAY)

    /** A fired occurrence starts a timer only if it is not more than [GRACE_MILLIS] late. */
    fun isTimely(scheduledMillis: Long, nowMillis: Long): Boolean = nowMillis - scheduledMillis <= GRACE_MILLIS

    /** Monday … Sunday, the mask's bit order, for the settings chips. */
    val DAYS_IN_ORDER: List<DayOfWeek> = DayOfWeek.values().toList()
}
