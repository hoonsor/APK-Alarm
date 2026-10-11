package com.hoonsor.alarm.domain

import com.hoonsor.alarm.data.AlarmEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NextTriggerTest {
    private val zone = ZoneId.of("Asia/Taipei")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    // 2026/10/09 是週五
    private val friMidnight = ms(2026, 10, 9, 0, 33)

    @Test fun oneShotLaterToday() {
        val a = AlarmEntity(hour = 7, minute = 0)
        assertEquals(ms(2026, 10, 9, 7, 0), NextTrigger.forAlarm(a, friMidnight, zone))
    }

    @Test fun oneShotPassedGoesTomorrow() {
        val a = AlarmEntity(hour = 0, minute = 10)
        assertEquals(ms(2026, 10, 10, 0, 10), NextTrigger.forAlarm(a, friMidnight, zone))
    }

    @Test fun notBeforeTomorrow() {
        val a = AlarmEntity(hour = 7, minute = 0, notBefore = ms(2026, 10, 10, 0, 0))
        assertEquals(ms(2026, 10, 10, 7, 0), NextTrigger.forAlarm(a, friMidnight, zone))
    }

    @Test fun mwfFromFriday() {
        val a = AlarmEntity(hour = 7, minute = 15, daysMask = Days.MWF)
        assertEquals(ms(2026, 10, 9, 7, 15), NextTrigger.forAlarm(a, friMidnight, zone))
        val afterRing = ms(2026, 10, 9, 7, 16)
        assertEquals(ms(2026, 10, 12, 7, 15), NextTrigger.forAlarm(a, afterRing, zone))
    }

    @Test fun sameWeekdayNextWeek() {
        val a = AlarmEntity(hour = 0, minute = 10, daysMask = 1 shl 4) // 只有週五
        assertEquals(ms(2026, 10, 16, 0, 10), NextTrigger.forAlarm(a, friMidnight, zone))
    }

    @Test fun disabledWithSnoozeStillRings() {
        val snooze = ms(2026, 10, 9, 0, 40)
        val a = AlarmEntity(hour = 7, minute = 0, enabled = false, snoozeUntil = snooze)
        assertEquals(snooze, NextTrigger.forAlarm(a, friMidnight, zone))
    }

    @Test fun disabledNoSnooze() {
        assertNull(NextTrigger.forAlarm(AlarmEntity(hour = 7, minute = 0, enabled = false), friMidnight, zone))
    }

    @Test fun calendarDayMapping() {
        assertEquals(0, Days.fromCalendarDay(java.util.Calendar.MONDAY))
        assertEquals(6, Days.fromCalendarDay(java.util.Calendar.SUNDAY))
    }
}
