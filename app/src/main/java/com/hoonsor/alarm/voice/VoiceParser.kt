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
        /** 依名稱指定群組（現有的或要新建的）；groupId 為 null 時使用，找不到同名群組就建立 */
        val groupName: String? = null,
    ) : VoiceIntent

    /** groupName：放進這個計時器群組（找不到同名群組就建立） */
    data class Timer(val durationMs: Long, val memo: String, val groupName: String? = null) : VoiceIntent

    /** 建立群組；forTimer = true 時建立計時器群組。同名群組已存在時不重複建立 */
    data class CreateGroup(val name: String, val forTimer: Boolean = false) : VoiceIntent

    /** 把鬧鐘移到群組；groupId 與 groupName 都是 null 代表移到「未分組」 */
    data class MoveAlarms(val alarmIds: List<Long>, val groupId: Long? = null, val groupName: String? = null) : VoiceIntent

    /** 開啟或關閉鬧鐘群組 */
    data class GroupSwitch(val groupId: Long, val enabled: Boolean) : VoiceIntent

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

    /**
     * @param groups 現有鬧鐘群組（id 與名稱），用來辨識「關閉上班群組」這類指令
     */
    fun parse(
        raw: String,
        now: LocalDateTime = LocalDateTime.now(ZoneId.systemDefault()),
        groups: List<Pair<Long, String>> = emptyList(),
    ): VoiceIntent {
        val full = raw.replace(" ", "").replace("，", ",").replace("。", "")
        if (full.isBlank()) return VoiceIntent.Unknown("沒有聽到內容")

        parseGroupSwitch(full, groups)?.let { return it }

        // 群組相關片語：先抽出來，剩下的文字再解析時間與提醒，避免群組名稱混進提醒文字
        val create = findCreateGroup(full)
        var text = create?.let { full.removeRange(it.range) } ?: full
        val ref = findGroupRef(text)
        if (ref != null) text = text.removeRange(ref.range)
        val groupName = ref?.name ?: create?.name
        val forTimer = create?.forTimer == true || (create != null && parseTimer(text) != null)
        val createIntent = create?.let { VoiceIntent.CreateGroup(it.name, forTimer) }

        if (create == null && Regex("(?:新增|建立|創建|新建)(?:一個|個)?(?:新的)?(?:鬧鐘|計時器?)?群組$").containsMatchIn(full)) {
            return VoiceIntent.Unknown("請說出群組名稱，例如「新增鬧鐘群組叫上班」或「建立運動群組」")
        }
        val main = parseMain(text.trim(','), now, groupName)
        return when {
            createIntent == null -> main
            main is VoiceIntent.Unknown -> createIntent
            else -> VoiceIntent.Batch(listOf(createIntent, main))
        }
    }

    private fun parseMain(text: String, now: LocalDateTime, groupName: String?): VoiceIntent {
        if (text.isBlank()) return VoiceIntent.Unknown("沒有聽到內容")
        val memo = extractMemo(text)

        parseTimer(text)?.let { return VoiceIntent.Timer(it, memo, groupName) }

        val time = parseClock(text) ?: return VoiceIntent.Unknown("聽不出時間，試試「早上七點十五」或「二十五分鐘後」")
        val (hour, minute) = time
        val mask = parseDays(text)
        if (mask != 0) return VoiceIntent.Alarm(hour, minute, mask, memo, groupName = groupName)

        val today = now.toLocalDate()
        return when {
            text.contains("後天") -> VoiceIntent.Alarm(hour, minute, 0, memo, today.plusDays(2), groupName = groupName)
            text.contains("明天") || text.contains("明早") -> {
                val targetToday = now.toLocalDate().atTime(hour, minute)
                // 凌晨說「明天早上」：可能是指天亮後的今天
                if (now.hour < 5 && targetToday.isAfter(now)) {
                    val todayOpt = VoiceIntent.Alarm(hour, minute, 0, memo, today, groupName = groupName)
                    val tomorrowOpt = VoiceIntent.Alarm(hour, minute, 0, memo, today.plusDays(1), groupName = groupName)
                    VoiceIntent.Ambiguous(
                        "「明天」是指哪一天？現在是 ${now.monthValue}/${now.dayOfMonth} %02d:%02d，天還沒亮。".format(now.hour, now.minute),
                        listOf(
                            "今天 ${today.monthValue}/${today.dayOfMonth}" to todayOpt,
                            "明天 ${today.plusDays(1).monthValue}/${today.plusDays(1).dayOfMonth}" to tomorrowOpt,
                        ),
                    )
                } else {
                    VoiceIntent.Alarm(hour, minute, 0, memo, today.plusDays(1), groupName = groupName)
                }
            }
            else -> VoiceIntent.Alarm(hour, minute, 0, memo, groupName = groupName)
        }
    }

    // ---------- 群組 ----------

    private data class GroupPhrase(val name: String, val range: IntRange, val forTimer: Boolean = false)

    private const val NAME = "[^,、「」到在進放加移裡內的]{1,12}?"

    /** 「新增鬧鐘群組叫上班」「建立一個群組名為運動」「新增上班群組」「建立『家事』計時器群組」 */
    private fun findCreateGroup(t: String): GroupPhrase? {
        val verb = "(?:幫我)?(?:新增|建立|創建|新建|開|設立)(?:一個|個)?(?:新的)?"
        Regex("$verb(鬧鐘|計時器?)?群組(?:叫做|叫|名為|名稱是|名稱為|命名為|:|：)「?([^,、「」]{1,20})」?").find(t)?.let { m ->
            val name = cleanName(m.groupValues[2])
            val g2 = m.groups[2]
            if (name.isNotEmpty() && g2 != null) {
                // 只移除到名稱結尾，名稱後面的時間、提醒要留給後續解析
                val end = g2.range.first + g2.value.indexOf(name).coerceAtLeast(0) + name.length - 1
                return GroupPhrase(name, m.range.first..end, m.groupValues[1].startsWith("計時"))
            }
        }
        Regex("$verb「?($NAME)」?的?(鬧鐘|計時器?)?群組").find(t)?.let { m ->
            val name = cleanName(m.groupValues[1])
            if (name.isNotEmpty() && name != "鬧鐘" && name != "計時器") {
                return GroupPhrase(name, m.range, m.groupValues[2].startsWith("計時"))
            }
        }
        return null
    }

    /** 「放到上班群組」「加入運動群組裡」「在家事群組」 */
    private fun findGroupRef(t: String): GroupPhrase? {
        val m = Regex("(?:放到|放在|放進|加到|加入|移到|歸到|分到|到|在|進)「?($NAME)」?(?:這個|那個)?(?:鬧鐘|計時器?)?群組(?:裡面|裡|內|中)?").find(t)
            ?: return null
        val name = cleanName(m.groupValues[1])
        return if (name.isEmpty()) null else GroupPhrase(name, m.range)
    }

    /** 「關閉上班群組」「打開運動群組」：只認現有群組 */
    private fun parseGroupSwitch(t: String, groups: List<Pair<Long, String>>): VoiceIntent? {
        if (groups.isEmpty()) return null
        val m = Regex("(關閉|關掉|關|停用|暫停|打開|開啟|啟用|恢復|開)「?(.{1,12}?)」?(?:這個|那個)?群組").find(t) ?: return null
        val name = cleanName(m.groupValues[2])
        val g = groups.firstOrNull { it.second.trim().equals(name, ignoreCase = true) } ?: return null
        val on = m.groupValues[1] in setOf("打開", "開啟", "啟用", "恢復", "開")
        return VoiceIntent.GroupSwitch(g.first, on)
    }

    /** 群組名稱遇到「並」「然後」或時間就截斷，例如「上班然後七點叫我」→「上班」 */
    private fun cleanName(raw: String): String {
        var n = raw
        listOf("並且", "並", "然後", "再", "裡面", "裡", ",", "、").forEach { sep -> n = n.substringBefore(sep) }
        n = n.replace(Regex("(凌晨|清晨|早上|上午|中午|下午|傍晚|晚上|每天|每週|平日|週末|明天|後天).*$"), "")
        n = n.replace(Regex("$NUM[點:：].*$"), "")
        return n.trim('「', '」', '"', ' ').take(12)
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
