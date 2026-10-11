package com.hoonsor.alarm.voice

import com.hoonsor.alarm.domain.Days
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class VoiceParserTest {
    private val friEvening = LocalDateTime.of(2026, 10, 9, 20, 0)
    private val friMidnight = LocalDateTime.of(2026, 10, 9, 0, 33)

    @Test fun weeklyWithMemo() {
        val r = VoiceParser.parse("每週一三五早上七點十五叫我晨跑，記得帶耳機", friEvening) as VoiceIntent.Alarm
        assertEquals(7, r.hour); assertEquals(15, r.minute)
        assertEquals(Days.MWF, r.daysMask)
        assertEquals("晨跑，記得帶耳機", r.memo)
    }

    @Test fun afternoonHalf() {
        val r = VoiceParser.parse("下午三點半提醒我開會", friEvening) as VoiceIntent.Alarm
        assertEquals(15, r.hour); assertEquals(30, r.minute)
        assertEquals("開會", r.memo)
    }

    @Test fun weekdaysDigits() {
        val r = VoiceParser.parse("平日 6:30", friEvening) as VoiceIntent.Alarm
        assertEquals(6, r.hour); assertEquals(30, r.minute); assertEquals(Days.WEEKDAYS, r.daysMask)
    }

    @Test fun timerMinutes() {
        val r = VoiceParser.parse("二十五分鐘後提醒我起來走動", friEvening) as VoiceIntent.Timer
        assertEquals(25 * 60_000L, r.durationMs)
        assertEquals("起來走動", r.memo)
    }

    @Test fun timerHourAndHalf() {
        val r = VoiceParser.parse("倒數一個半小時", friEvening) as VoiceIntent.Timer
        assertEquals(90 * 60_000L, r.durationMs)
    }

    @Test fun tomorrowAtMidnightIsAmbiguous() {
        assertTrue(VoiceParser.parse("明天早上七點叫我出門", friMidnight) is VoiceIntent.Ambiguous)
    }

    @Test fun tomorrowInEvening() {
        val r = VoiceParser.parse("明天早上七點叫我出門", friEvening) as VoiceIntent.Alarm
        assertEquals(friEvening.toLocalDate().plusDays(1), r.notBeforeDate)
    }

    @Test fun chineseNumbers() {
        assertEquals(25, VoiceParser.cn("二十五"))
        assertEquals(10, VoiceParser.cn("十"))
        assertEquals(2, VoiceParser.cn("兩"))
        assertEquals(120, VoiceParser.cn("一百二十"))
    }
}
