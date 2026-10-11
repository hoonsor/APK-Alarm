package com.hoonsor.alarm.voice

import android.util.Log
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.data.AlarmEntity
import com.hoonsor.alarm.data.AlarmGroupEntity
import com.hoonsor.alarm.data.KeyVault
import com.hoonsor.alarm.data.TimerGroupEntity
import com.hoonsor.alarm.domain.Days
import com.hoonsor.alarm.domain.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

class AiException(message: String) : Exception(message)

/**
 * 用 OpenAI 相容的 Chat Completions API（預設 MiniMax M3）解析語音指令。
 * AI 只負責「提出」要做的動作；實際寫入一律經過確認畫面與 Repository，
 * 排程仍由本地 AlarmScheduler 負責，API 斷線不會影響已設定的鬧鐘。
 */
object AiParser {
    private const val TAG = "AiParser"

    fun isConfigured(): Boolean {
        val s = Graph.settings
        return s.aiEnabled && !s.aiKeyCipher.isNullOrBlank()
    }

    suspend fun parse(
        transcript: String,
        alarms: List<AlarmEntity>,
        groups: List<AlarmGroupEntity>,
        timerGroups: List<TimerGroupEntity> = emptyList(),
        now: LocalDateTime = LocalDateTime.now(ZoneId.systemDefault()),
    ): VoiceIntent = withContext(Dispatchers.IO) {
        val s = Graph.settings
        val key = KeyVault.decrypt(s.aiKeyCipher) ?: throw AiException("尚未設定 API key")
        val content = chat(s.aiBaseUrl, s.aiModel, key, systemPrompt(alarms, groups, now, timerGroups), transcript)
        parseResponse(content, alarms.map { it.id }.toSet(), groups.map { it.id }.toSet())
    }

    // ---------- HTTP ----------

    private fun chat(baseUrl: String, model: String, key: String, system: String, user: String): String {
        val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        // MiniMax：把思考內容與答案分開，content 只留最終答案
        if (baseUrl.contains("minimax", ignoreCase = true)) body.put("reasoning_split", true)

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code: $text")
                throw AiException(
                    when (code) {
                        401, 403 -> "API key 無效或沒有權限（HTTP $code）"
                        404 -> "網址或模型名稱錯誤（HTTP 404）"
                        429 -> "呼叫太頻繁或額度用完（HTTP 429）"
                        else -> "AI 服務錯誤（HTTP $code）" + errorMessage(text)?.let { "：$it" }.orEmpty()
                    },
                )
            }
            val json = JSONObject(text)
            // MiniMax 原生錯誤格式：HTTP 200 但 base_resp.status_code != 0
            json.optJSONObject("base_resp")?.let { br ->
                val sc = br.optInt("status_code", 0)
                if (sc != 0) throw AiException("AI 服務錯誤（${sc}）：" + br.optString("status_msg"))
            }
            val choices = json.optJSONArray("choices") ?: throw AiException("AI 回應格式不正確")
            return choices.getJSONObject(0).getJSONObject("message").optString("content", "")
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(text: String): String? = runCatching {
        val j = JSONObject(text)
        j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: j.optJSONObject("base_resp")?.optString("status_msg")
    }.getOrNull()

    // ---------- 提示詞 ----------

    fun systemPrompt(
        alarms: List<AlarmEntity>,
        groups: List<AlarmGroupEntity>,
        now: LocalDateTime,
        timerGroups: List<TimerGroupEntity> = emptyList(),
    ): String {
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.TRADITIONAL_CHINESE)
        val groupName = groups.associate { it.id to it.name }
        val alarmList = JSONArray().apply {
            alarms.forEach { a ->
                put(
                    JSONObject()
                        .put("id", a.id)
                        .put("time", Fmt.hm(a.hour, a.minute))
                        .put("days", Days.summary(a.daysMask))
                        .put("memo", a.memo)
                        .put("group", a.groupId?.let { groupName[it] } ?: JSONObject.NULL)
                        .put("enabled", a.enabled),
                )
            }
        }
        val groupList = JSONArray().apply {
            groups.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("enabled", it.enabled)) }
        }
        val timerGroupList = JSONArray().apply { timerGroups.forEach { put(JSONObject().put("name", it.name)) } }
        return """
你是鬧鐘 App 的指令解析器。把使用者的語音內容轉成 JSON。只輸出 JSON，不要輸出任何其他文字或 Markdown。

現在時間：${"%04d-%02d-%02d %02d:%02d".format(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute)}（$weekday），時區 ${ZoneId.systemDefault().id}。
現有鬧鐘：$alarmList
現有鬧鐘群組：$groupList
現有計時器群組：$timerGroupList

輸出格式：
{"actions": [動作…], "question": null, "error": null}

動作種類：
1. 新增鬧鐘 {"type":"create_alarm","hour":7,"minute":15,"days":[1,3,5],"date":null,"memo":"晨跑","group_id":null,"group_name":null}
   - days：1=週一 … 7=週日。每天＝[1,2,3,4,5,6,7]，平日＝[1,2,3,4,5]，週末＝[6,7]。
   - 只響一次時 days 為 []；若指定了日期（明天、後天、10/20）填 date（格式 YYYY-MM-DD），否則 date 為 null（代表下一次到這個時間就響）。
   - 放進現有群組：填 group_id。放進不存在的新群組：group_id 為 null、group_name 填新群組名稱（App 會自動建立）。沒指定群組兩者都是 null。
2. 開始計時 {"type":"create_timer","seconds":1500,"memo":"起來走動","group_name":null}
   - 指定計時器群組時 group_name 填群組名稱（不存在會自動建立），否則 null。
3. 修改鬧鐘 {"type":"update_alarm","id":3,"hour":6,"minute":30,"days":[1,3,5],"memo":"晨跑","enabled":true}
   - id 必須來自「現有鬧鐘」；只填要改的欄位，不改的欄位不要出現。
4. 建立群組 {"type":"create_group","name":"上班","for":"alarm"}
   - for："alarm"＝鬧鐘群組（預設），"timer"＝計時器群組。同名群組已存在時不要再建立。
   - 例：「新增鬧鐘群組叫上班，裡面加平日七點」→ create_group（上班）＋ create_alarm（group_name:"上班"）。
5. 移動鬧鐘到群組 {"type":"move_alarms","ids":[3,5],"group_id":2,"group_name":null}
   - ids 必須來自「現有鬧鐘」；移到新群組時 group_id 為 null 並填 group_name；移出群組（未分組）時兩者都是 null。
6. 開關鬧鐘群組 {"type":"set_group","group_id":2,"enabled":false}
   - group_id 必須來自「現有鬧鐘群組」。「關閉／暫停上班群組」→ enabled:false；「打開／恢復」→ enabled:true。

規則：
- memo 只放提醒內容，去掉「叫我」「提醒我」「記得」等字，也不要包含群組名稱；沒有內容就給空字串。
- 一句話要求多個鬧鐘時，actions 放多個動作。
- 時間有兩種以上合理解讀時（例如凌晨說「明天早上」可能指今天天亮後），不要猜，改用 question：
  {"actions":[],"question":{"text":"要問使用者的話","options":[{"label":"選項文字","actions":[動作…]}]},"error":null}
- 要修改的鬧鐘在清單中找不到或有多個符合時，用 question 讓使用者選，或在 error 說明。
- 不支援刪除；使用者要刪除時，error 填「刪除請在 App 內長按鬧鐘操作」。
- 完全聽不懂時，error 填簡短原因。
""".trim()
    }

    // ---------- 解析回應 ----------

    /** 去掉思考內容與 Markdown 圍欄，取出 JSON 物件並轉成 VoiceIntent。 */
    fun parseResponse(raw: String, alarmIds: Set<Long>, groupIds: Set<Long>): VoiceIntent {
        var text = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        if (text.contains("<think>")) text = text.substringAfterLast("</think>", "")
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiException("AI 沒有回傳可解析的結果")
        val json = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrElse { throw AiException("AI 回傳的 JSON 格式錯誤") }

        json.optNullableString("error")?.let { return VoiceIntent.Unknown(it) }

        json.optJSONObject("question")?.let { q ->
            val options = mutableListOf<Pair<String, VoiceIntent>>()
            val arr = q.optJSONArray("options") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val intent = actionsToIntent(o.optJSONArray("actions"), alarmIds, groupIds) ?: continue
                options += o.optString("label", "選項 ${i + 1}") to intent
            }
            if (options.isNotEmpty()) return VoiceIntent.Ambiguous(q.optString("text", "請選擇"), options)
        }

        return actionsToIntent(json.optJSONArray("actions"), alarmIds, groupIds)
            ?: VoiceIntent.Unknown("AI 沒有找到可以執行的動作")
    }

    private fun actionsToIntent(arr: JSONArray?, alarmIds: Set<Long>, groupIds: Set<Long>): VoiceIntent? {
        arr ?: return null
        val list = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { toAction(it, alarmIds, groupIds) } }
        return when (list.size) {
            0 -> null
            1 -> list[0]
            else -> VoiceIntent.Batch(list)
        }
    }

    private fun toAction(o: JSONObject, alarmIds: Set<Long>, groupIds: Set<Long>): VoiceIntent? = when (o.optString("type")) {
        "create_alarm" -> {
            val h = o.optInt("hour", -1)
            val m = o.optInt("minute", 0)
            if (h !in 0..23 || m !in 0..59) null
            else VoiceIntent.Alarm(
                hour = h,
                minute = m,
                daysMask = daysToMask(o.optJSONArray("days")),
                memo = o.optNullableString("memo").orEmpty().take(60),
                notBeforeDate = o.optNullableString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                groupId = o.optLong("group_id", -1).takeIf { it in groupIds },
                groupName = o.optNullableString("group_name")?.trim()?.take(20)
                    .takeIf { o.optLong("group_id", -1) !in groupIds },
            )
        }
        "create_timer" -> {
            val sec = o.optLong("seconds", 0)
            if (sec <= 0 || sec > 99 * 3600) null
            else VoiceIntent.Timer(
                sec * 1000, o.optNullableString("memo").orEmpty().take(60),
                groupName = o.optNullableString("group_name")?.trim()?.take(20),
            )
        }
        "create_group" -> o.optNullableString("name")?.trim()?.take(20)?.let {
            VoiceIntent.CreateGroup(it, forTimer = o.optString("for") == "timer")
        }
        "move_alarms" -> {
            val arr = o.optJSONArray("ids") ?: JSONArray()
            val ids = (0 until arr.length()).map { arr.optLong(it, -1) }.filter { it in alarmIds }.distinct()
            if (ids.isEmpty()) null
            else {
                val gid = o.optLong("group_id", -1).takeIf { it in groupIds }
                VoiceIntent.MoveAlarms(ids, gid, if (gid == null) o.optNullableString("group_name")?.trim()?.take(20) else null)
            }
        }
        "set_group" -> {
            val gid = o.optLong("group_id", -1)
            if (gid !in groupIds || !o.has("enabled")) null else VoiceIntent.GroupSwitch(gid, o.optBoolean("enabled"))
        }
        "update_alarm" -> {
            val id = o.optLong("id", -1)
            if (id !in alarmIds) null
            else VoiceIntent.Update(
                alarmId = id,
                hour = o.optIntOrNull("hour")?.takeIf { it in 0..23 },
                minute = o.optIntOrNull("minute")?.takeIf { it in 0..59 },
                daysMask = if (o.has("days") && !o.isNull("days")) daysToMask(o.optJSONArray("days")) else null,
                memo = o.optNullableString("memo"),
                enabled = if (o.has("enabled") && !o.isNull("enabled")) o.optBoolean("enabled") else null,
            )
        }
        else -> null
    }

    private fun daysToMask(arr: JSONArray?): Int {
        arr ?: return 0
        var mask = 0
        for (i in 0 until arr.length()) {
            val d = arr.optInt(i, 0)
            if (d in 1..7) mask = mask or (1 shl (d - 1))
        }
        return mask
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (!has(name) || isNull(name)) null else optInt(name, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
}
