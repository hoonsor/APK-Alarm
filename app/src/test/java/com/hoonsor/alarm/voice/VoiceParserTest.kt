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

    @Test fun createGroupNamed() {
        val r = VoiceParser.parse("新增鬧鐘群組叫上班", friEvening) as VoiceIntent.CreateGroup
        assertEquals("上班", r.name)
    }

    @Test fun createGroupWithAlarm() {
        val r = VoiceParser.parse("新增一個群組叫家事，每天早上八點提醒我倒垃圾", friEvening) as VoiceIntent.Batch
        assertTrue(r.actions[0] is VoiceIntent.CreateGroup)
        val a = r.actions[1] as VoiceIntent.Alarm
        assertEquals("家事", a.groupName)
        assertEquals("倒垃圾", a.memo)
        assertEquals(8, a.hour)
    }

    @Test fun alarmIntoGroup() {
        val r = VoiceParser.parse("平日七點叫我出門放到上班群組", friEvening) as VoiceIntent.Alarm
        assertEquals("上班", r.groupName)
        assertEquals("出門", r.memo)
    }

    @Test fun timerGroup() {
        val c = VoiceParser.parse("新增計時器群組叫煮飯", friEvening) as VoiceIntent.CreateGroup
        assertTrue(c.forTimer)
        val t = VoiceParser.parse("十分鐘後提醒我關火放到煮飯群組", friEvening) as VoiceIntent.Timer
        assertEquals("煮飯", t.groupName)
    }

    @Test fun groupSwitch() {
        val r = VoiceParser.parse("關閉上班群組", friEvening, listOf(1L to "上班")) as VoiceIntent.GroupSwitch
        assertEquals(1L, r.groupId)
        assertEquals(false, r.enabled)
    }

    @Test fun timerPageDurationOnly() {
        val r = VoiceParser.parse("十二分鐘煮麵放到煮飯群組", friEvening, preferTimer = true) as VoiceIntent.Timer
        assertEquals(12 * 60_000L, r.durationMs)
        assertEquals("煮麵", r.memo)
        assertEquals("煮飯", r.groupName)
        assertTrue(VoiceParser.parse("七點叫我起床", friEvening, preferTimer = true) is VoiceIntent.Alarm)
    }

    // ---------- 語料測試找到的句型 ----------

    @Test fun separateWeekdays() {
        fun mask(s: String) = (VoiceParser.parse(s, friEvening) as VoiceIntent.Alarm).daysMask
        assertEquals(Days.MWF, mask("禮拜一、禮拜三、禮拜五，晚上九點四十五分"))
        assertEquals(Days.MWF, mask("週一週三週五早上十點"))
        assertEquals(0b0001010, mask("星期二還有四，早上九點"))
        assertEquals(Days.WEEKEND, mask("星期六跟星期日下午三點五十"))
        assertEquals(Days.WEEKDAYS, mask("禮拜一到五早上八點"))
        assertEquals(Days.WEEKDAYS, mask("週一到週五每天下午兩點零五分"))
        assertEquals(Days.MWF, mask("週一到週五...啊不對，是一三五，早上六點四十五分"))
    }

    @Test fun quarterHours() {
        fun hm(s: String) = (VoiceParser.parse(s, friEvening) as VoiceIntent.Alarm).let { it.hour to it.minute }
        assertEquals(20 to 15, hm("晚上八點一刻"))
        assertEquals(9 to 45, hm("週二週四上午九點三刻"))
        assertEquals(17 to 45, hm("後天下午差一刻六點的鬧鐘"))
        assertEquals(7 to 5, hm("早上七點過五分"))
    }

    @Test fun weekdayFollowedByDigits() {
        val a = VoiceParser.parse("週六18:05，內容是澆花", friEvening) as VoiceIntent.Alarm
        assertEquals(18, a.hour); assertEquals(5, a.minute); assertEquals(1 shl 5, a.daysMask)
        val b = VoiceParser.parse("每個星期六十一點四十分響", friEvening) as VoiceIntent.Alarm
        assertEquals(11, b.hour); assertEquals(40, b.minute); assertEquals(1 shl 5, b.daysMask)
    }

    @Test fun eveningWords() {
        val a = VoiceParser.parse("後天晚間七點十分還書", friEvening) as VoiceIntent.Alarm
        assertEquals(19, a.hour)
        val b = VoiceParser.parse("明晚八點二十的鬧鐘", friEvening) as VoiceIntent.Alarm
        assertEquals(20, b.hour); assertEquals(friEvening.toLocalDate().plusDays(1), b.notBeforeDate)
        assertEquals(14, (VoiceParser.parse("明天兩點四十下午", friEvening) as VoiceIntent.Alarm).hour)
        assertEquals(13, (VoiceParser.parse("明天 1 點 PM 的鬧鐘", friEvening) as VoiceIntent.Alarm).hour)
    }

    @Test fun clockHourWords() {
        fun ms(s: String) = (VoiceParser.parse(s, friEvening) as VoiceIntent.Timer).durationMs
        assertEquals(30 * 60_000L, ms("倒數半個鐘頭喔，記得提醒倒垃圾"))
        assertEquals(60 * 60_000L, ms("倒數一個鐘，時間到記得叫我"))
        assertEquals(90 * 60_000L, ms("設個計時器，一個半鐘後提醒我吃藥"))
    }

    @Test fun trailingHalfAndRepeatedDuration() {
        fun ms(s: String) = (VoiceParser.parse(s, friEvening) as VoiceIntent.Timer).durationMs
        assertEquals(150 * 60_000L, ms("兩小時半之後提醒我準備簡報"))
        assertEquals(150 * 60_000L, ms("幫我倒數兩個鐘頭半"))
        assertEquals(90 * 60_000L, ms("設定倒數一小時半"))
        assertEquals(330_000L, ms("倒數5分半，提醒寫週報"))
        assertEquals(30 * 60_000L, ms("倒數半小時，30 分鐘後提醒我吃藥"))
        assertEquals(90 * 60_000L, ms("倒數一小時，三十分鐘"))
    }

    @Test fun noPeriodOneToSixMeansAfternoon() {
        fun hm(s: String) = (VoiceParser.parse(s, friEvening) as VoiceIntent.Alarm).let { it.hour to it.minute }
        assertEquals(14 to 0, hm("明天兩點繳電話費"))
        assertEquals(14 to 0, hm("下午兩點"))
        assertEquals(2 to 0, hm("凌晨兩點"))
        assertEquals(14 to 0, hm("14:00"))
        assertEquals(7 to 0, hm("七點起床"))
        assertEquals(6 to 0, hm("明天早上記得六點叫我"))
    }
}
