package com.hoonsor.alarm.agent

import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.agent.HoonsorAgent

/**
 * 鬧鐘 APP 開放給語音總控的動作清單（describe 的內容）。
 * 純資料、不碰 Android，JVM 測試會比對它和 docs/agent-manifest.json 是否完全一致。
 * 動作名稱與參數是公開契約，改版規則見 hoonsor/APK-VoiceHub docs/AGENT_PROTOCOL.md 第 5 節。
 */
object AgentCatalog {
    const val APP_NAME = "倬的鬧鐘"
    const val DESCRIPTION = "倬的鬧鐘與計時器：查詢、新增、修改、開關、略過下次、刪除鬧鐘，開關群組，開始與控制計時器。" +
        "用語音新增的鬧鐘與計時器預設都是一次性（響完自動刪除），使用者說「固定」「重複」「常駐」「保留」時才建立會留下來的。"

    val TIMER_MAX_SECONDS = 99 * 3600 + 59 * 60 + 59

    fun describe(packageName: String, version: String): JSONObject =
        HoonsorAgent.describe(APP_NAME, packageName, version, DESCRIPTION, actions())

    fun actions(): List<JSONObject> = listOf(
        HoonsorAgent.action(
            "alarm_list", "查詢鬧鐘",
            "列出鬧鐘（id、時間、重複、名稱、開關、群組、下次響鈴、是否略過下次、是否一次性）與鬧鐘群組；只查詢不修改。要操作某個鬧鐘前，可先用這個取得 id",
            "read",
            obj(
                "query" to str("只列出名稱、群組或時間符合的鬧鐘，例如「上班」「07:00」"),
                "group" to str("只列出這個群組的鬧鐘"),
                "enabled_only" to bool("true＝只列出開著（會響）的鬧鐘"),
                "limit" to int("最多列幾個，預設 30", 1, 200),
            ),
            listOf("我有哪些鬧鐘", "明天早上幾點會響", "上班群組裡有哪些鬧鐘", "現在開著的鬧鐘有哪些"),
        ),
        HoonsorAgent.action(
            "alarm_create", "新增鬧鐘",
            "在倬的鬧鐘新增一個鬧鐘。沒給 repeat 也沒給 days 時只響一次；只響一次的鬧鐘可用 date 指定日期，而且預設是一次性（響完自動刪除）。group 不存在時會自動建立",
            "reversible",
            obj(
                "hour" to int("時，0–23（24 小時制）", 0, 23),
                "minute" to int("分，0–59，預設 0", 0, 59),
                "repeat" to enumStr("重複：once＝只響一次、weekdays＝平日、weekend＝週末、everyday＝每天", "once", "weekdays", "weekend", "everyday"),
                "days" to daysParam(),
                "date" to str("只響一次時的日期：today、tomorrow、day_after_tomorrow，或 YYYY-MM-DD；不給就是下一個到達的時間"),
                "memo" to str("鬧鐘名稱或提醒內容，例如「上班」「吃藥」"),
                "group" to str("放進這個鬧鐘群組（名稱）"),
                "auto_delete" to bool(AUTO_DELETE_ALARM),
                required = listOf("hour"),
            ),
            listOf(
                "明天早上七點叫我", "平日六點半的鬧鐘，名稱叫上班", "設一個每天晚上十點提醒我吃藥的鬧鐘", "週六週日九點叫我，放到假日群組",
                "幫我設一個固定的明天八點鬧鐘，響完不要刪",
            ),
        ),
        HoonsorAgent.action(
            "alarm_update", "修改鬧鐘",
            "修改一個既有鬧鐘的時間、重複、名稱或群組（只能一次改一個）；改了時間或重複會自動打開它。只想開關請用 alarm_toggle",
            "reversible",
            target(
                "要改的鬧鐘（只能符合一個）",
                "hour" to int("新的時，0–23", 0, 23),
                "minute" to int("新的分，0–59", 0, 59),
                "repeat" to enumStr("新的重複設定", "once", "weekdays", "weekend", "everyday"),
                "days" to daysParam(),
                "memo" to str("新的名稱或提醒內容"),
                "group" to str("移到這個群組（名稱，不存在就建立）；空字串＝移出群組"),
                "auto_delete" to bool("true＝改成一次性（響完自動刪除，只對只響一次的鬧鐘有效）、false＝響完保留"),
                withAll = false,
            ),
            listOf("把上班的鬧鐘改成七點十五", "七點那個鬧鐘改成只有週一到週三", "把吃藥鬧鐘的名稱改成吃維他命", "明天八點的鬧鐘響完要留著"),
        ),
        HoonsorAgent.action(
            "alarm_toggle", "開關鬧鐘",
            "打開或關閉鬧鐘（不刪除）。關閉後就不會響，之後可以再打開；要暫時跳過一次請用 alarm_skip_next",
            "reversible",
            target("要開關的鬧鐘", "enabled" to bool("true＝打開、false＝關閉"), required = listOf("enabled")),
            listOf("把七點的鬧鐘關掉", "打開上班鬧鐘", "所有鬧鐘都關掉", "晨跑鬧鐘先停用"),
        ),
        HoonsorAgent.action(
            "alarm_skip_next", "略過下次",
            "只略過重複鬧鐘的下一次響鈴（之後照常響），或取消略過；只響一次的鬧鐘不能略過，請改用 alarm_toggle 關閉",
            "reversible",
            target("要略過的鬧鐘", "skip" to bool("true＝略過下一次（預設）、false＝取消略過")),
            listOf("明天的上班鬧鐘略過", "下次的晨跑不要叫我", "取消略過上班鬧鐘", "明天放假，平日的鬧鐘都跳過一次"),
        ),
        HoonsorAgent.action(
            "alarm_delete", "刪除鬧鐘",
            "永久刪除鬧鐘（可一次多個），會先確認；只想暫停請用 alarm_toggle",
            "destructive",
            target("要刪除的鬧鐘"),
            listOf("刪掉七點的鬧鐘", "把吃藥的鬧鐘刪除", "刪除所有關著的鬧鐘"),
        ),
        HoonsorAgent.action(
            "group_toggle", "開關群組",
            "打開或關閉整個鬧鐘群組或計時器群組。關閉鬧鐘群組＝群組內鬧鐘都不響（重新打開後恢復各自原本的開關）；關閉計時器群組＝暫停群組內執行中的計時器",
            "reversible",
            obj(
                "name" to str("群組名稱"),
                "enabled" to bool("true＝打開、false＝關閉"),
                "kind" to enumStr("alarm＝鬧鐘群組（預設）、timer＝計時器群組", "alarm", "timer"),
                required = listOf("name", "enabled"),
            ),
            listOf("把上班群組關掉", "打開假日群組的鬧鐘", "暫停廚房那組計時器"),
        ),
        HoonsorAgent.action(
            "timer_list", "查詢計時器",
            "列出計時器（id、名稱、剩餘時間、狀態、群組、幾點響、是否一次性）；只查詢不修改",
            "read",
            obj("query" to str("只列出名稱或群組符合的計時器")),
            listOf("計時器還剩多久", "煮麵的計時器剩幾分鐘", "現在有哪些計時器在跑"),
        ),
        HoonsorAgent.action(
            "timer_start", "開始計時",
            "在倬的鬧鐘新增一個計時器並立刻開始倒數，預設是一次性（響完自動刪除）。要暫停、繼續或重設既有的計時器請用 timer_control",
            "reversible",
            obj(
                "seconds" to int("倒數秒數，1–359999（例如 10 分鐘＝600）", 1, TIMER_MAX_SECONDS),
                "label" to str("計時器名稱，例如「煮麵」"),
                "group" to str("放進這個計時器群組（名稱，不存在就建立）"),
                "auto_delete" to bool(AUTO_DELETE_TIMER),
                required = listOf("seconds"),
            ),
            listOf("計時十分鐘", "煮麵計時三分鐘", "一個半小時後提醒我，叫做洗衣服", "設一個常駐的二十五分鐘番茄鐘計時器"),
        ),
        HoonsorAgent.action(
            "timer_control", "控制計時器",
            "暫停、繼續或重設（停下並回到原本時長）既有的計時器。沒指定時，只有一個符合的計時器就直接處理",
            "reversible",
            target(
                "要控制的計時器（比對名稱或群組）",
                "command" to enumStr("pause＝暫停、resume＝繼續、reset＝重設", "pause", "resume", "reset"),
                required = listOf("command"),
            ),
            listOf("暫停計時器", "繼續煮麵的計時", "把計時器重設", "所有計時器都暫停"),
        ),
        HoonsorAgent.action(
            "timer_delete", "刪除計時器",
            "刪除計時器（可一次多個），會先確認；只想停下請用 timer_control",
            "destructive",
            target("要刪除的計時器（比對名稱或群組）"),
            listOf("刪掉煮麵的計時器", "把所有計時器都刪除"),
        ),
    )

    private const val KEEP_WORDS = "使用者明確說「固定」「重複」「常駐」「保留」「響完不要刪」時才給 false"
    private const val AUTO_DELETE_ALARM =
        "一次性：true＝響完（按停止或響太久自動停止）就自動刪除，只對只響一次的鬧鐘有效。沒給時：只響一次的鬧鐘預設 true，重複鬧鐘一律保留。$KEEP_WORDS"
    private const val AUTO_DELETE_TIMER =
        "一次性：true＝響完（按停止或響太久自動停止）就自動刪除。沒給時預設 true。$KEEP_WORDS；false＝響完回到原本時長，留在清單裡"

    // ---------- JSON Schema 小工具 ----------

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    private fun int(desc: String, min: Int, max: Int) =
        JSONObject().put("type", "integer").put("description", desc).put("minimum", min).put("maximum", max)
    private fun enumStr(desc: String, vararg values: String) =
        JSONObject().put("type", "string").put("description", desc).put("enum", JSONArray(values.toList()))

    private fun daysParam() = JSONObject().put("type", "array")
        .put("description", "指定星期，1＝週一 … 7＝週日，例如 [1,3,5]；給了就優先於 repeat，空陣列＝只響一次")
        .put("items", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 7))

    private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject().put("type", "object").put("properties", p)
            .apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }
    }

    /** 指定對象：ids（查詢回傳的 id）、query（名稱、群組或時間）、all（全部符合的） */
    private fun target(
        what: String,
        vararg extra: Pair<String, JSONObject>,
        required: List<String> = emptyList(),
        withAll: Boolean = true,
    ): JSONObject {
        val base = mutableListOf(
            "ids" to JSONObject().put("type", "array").put("description", "$what：查詢動作回傳的 id，與 query 二擇一")
                .put("items", JSONObject().put("type", "integer")),
            "query" to str("$what：比對名稱、群組或時間（如「07:00」「上班」）；符合多個時會請你選"),
        )
        if (withAll) base += "all" to bool("true＝套用到所有符合的對象（沒給 ids、query 時就是全部）")
        return obj(*(base + extra).toTypedArray(), required = required)
    }
}
