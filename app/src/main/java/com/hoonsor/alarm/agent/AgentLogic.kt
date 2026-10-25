package com.hoonsor.alarm.agent

import com.hoonsor.alarm.data.AlarmEntity
import com.hoonsor.alarm.data.AlarmGroupEntity
import com.hoonsor.alarm.data.TimerEntity
import com.hoonsor.alarm.data.TimerGroupEntity
import com.hoonsor.alarm.domain.Days
import com.hoonsor.alarm.domain.Fmt
import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.agent.HoonsorAgent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Agent API 的純計算（參數解析、比對對象、唸出來的文字）；不碰資料庫，JVM 單元測試覆蓋 */
object AgentLogic {

    /** 選取對象的結果：找到的清單，或要直接回給大腦的錯誤 */
    sealed interface Pick<out T> {
        data class Found<T>(val items: List<T>) : Pick<T>
        data class Fail(val error: JSONObject) : Pick<Nothing>
    }

    // ---------- 參數 ----------

    /** days 陣列（1＝週一 … 7＝週日）優先，其次 repeat；都沒給回傳 null（呼叫端決定預設） */
    fun parseDaysMask(params: JSONObject): Int? {
        params.optJSONArray("days")?.let { arr ->
            var mask = 0
            for (i in 0 until arr.length()) {
                val d = arr.optInt(i, -1)
                require(d in 1..7) { "days 只能是 1（週一）到 7（週日）" }
                mask = mask or (1 shl (d - 1))
            }
            return mask
        }
        val r = optText(params, "repeat") ?: return null
        return when (r) {
            "once" -> 0
            "weekdays" -> Days.WEEKDAYS
            "weekend" -> Days.WEEKEND
            "everyday" -> Days.EVERYDAY
            else -> throw IllegalArgumentException("repeat 只能是 once、weekdays、weekend、everyday，收到 $r")
        }
    }

    /** daysMask 轉回 days 陣列（undo 用） */
    fun daysArray(mask: Int): JSONArray = JSONArray((1..7).filter { Days.has(mask, it - 1) })

    fun parseDate(raw: String?, today: LocalDate): LocalDate? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return when (s.lowercase()) {
            "today", "今天" -> today
            "tomorrow", "明天" -> today.plusDays(1)
            "day_after_tomorrow", "後天" -> today.plusDays(2)
            else -> runCatching { LocalDate.parse(s) }.getOrElse {
                throw IllegalArgumentException("date 看不懂：$s，請用 YYYY-MM-DD 或 tomorrow")
            }
        }
    }

    private val TIME = Regex("^(\\d{1,2})\\s*[:：點时時]\\s*(\\d{1,2})?\\s*分?$")
    private val COMPACT = Regex("^(\\d{3,4})$")

    /** 「07:00」「7:5」「7點」「0730」→ (時, 分)；不是時間回傳 null */
    fun parseTimeQuery(q: String): Pair<Int, Int>? {
        val s = q.trim()
        TIME.matchEntire(s)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            return if (h in 0..23 && min in 0..59) h to min else null
        }
        COMPACT.matchEntire(s)?.let {
            val n = it.value.toInt()
            val h = n / 100
            val min = n % 100
            return if (h in 0..23 && min in 0..59) h to min else null
        }
        return null
    }

    /** 字串參數；沒給、null 或空白都回傳 null */
    fun optText(params: JSONObject, key: String): String? =
        if (!params.has(key) || params.isNull(key)) null else params.optString(key).trim().takeIf { it.isNotEmpty() }

    fun ids(params: JSONObject): List<Long>? {
        val arr = params.optJSONArray("ids") ?: return null
        return (0 until arr.length()).map { arr.optLong(it) }.takeIf { it.isNotEmpty() }
    }

    // ---------- 鬧鐘 ----------

    fun matchAlarms(alarms: List<AlarmEntity>, groups: Map<Long, AlarmGroupEntity>, query: String): List<AlarmEntity> {
        parseTimeQuery(query)?.let { (h, m) ->
            return alarms.filter { it.hour == h && it.minute == m }
        }
        val q = query.trim()
        val byMemo = alarms.filter { it.memo.trim().equals(q, ignoreCase = true) }
        if (byMemo.isNotEmpty()) return byMemo
        return alarms.filter { a ->
            a.memo.contains(q, ignoreCase = true) ||
                a.groupId?.let { groups[it]?.name }?.contains(q, ignoreCase = true) == true
        }
    }

    /**
     * 依 ids／query／all 選鬧鐘。allowMany = false 時一定只能選到一個。
     * 沒給任何條件：all = true 時選全部，否則請大腦問清楚。
     */
    fun pickAlarms(
        params: JSONObject,
        alarms: List<AlarmEntity>,
        groups: Map<Long, AlarmGroupEntity>,
        allowMany: Boolean,
    ): Pick<AlarmEntity> {
        val all = allowMany && params.optBoolean("all", false)
        val ids = ids(params)
        val q = optText(params, "query")
        val found = when {
            ids != null -> {
                val list = alarms.filter { it.id in ids }
                if (list.size < ids.toSet().size) {
                    val missing = ids.filter { id -> list.none { it.id == id } }
                    return Pick.Fail(HoonsorAgent.fail(HoonsorAgent.NOT_FOUND, "找不到 id ${missing.joinToString("、")} 的鬧鐘，要不要先列出全部？"))
                }
                list
            }
            q != null -> matchAlarms(alarms, groups, q)
            all -> alarms
            else -> return Pick.Fail(HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "要指定哪一個鬧鐘？可以說時間或名稱。"))
        }
        if (found.isEmpty()) {
            return Pick.Fail(
                HoonsorAgent.fail(
                    HoonsorAgent.NOT_FOUND,
                    if (alarms.isEmpty()) "目前一個鬧鐘都沒有。" else "找不到${q?.let { "「$it」" } ?: ""}的鬧鐘，要不要先列出全部？",
                ),
            )
        }
        if (found.size > 1 && (!allowMany || (!all && ids == null))) {
            return Pick.Fail(
                HoonsorAgent.ambiguous(
                    if (allowMany) "有 ${found.size} 個鬧鐘符合，要哪一個？還是全部？" else "有 ${found.size} 個鬧鐘符合，要改哪一個？",
                    found.map { alarmLabel(it, groups) + "（id ${it.id}）" },
                ),
            )
        }
        return Pick.Found(found)
    }

    /** 「07:00 上班（平日）」 */
    fun alarmLabel(a: AlarmEntity, groups: Map<Long, AlarmGroupEntity>): String {
        val name = a.memo.trim().ifEmpty { a.groupId?.let { groups[it]?.name }.orEmpty() }
        return Fmt.hm(a.hour, a.minute) + (if (name.isNotEmpty()) " $name" else "") + "（${Days.summary(a.daysMask)}）"
    }

    fun labels(list: List<AlarmEntity>, groups: Map<Long, AlarmGroupEntity>, max: Int = 3): String {
        val shown = list.take(max).joinToString("、") { alarmLabel(it, groups) }
        return if (list.size > max) "$shown 等 ${list.size} 個" else shown
    }

    // ---------- 計時器 ----------

    fun matchTimers(timers: List<TimerEntity>, groups: Map<Long, TimerGroupEntity>, query: String): List<TimerEntity> {
        val q = query.trim()
        val exact = timers.filter { it.label.trim().equals(q, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact
        return timers.filter { t ->
            t.label.contains(q, ignoreCase = true) ||
                t.groupId?.let { groups[it]?.name }?.contains(q, ignoreCase = true) == true
        }
    }

    /**
     * 依 ids／query／all 選計時器。沒給條件時：all＝全部；否則 applicable 裡只有一個就選它。
     */
    fun pickTimers(
        params: JSONObject,
        timers: List<TimerEntity>,
        groups: Map<Long, TimerGroupEntity>,
        applicable: (TimerEntity) -> Boolean = { true },
        label: (TimerEntity) -> String,
    ): Pick<TimerEntity> {
        val all = params.optBoolean("all", false)
        val ids = ids(params)
        val q = optText(params, "query")
        val found = when {
            ids != null -> {
                val list = timers.filter { it.id in ids }
                if (list.size < ids.toSet().size) {
                    return Pick.Fail(HoonsorAgent.fail(HoonsorAgent.NOT_FOUND, "找不到指定的計時器，要不要先列出全部？"))
                }
                list
            }
            q != null -> matchTimers(timers, groups, q)
            all -> timers
            else -> timers.filter(applicable).ifEmpty { timers }
        }
        if (found.isEmpty()) {
            return Pick.Fail(
                HoonsorAgent.fail(
                    HoonsorAgent.NOT_FOUND,
                    if (timers.isEmpty()) "目前沒有任何計時器。" else "找不到${q?.let { "「$it」" } ?: ""}的計時器，要不要先列出全部？",
                ),
            )
        }
        if (found.size > 1 && !all && ids == null) {
            return Pick.Fail(HoonsorAgent.ambiguous("有 ${found.size} 個計時器符合，要哪一個？還是全部？", found.map { label(it) + "（id ${it.id}）" }))
        }
        return Pick.Found(found)
    }

    // ---------- 文字 ----------

    /** 「今天 07:00」「明天 07:00」「10/24（五）07:00」 */
    fun whenText(ms: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
        val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).toLocalDate()
        val day = when (t.toLocalDate()) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            today.plusDays(2) -> "後天"
            else -> "%d/%d（%s）".format(t.monthValue, t.dayOfMonth, Days.LABELS[t.dayOfWeek.value - 1])
        }
        return "$day ${Fmt.hm(t.hour, t.minute)}"
    }

    /** 「10 分鐘」「1 小時 30 分」「45 秒」 */
    fun durationText(ms: Long): String {
        val total = (ms + 999) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return buildString {
            if (h > 0) append("$h 小時")
            if (m > 0) {
                if (isNotEmpty()) append(" ")
                append(if (h > 0) "$m 分" else "$m 分鐘")
            }
            if (s > 0 && h == 0L) {
                if (isNotEmpty()) append(" ")
                append("$s 秒")
            }
            if (isEmpty()) append("0 秒")
        }
    }
}
