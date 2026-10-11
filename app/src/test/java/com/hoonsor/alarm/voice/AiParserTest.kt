package com.hoonsor.alarm.voice

import com.hoonsor.alarm.domain.Days
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AiParserTest {
    private val ids = setOf(3L, 4L)
    private val groups = setOf(1L)

    @Test fun createAlarmWithThinkAndFence() {
        val raw = """
            <think>使用者要每週一三五…</think>
            ```json
            {"actions":[{"type":"create_alarm","hour":7,"minute":15,"days":[1,3,5],"date":null,"memo":"晨跑","group_id":1}],"question":null,"error":null}
            ```
        """.trimIndent()
        val r = AiParser.parseResponse(raw, ids, groups) as VoiceIntent.Alarm
        assertEquals(7, r.hour); assertEquals(15, r.minute)
        assertEquals(Days.MWF, r.daysMask)
        assertEquals("晨跑", r.memo)
        assertEquals(1L, r.groupId)
        assertNull(r.notBeforeDate)
    }

    @Test fun oneShotWithDateAndUnknownGroup() {
        val raw = """{"actions":[{"type":"create_alarm","hour":6,"minute":0,"days":[],"date":"2026-10-11","memo":"","group_id":99}]}"""
        val r = AiParser.parseResponse(raw, ids, groups) as VoiceIntent.Alarm
        assertEquals(0, r.daysMask)
        assertEquals(LocalDate.of(2026, 10, 11), r.notBeforeDate)
        assertNull(r.groupId)
    }

    @Test fun timer() {
        val r = AiParser.parseResponse("""{"actions":[{"type":"create_timer","seconds":1500,"memo":"起來走動"}]}""", ids, groups) as VoiceIntent.Timer
        assertEquals(1_500_000L, r.durationMs)
    }

    @Test fun updateOnlyGivenFields() {
        val r = AiParser.parseResponse("""{"actions":[{"type":"update_alarm","id":3,"hour":6,"minute":30}]}""", ids, groups) as VoiceIntent.Update
        assertEquals(3L, r.alarmId); assertEquals(6, r.hour); assertEquals(30, r.minute)
        assertNull(r.daysMask); assertNull(r.memo); assertNull(r.enabled)
    }

    @Test fun updateUnknownIdIsIgnored() {
        val r = AiParser.parseResponse("""{"actions":[{"type":"update_alarm","id":42,"hour":6}]}""", ids, groups)
        assertTrue(r is VoiceIntent.Unknown)
    }

    @Test fun batch() {
        val raw = """{"actions":[{"type":"create_alarm","hour":7,"minute":0,"days":[1,2,3,4,5]},{"type":"create_alarm","hour":8,"minute":0,"days":[6,7]}]}"""
        val r = AiParser.parseResponse(raw, ids, groups) as VoiceIntent.Batch
        assertEquals(2, r.actions.size)
    }

    @Test fun question() {
        val raw = """{"actions":[],"question":{"text":"是今天還是明天？","options":[
            {"label":"今天 10/11","actions":[{"type":"create_alarm","hour":7,"minute":0,"days":[],"date":"2026-10-11"}]},
            {"label":"明天 10/12","actions":[{"type":"create_alarm","hour":7,"minute":0,"days":[],"date":"2026-10-12"}]}]}}"""
        val r = AiParser.parseResponse(raw, ids, groups) as VoiceIntent.Ambiguous
        assertEquals(2, r.options.size)
    }

    @Test fun error() {
        val r = AiParser.parseResponse("""{"actions":[],"error":"刪除請在 App 內長按鬧鐘操作"}""", ids, groups) as VoiceIntent.Unknown
        assertTrue(r.reason.contains("刪除"))
    }

    @Test fun createGroupThenAlarmInNewGroup() {
        val raw = """{"actions":[{"type":"create_group","name":"上班"},{"type":"create_alarm","hour":7,"minute":0,"days":[1,2,3,4,5],"memo":"出門","group_id":null,"group_name":"上班"}]}"""
        val r = AiParser.parseResponse(raw, ids, groups) as VoiceIntent.Batch
        assertEquals("上班", (r.actions[0] as VoiceIntent.CreateGroup).name)
        val a = r.actions[1] as VoiceIntent.Alarm
        assertNull(a.groupId)
        assertEquals("上班", a.groupName)
    }

    @Test fun moveAndSwitchGroup() {
        val m = AiParser.parseResponse("""{"actions":[{"type":"move_alarms","ids":[3,99],"group_id":1}]}""", ids, groups) as VoiceIntent.MoveAlarms
        assertEquals(listOf(3L), m.alarmIds)
        assertEquals(1L, m.groupId)
        val s = AiParser.parseResponse("""{"actions":[{"type":"set_group","group_id":1,"enabled":false}]}""", ids, groups) as VoiceIntent.GroupSwitch
        assertEquals(false, s.enabled)
    }
}
