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

    // 2026/10/12 週一 07:01（平日鬧鐘剛響過）→ 略過下次＝跳過週二，週三照常
    @Test fun skipNextWeekday() {
        val mon = ms(2026, 10, 12, 7, 1)
        val a = AlarmEntity(hour = 7, minute = 0, daysMask = Days.WEEKDAYS)
        val tue = ms(2026, 10, 13, 7, 0)
        assertEquals(tue, NextTrigger.forAlarm(a, mon, zone))
        val skipped = a.copy(skipUntil = tue)
        assertEquals(ms(2026, 10, 14, 7, 0), NextTrigger.forAlarm(skipped, mon, zone))
        // 過了週二之後，略過自然失效
        assertEquals(ms(2026, 10, 15, 7, 0), NextTrigger.forAlarm(skipped, ms(2026, 10, 14, 7, 1), zone))
    }

    @Test fun skipWeeklyJumpsTwoWeeks() {
        val mon = ms(2026, 10, 12, 8, 0)
        val a = AlarmEntity(hour = 7, minute = 0, daysMask = 1) // 只有週一
        val next = ms(2026, 10, 19, 7, 0)
        assertEquals(next, NextTrigger.forAlarm(a, mon, zone))
        assertEquals(ms(2026, 10, 26, 7, 0), NextTrigger.forAlarm(a.copy(skipUntil = next), mon, zone))
    }

    @Test fun oneShotIgnoresSkip() {
        val now = ms(2026, 10, 12, 6, 0)
        val today = ms(2026, 10, 12, 7, 0)
        val a = AlarmEntity(hour = 7, minute = 0, skipUntil = today)
        assertEquals(today, NextTrigger.forAlarm(a, now, zone))
    }
}
