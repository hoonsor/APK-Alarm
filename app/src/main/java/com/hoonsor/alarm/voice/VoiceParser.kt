package com.hoonsor.alarm.voice

import com.hoonsor.alarm.domain.Days
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 語音解析結果。離線規則解析與 AI（MiniMax M3）解析都產生相同結構。 */
sealed interface VoiceIntent {
    data class Alarm(
        val hour: Int,
        val minute: Int,
        val daysMask: Int,
        val memo: String,
        /** 只響一次時，不得早於這一天的 00:00 */
        val notBeforeDate: LocalDate? = null,
        val groupId: Long? = null,
    ) : VoiceIntent

    data class Timer(val durationMs: Long, val memo: String) : VoiceIntent

    /** 修改既有鬧鐘；null 欄位代表不變 */
    data class Update(
        val alarmId: Long,
        val hour: Int? = null,
        val minute: Int? = null,
        val daysMask: Int? = null,
        val memo: String? = null,
        val enabled: Boolean? = null,
    ) : VoiceIntent

    /** 一句話包含多個動作 */
    data class Batch(val actions: List<VoiceIntent>) : VoiceIntent

    /** 時間有兩種合理解讀時，交給使用者選 */
    data class Ambiguous(val question: String, val options: List<Pair<String, VoiceIntent>>) : VoiceIntent

    data class Unknown(val reason: String) : VoiceIntent
}

/**
 * 離線規則解析器，處理常見句型：
 *  「每週一三五早上七點十五叫我晨跑」「平日 6:30」「明天早上七點叫我出門」
 *  「二十五分鐘後提醒我起來走動」「倒數一個半小時」
 */
object VoiceParser {
    private const val NUM = "[0-9零〇一二兩三四五六七八九十百]+"

    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now(ZoneId.systemDefault())): VoiceIntent {
        val text = raw.replace(" ", "").replace("，", ",").replace("。", "")
        if (text.isBlank()) return VoiceIntent.Unknown("沒有聽到內容")

        val memo = extractMemo(text)

        parseTimer(text)?.let { return VoiceIntent.Timer(it, memo) }

        val time = parseClock(text) ?: return VoiceIntent.Unknown("聽不出時間，試試「早上七點十五」或「二十五分鐘後」")
        val (hour, minute) = time
        val mask = parseDays(text)
        if (mask != 0) return VoiceIntent.Alarm(hour, minute, mask, memo)

        val today = now.toLocalDate()
        return when {
            text.contains("後天") -> VoiceIntent.Alarm(hour, minute, 0, memo, today.plusDays(2))
            text.contains("明天") || text.contains("明早") -> {
                val targetToday = now.toLocalDate().atTime(hour, minute)
                // 凌晨說「明天早上」：可能是指天亮後的今天
                if (now.hour < 5 && targetToday.isAfter(now)) {
                    val todayOpt = VoiceIntent.Alarm(hour, minute, 0, memo, today)
                    val tomorrowOpt = VoiceIntent.Alarm(hour, minute, 0, memo, today.plusDays(1))
                    VoiceIntent.Ambiguous(
                        "「明天」是指哪一天？現在是 ${now.monthValue}/${now.dayOfMonth} %02d:%02d，天還沒亮。".format(now.hour, now.minute),
                        listOf(
                            "今天 ${today.monthValue}/${today.dayOfMonth}" to todayOpt,
                            "明天 ${today.plusDays(1).monthValue}/${today.plusDays(1).dayOfMonth}" to tomorrowOpt,
                        ),
                    )
                } else {
                    VoiceIntent.Alarm(hour, minute, 0, memo, today.plusDays(1))
                }
            }
            else -> VoiceIntent.Alarm(hour, minute, 0, memo)
        }
    }

    // ---------- 計時器 ----------

    private fun parseTimer(t: String): Long? {
        val isTimer = t.contains("後") || t.contains("倒數") || t.contains("計時")
        if (!isTimer || Regex("$NUM[點:：]").containsMatchIn(t) && !t.contains("小時")) return null
        var ms = 0L
        if (t.contains("半小時") && !Regex("${NUM}個?半小時").containsMatchIn(t)) ms += 30 * 60_000L
        Regex("(${NUM})個?(半)?(小時|鐘頭)").find(t)?.let { m ->
            ms += (cn(m.groupValues[1]) ?: 0) * 3_600_000L
            if (m.groupValues[2].isNotEmpty()) ms += 30 * 60_000L
        }
        Regex("(${NUM})(分鐘|分)").find(t)?.let { ms += (cn(it.groupValues[1]) ?: 0) * 60_000L }
        Regex("(${NUM})秒").find(t)?.let { ms += (cn(it.groupValues[1]) ?: 0) * 1000L }
        return ms.takeIf { it > 0 }
    }

    // ---------- 時刻 ----------

    private fun parseClock(t: String): Pair<Int, Int>? {
        val m = Regex("(凌晨|清晨|早上|上午|中午|下午|傍晚|晚上|半夜)?($NUM)[點:：]($NUM|半)?(分)?").find(t) ?: return null
        val period = m.groupValues[1]
        var hour = cn(m.groupValues[2]) ?: return null
        val minPart = m.groupValues[3]
        val minute = when {
            minPart == "半" -> 30
            minPart.isEmpty() -> 0
            else -> cn(minPart) ?: 0
        }
        when (period) {
            "下午", "傍晚", "晚上" -> if (hour in 1..11) hour += 12
            "中午" -> if (hour in 1..2) hour += 12
            "半夜" -> if (hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    // ---------- 星期 ----------

    private fun parseDays(t: String): Int {
        if (t.contains("每天") || t.contains("每日")) return Days.EVERYDAY
        if (t.contains("平日") || t.contains("工作日") || t.contains("上班日")) return Days.WEEKDAYS
        if (t.contains("週末") || t.contains("周末")) return Days.WEEKEND
        val m = Regex("(週|周|星期|禮拜)([一二三四五六日天、和跟,]+)").find(t) ?: return 0
        var mask = 0
        m.groupValues[2].forEach { c ->
            val idx = when (c) {
                '一' -> 0; '二' -> 1; '三' -> 2; '四' -> 3; '五' -> 4; '六' -> 5; '日', '天' -> 6
                else -> -1
            }
            if (idx >= 0) mask = mask or (1 shl idx)
        }
        return mask
    }

    // ---------- 提醒文字 ----------

    private fun extractMemo(t: String): String {
        val m = Regex("(叫我|提醒我|提醒|記得)(.*)").find(t) ?: return ""
        val body = if (m.groupValues[1] == "記得") m.value else m.groupValues[2]
        return body.trim(',', '、', ' ').replace(",", "，").take(60)
    }

    /** 中文或阿拉伯數字 → Int，支援到 999（例如「二十五」「兩」「一百二十」） */
    fun cn(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        val digit = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '兩' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0
        var current = 0
        for (c in s) {
            when (c) {
                in digit.keys -> current = digit.getValue(c)
                '十' -> { total += (if (current == 0) 1 else current) * 10; current = 0 }
                '百' -> { total += (if (current == 0) 1 else current) * 100; current = 0 }
                else -> return null
            }
        }
        return (total + current).takeIf { s.isNotEmpty() }
    }
}
