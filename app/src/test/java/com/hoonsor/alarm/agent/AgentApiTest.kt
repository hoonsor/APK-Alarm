package com.hoonsor.alarm.agent

import com.hoonsor.alarm.agent.AgentLogic.Pick
import com.hoonsor.alarm.data.AlarmEntity
import com.hoonsor.alarm.data.AlarmGroupEntity
import com.hoonsor.alarm.data.TimerEntity
import com.hoonsor.alarm.data.TimerState
import com.hoonsor.alarm.domain.Days
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** docs/agent-manifest.json 必須與 describe 完全一致（版本號除外） */
class ManifestTest {
    @Test fun manifestMatchesDescribe() {
        val file = listOf(File("../docs/agent-manifest.json"), File("docs/agent-manifest.json")).first { it.parentFile?.exists() == true }
        val actual = AgentCatalog.describe("com.hoonsor.alarm", "x").apply { remove("version") }
        if (System.getenv("UPDATE_MANIFEST") == "1") {
            file.writeText(actual.toString(2) + "\n", Charsets.UTF_8)
        }
        val expected = JSONObject(file.readText(Charsets.UTF_8)).apply { remove("version") }
        assertTrue(
            "docs/agent-manifest.json 與 AgentCatalog 不一致；設定環境變數 UPDATE_MANIFEST=1 後執行 gradlew testDebugUnitTest 重新產生",
            expected.similar(actual),
        )
    }

    @Test fun everyActionFollowsGuide() {
        val actions = AgentCatalog.actions()
        assertTrue(actions.any { it.getString("name").endsWith("_list") })
        actions.forEach { a ->
            val name = a.getString("name")
            assertTrue("$name 至少要 2 句例句", a.getJSONArray("examples").length() >= 2)
            assertTrue("$name 的 title 要 2–8 個字", a.getString("title").length in 2..8)
            assertEquals("$name 的 params 必須是 object", "object", a.getJSONObject("params").getString("type"))
        }
        assertEquals(actions.size, actions.map { it.getString("name") }.toSet().size)
    }
}

class AgentLogicTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private val groups = mapOf(1L to AlarmGroupEntity(id = 1, name = "上班"), 2L to AlarmGroupEntity(id = 2, name = "假日", enabled = false))
    private val alarms = listOf(
        AlarmEntity(id = 10, hour = 7, minute = 0, daysMask = Days.WEEKDAYS, memo = "起床", groupId = 1),
        AlarmEntity(id = 11, hour = 7, minute = 30, daysMask = Days.WEEKDAYS, memo = "出門", groupId = 1),
        AlarmEntity(id = 12, hour = 9, minute = 0, daysMask = Days.WEEKEND, memo = "晨跑", groupId = 2),
        AlarmEntity(id = 13, hour = 22, minute = 0, memo = "吃藥"),
        AlarmEntity(id = 14, hour = 22, minute = 0, memo = "吃藥後散步"),
    )

    private fun p(json: String) = JSONObject(json)

    @Test fun daysParsing() {
        assertEquals(Days.MWF, AgentLogic.parseDaysMask(p("""{"days":[1,3,5]}""")))
        assertEquals(Days.WEEKDAYS, AgentLogic.parseDaysMask(p("""{"repeat":"weekdays"}""")))
        assertEquals(0, AgentLogic.parseDaysMask(p("""{"days":[]}""")))
        assertEquals(Days.WEEKEND, AgentLogic.parseDaysMask(p("""{"repeat":"once","days":[6,7]}""")))
        assertNull(AgentLogic.parseDaysMask(p("{}")))
        assertEquals("[1,3,5]", AgentLogic.daysArray(Days.MWF).toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun badDayRejected() {
        AgentLogic.parseDaysMask(p("""{"days":[0]}"""))
    }

    @Test fun dateParsing() {
        val today = LocalDate.of(2026, 10, 20)
        assertEquals(LocalDate.of(2026, 10, 21), AgentLogic.parseDate("tomorrow", today))
        assertEquals(LocalDate.of(2026, 10, 22), AgentLogic.parseDate("day_after_tomorrow", today))
        assertEquals(LocalDate.of(2026, 12, 1), AgentLogic.parseDate("2026-12-01", today))
        assertNull(AgentLogic.parseDate(" ", today))
    }

    @Test fun timeQuery() {
        assertEquals(7 to 0, AgentLogic.parseTimeQuery("07:00"))
        assertEquals(7 to 5, AgentLogic.parseTimeQuery("7:5"))
        assertEquals(7 to 0, AgentLogic.parseTimeQuery("7點"))
        assertEquals(6 to 30, AgentLogic.parseTimeQuery("6點30分"))
        assertEquals(7 to 30, AgentLogic.parseTimeQuery("0730"))
        assertNull(AgentLogic.parseTimeQuery("上班"))
        assertNull(AgentLogic.parseTimeQuery("25:00"))
    }

    @Test fun matchByTimeMemoAndGroup() {
        assertEquals(listOf(10L), AgentLogic.matchAlarms(alarms, groups, "07:00").map { it.id })
        // 名稱完全相同的優先，不會連「吃藥後散步」一起選到
        assertEquals(listOf(13L), AgentLogic.matchAlarms(alarms, groups, "吃藥").map { it.id })
        assertEquals(listOf(10L, 11L), AgentLogic.matchAlarms(alarms, groups, "上班").map { it.id })
    }

    @Test fun pickAmbiguousNeedsChoice() {
        val r = AgentLogic.pickAlarms(p("""{"query":"上班"}"""), alarms, groups, allowMany = true)
        r as Pick.Fail
        assertEquals("AMBIGUOUS", r.error.getJSONObject("error").getString("code"))
        assertEquals(2, r.error.getJSONObject("error").getJSONObject("data").getJSONArray("candidates").length())
    }

    @Test fun pickAllAndIds() {
        val all = AgentLogic.pickAlarms(p("""{"query":"上班","all":true}"""), alarms, groups, allowMany = true)
        assertEquals(2, (all as Pick.Found).items.size)
        val ids = AgentLogic.pickAlarms(p("""{"ids":[10,12]}"""), alarms, groups, allowMany = true)
        assertEquals(listOf(10L, 12L), (ids as Pick.Found).items.map { it.id })
        // 修改只能一次一個：就算 all 也要問
        val one = AgentLogic.pickAlarms(p("""{"query":"上班","all":true}"""), alarms, groups, allowMany = false)
        assertTrue(one is Pick.Fail)
    }

    @Test fun pickErrors() {
        val missing = AgentLogic.pickAlarms(p("""{"ids":[99]}"""), alarms, groups, allowMany = true) as Pick.Fail
        assertEquals("NOT_FOUND", missing.error.getJSONObject("error").getString("code"))
        val none = AgentLogic.pickAlarms(p("{}"), alarms, groups, allowMany = true) as Pick.Fail
        assertEquals("INVALID_PARAMS", none.error.getJSONObject("error").getString("code"))
        val noMatch = AgentLogic.pickAlarms(p("""{"query":"游泳"}"""), alarms, groups, allowMany = true) as Pick.Fail
        assertEquals("NOT_FOUND", noMatch.error.getJSONObject("error").getString("code"))
    }

    @Test fun timerPickDefaultsToOnlyApplicable() {
        val timers = listOf(
            TimerEntity(id = 1, label = "煮麵", durationMs = 180_000, state = TimerState.RUNNING),
            TimerEntity(id = 2, label = "洗衣服", durationMs = 3_600_000),
        )
        val r = AgentLogic.pickTimers(p("{}"), timers, emptyMap(), { it.state == TimerState.RUNNING }) { it.label }
        assertEquals(listOf(1L), (r as Pick.Found).items.map { it.id })
        val both = AgentLogic.pickTimers(p("{}"), timers, emptyMap()) { it.label }
        assertTrue(both is Pick.Fail)
        val byName = AgentLogic.pickTimers(p("""{"query":"洗衣"}"""), timers, emptyMap()) { it.label }
        assertEquals(listOf(2L), (byName as Pick.Found).items.map { it.id })
    }

    @Test fun texts() {
        val now = ms(2026, 10, 20, 21, 0) // 週二
        assertEquals("今天 22:00", AgentLogic.whenText(ms(2026, 10, 20, 22, 0), now, zone))
        assertEquals("明天 07:00", AgentLogic.whenText(ms(2026, 10, 21, 7, 0), now, zone))
        assertEquals("10/24（六） 09:00", AgentLogic.whenText(ms(2026, 10, 24, 9, 0), now, zone))
        assertEquals("10 分鐘", AgentLogic.durationText(600_000))
        assertEquals("1 小時 30 分", AgentLogic.durationText(5_400_000))
        assertEquals("1 分鐘 30 秒", AgentLogic.durationText(90_000))
        assertEquals("07:00 起床（平日）", AgentLogic.alarmLabel(alarms[0], groups))
    }

    @Test fun confirmTokenBoundToTarget() {
        val t = ConfirmTokens.issue("alarm_delete:10", nowMs = 1_000)
        assertFalse(ConfirmTokens.consume(null, "alarm_delete:10", nowMs = 2_000))
        assertTrue(ConfirmTokens.consume(t, "alarm_delete:10", nowMs = 2_000))
        assertFalse("只能用一次", ConfirmTokens.consume(t, "alarm_delete:10", nowMs = 2_000))
        val other = ConfirmTokens.issue("alarm_delete:10", nowMs = 1_000)
        assertFalse("換了對象就無效", ConfirmTokens.consume(other, "alarm_delete:11", nowMs = 2_000))
        val old = ConfirmTokens.issue("alarm_delete:10", nowMs = 1_000)
        assertFalse("過期", ConfirmTokens.consume(old, "alarm_delete:10", nowMs = 200_000))
    }
}
