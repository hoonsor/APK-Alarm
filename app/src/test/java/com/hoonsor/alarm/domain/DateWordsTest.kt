package com.hoonsor.alarm.domain

import com.hoonsor.alarm.agent.AgentLogic
import com.hoonsor.alarm.data.AlarmEntity
import com.hoonsor.alarm.voice.VoiceIntent
import com.hoonsor.alarm.voice.VoiceParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 指定日期的一次性鬧鐘：「下週一」「這週五」「11 月 20 號」 */
class DateWordsTest {
    private val sat = LocalDate.of(2026, 11, 14)  // 週六
    private val sun = LocalDate.of(2026, 11, 15)  // 週日
    private val mon = LocalDate.of(2026, 11, 16)  // 週一
    private val zone = ZoneId.of("Asia/Taipei")

    @Test fun 下週一以週一開始算() {
        assertEquals(mon, DateWords.weekday(sat, 1, 0))         // 週六說下週一＝後天
        assertEquals(mon, DateWords.weekday(sun, 1, 0))         // 週日說下週一＝明天
        assertEquals(LocalDate.of(2026, 11, 23), DateWords.weekday(mon, 1, 0)) // 週一說下週一＝七天後
        assertEquals(LocalDate.of(2026, 11, 30), DateWords.weekday(mon, 2, 0)) // 下下週一
    }

    @Test fun 這週() {
        assertEquals(LocalDate.of(2026, 11, 20), DateWords.weekday(mon, 0, 4))  // 這週五
        assertEquals(LocalDate.of(2026, 11, 13), DateWords.weekday(sat, 0, 4))  // 週六說這週五＝昨天（由呼叫端處理）
    }

    @Test fun 最近的星期幾() {
        assertEquals(mon, DateWords.upcoming(sat, 0, includeToday = true))
        assertEquals(sat, DateWords.upcoming(sat, 5, includeToday = true))
        assertEquals(LocalDate.of(2026, 11, 21), DateWords.upcoming(sat, 5, includeToday = false))
    }

    @Test fun 月日沒說年份() {
        assertEquals(LocalDate.of(2026, 11, 20), DateWords.monthDay(sat, 11, 20))
        assertEquals(LocalDate.of(2027, 1, 5), DateWords.monthDay(sat, 1, 5))   // 已過就是明年
        assertEquals(sat, DateWords.monthDay(sat, 11, 14))                      // 今天就是今天
        assertNull(DateWords.monthDay(sat, 2, 30))
    }

    @Test fun 顯示文字() {
        assertEquals("後天", DateWords.label(mon, sat))
        assertEquals("11/23（一）", DateWords.label(LocalDate.of(2026, 11, 23), sat))
        assertEquals("後天 11/16（一） 09:00", AgentLogic.dateWhen(mon, 9, 0, sat))
        assertEquals("11/20（五） 15:00", AgentLogic.dateWhen(LocalDate.of(2026, 11, 20), 15, 0, sat))
    }

    // ---------- Agent API ----------

    @Test fun API日期格式() {
        assertEquals(mon, AgentLogic.parseDate("next_mon", sat))
        assertEquals(mon, AgentLogic.parseDate("下週一", sat))
        assertEquals(mon, AgentLogic.parseDate("下禮拜一", sat))
        assertEquals(LocalDate.of(2026, 11, 23), AgentLogic.parseDate("next2_mon", sat))
        assertEquals(LocalDate.of(2026, 11, 20), AgentLogic.parseDate("this_fri", mon))
        assertEquals(LocalDate.of(2026, 11, 20), AgentLogic.parseDate("11/20", sat))
        assertEquals(LocalDate.of(2026, 11, 20), AgentLogic.parseDate("11月20日", sat))
        assertEquals(LocalDate.of(2026, 12, 1), AgentLogic.parseDate("2026/12/1", sat))
        assertEquals(LocalDate.of(2026, 12, 1), AgentLogic.parseDate("2026-12-01", sat))
    }

    @Test(expected = IllegalArgumentException::class)
    fun API看不懂的日期() {
        AgentLogic.parseDate("找一天", sat)
    }

    @Test fun 只響一次卻給了星期() {
        val now = ZonedDateTime.of(2026, 11, 14, 20, 0, 0, 0, zone)
        val p = JSONObject("""{"hour":9,"repeat":"once","days":[1]}""")
        assertEquals(mon, AgentLogic.onceWeekday(p, 9, 0, now))
        // 今天（週六）的時間已經過了 → 下週六
        assertEquals(LocalDate.of(2026, 11, 21), AgentLogic.onceWeekday(JSONObject("""{"repeat":"once","days":[6]}"""), 9, 0, now))
        // 沒說 once：照舊是每週重複
        assertNull(AgentLogic.onceWeekday(JSONObject("""{"days":[1]}"""), 9, 0, now))
        assertNull(AgentLogic.onceWeekday(JSONObject("""{"repeat":"once","days":[1,3]}"""), 9, 0, now))
    }

    @Test fun 指定日期的鬧鐘排程() {
        val nowMs = ZonedDateTime.of(2026, 11, 14, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        val nb = LocalDate.of(2026, 12, 25).atStartOfDay(zone).toInstant().toEpochMilli()
        val a = AlarmEntity(hour = 9, minute = 0, notBefore = nb)
        val next = NextTrigger.nextRegular(a, nowMs, zone)!!
        assertEquals(ZonedDateTime.of(2026, 12, 25, 9, 0, 0, 0, zone).toInstant().toEpochMilli(), next) // 超過 15 天也排得到
        assertEquals(LocalDate.of(2026, 12, 25), NextTrigger.fixedDate(a, nowMs, zone))
        assertNull(NextTrigger.fixedDate(a.copy(daysMask = Days.WEEKDAYS), nowMs, zone))
        val past = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertNull(NextTrigger.fixedDate(a.copy(notBefore = past), nowMs, zone))
    }

    @Test fun 標籤顯示日期() {
        val nowMs = ZonedDateTime.of(2026, 11, 14, 20, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
        val nb = mon.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("09:00 開會（11/16 週一）", AgentLogic.alarmLabel(AlarmEntity(hour = 9, minute = 0, memo = "開會", notBefore = nb), emptyMap(), nowMs))
    }

    // ---------- App 內語音 ----------

    private val satEvening = LocalDateTime.of(2026, 11, 14, 20, 0)

    private fun alarm(text: String, now: LocalDateTime = satEvening) = VoiceParser.parse(text, now) as VoiceIntent.Alarm

    @Test fun 語音下週一開會() {
        val r = alarm("設定下週一9點開會的一次性鬧鐘")
        assertEquals(9, r.hour); assertEquals(0, r.minute)
        assertEquals(0, r.daysMask)
        assertEquals(mon, r.notBeforeDate)
    }

    @Test fun 語音下星期三提醒() {
        val r = alarm("下星期三下午三點提醒我交報告")
        assertEquals(15, r.hour); assertEquals(0, r.daysMask)
        assertEquals(LocalDate.of(2026, 11, 18), r.notBeforeDate)
        assertEquals("交報告", r.memo)
    }

    @Test fun 語音幾月幾號() {
        val r = alarm("十一月二十號早上八點叫我去健檢")
        assertEquals(8, r.hour); assertEquals(0, r.daysMask)
        assertEquals(LocalDate.of(2026, 11, 20), r.notBeforeDate)
        assertEquals("去健檢", r.memo)
        assertEquals(LocalDate.of(2026, 11, 20), alarm("11/20 早上八點叫我去健檢").notBeforeDate)
    }

    @Test fun 語音一次性加星期() {
        val r = alarm("一次性的週三早上八點鬧鐘")
        assertEquals(0, r.daysMask)
        assertEquals(LocalDate.of(2026, 11, 18), r.notBeforeDate)
    }

    @Test fun 語音每週一照舊是重複() {
        val r = alarm("每週一早上九點叫我開會")
        assertEquals(1, r.daysMask)
        assertNull(r.notBeforeDate)
        assertEquals(1, alarm("週一早上九點叫我開會").daysMask)
    }

    @Test fun 語音這週已經過了() {
        val r = VoiceParser.parse("這週五早上九點叫我", satEvening)
        assertTrue(r is VoiceIntent.Unknown)
    }
}
