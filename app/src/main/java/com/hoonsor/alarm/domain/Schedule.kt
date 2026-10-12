package com.hoonsor.alarm.domain

import com.hoonsor.alarm.data.AlarmEntity
import com.hoonsor.alarm.data.AlarmGroupEntity
import com.hoonsor.alarm.data.TimerEntity
import com.hoonsor.alarm.data.TimerState
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object Days {
    val LABELS = listOf("一", "二", "三", "四", "五", "六", "日")
    const val WEEKDAYS = 0b0011111
    const val WEEKEND = 0b1100000
    const val EVERYDAY = 0b1111111
    const val MWF = 0b0010101

    fun has(mask: Int, index: Int) = mask and (1 shl index) != 0
    fun toggle(mask: Int, index: Int) = mask xor (1 shl index)

    fun summary(mask: Int): String = when (mask) {
        EVERYDAY -> "每天"
        WEEKDAYS -> "平日"
        WEEKEND -> "週末"
        0 -> "僅響一次"
        else -> "每週" + LABELS.filterIndexed { i, _ -> has(mask, i) }.joinToString("、")
    }

    /** java.util.Calendar 的 SUNDAY=1…SATURDAY=7 轉為本 App 的 bit index（週一 = 0） */
    fun fromCalendarDay(calendarDay: Int): Int = (calendarDay + 5) % 7
}

object NextTrigger {

    /**
     * 這個鬧鐘下一次要響的時間（epoch ms），不需要排程時回傳 null。
     * groupEnabled = false 時（群組被關閉）只保留已在進行中的貪睡。
     */
    fun forAlarm(
        alarm: AlarmEntity,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        groupEnabled: Boolean = true,
    ): Long? {
        val snooze = alarm.snoozeUntil?.takeIf { it > nowMs }
        val regular = if (alarm.enabled && groupEnabled) nextRegular(alarm, nowMs, zone) else null
        return listOfNotNull(snooze, regular).minOrNull()
    }

    fun forAlarm(alarm: AlarmEntity, nowMs: Long, groups: Map<Long, AlarmGroupEntity>): Long? =
        forAlarm(alarm, nowMs, ZoneId.systemDefault(), groupEnabled = alarm.groupId?.let { groups[it]?.enabled } ?: true)

    fun nextRegular(alarm: AlarmEntity, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val from = maxOf(nowMs, alarm.notBefore ?: Long.MIN_VALUE)
        val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(from), zone)
        // 最多往後找 15 天：涵蓋「今天已過、下週同一天」，以及每週一次的鬧鐘被「略過下次」後要跳到兩週後
        // 只響一次的鬧鐘不能略過，即使資料裡留有略過時間也忽略
        val skip = if (alarm.daysMask == 0) Long.MIN_VALUE else alarm.skipUntil ?: Long.MIN_VALUE
        for (offset in 0..15) {
            val day = start.toLocalDate().plusDays(offset.toLong())
            val candidate = ZonedDateTime.of(day, LocalTime.of(alarm.hour, alarm.minute), zone)
            val ms = candidate.toInstant().toEpochMilli()
            if (ms <= nowMs || ms < from || ms <= skip) continue
            if (alarm.daysMask == 0) return ms
            val index = candidate.dayOfWeek.value - 1
            if (Days.has(alarm.daysMask, index)) return ms
        }
        return null
    }

    /** 計時器結束的牆上時間（epoch ms）。同一次開機內以 elapsedRealtime 換算，最不受改時間影響。 */
    fun forTimer(timer: TimerEntity, nowWall: Long, nowElapsed: Long, bootCount: Int): Long? {
        if (timer.state != TimerState.RUNNING) return null
        val elapsedEnd = timer.endAtElapsed
        return if (elapsedEnd != null && timer.bootCount != null && timer.bootCount == bootCount) {
            nowWall + (elapsedEnd - nowElapsed)
        } else {
            timer.endAtWall
        }
    }

    fun timerRemaining(timer: TimerEntity, nowWall: Long, nowElapsed: Long, bootCount: Int): Long = when (timer.state) {
        TimerState.RUNNING -> ((forTimer(timer, nowWall, nowElapsed, bootCount) ?: nowWall) - nowWall).coerceAtLeast(0)
        TimerState.PAUSED -> timer.remainingMs
        TimerState.RINGING, TimerState.DONE -> 0
    }
}

object Fmt {
    fun hm(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

    /** 計時器顯示：未滿一小時為 mm:ss，否則 h:mm:ss */
    fun duration(ms: Long): String {
        val total = (ms + 999) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** 碼錶：mm:ss.cc */
    fun stopwatch(ms: Long): String {
        val cs = (ms / 10) % 100
        val s = (ms / 1000) % 60
        val m = ms / 60000
        return "%02d:%02d.%02d".format(m, s, cs)
    }

    fun until(targetMs: Long, nowMs: Long): String {
        val mins = ((targetMs - nowMs) / 60000).coerceAtLeast(0)
        val d = mins / (60 * 24)
        val h = (mins % (60 * 24)) / 60
        val m = mins % 60
        return when {
            d > 0 -> "$d 天 $h 小時後"
            h > 0 -> "$h 小時 $m 分後"
            else -> "$m 分鐘後"
        }
    }

    fun dateTime(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
        return "%04d/%02d/%02d %02d:%02d".format(z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute)
    }
}
