package com.hoonsor.alarm.agent

import android.os.SystemClock
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.agent.AgentLogic.Pick
import com.hoonsor.alarm.data.AlarmEntity
import com.hoonsor.alarm.data.AlarmGroupEntity
import com.hoonsor.alarm.data.TimerEntity
import com.hoonsor.alarm.data.TimerGroupEntity
import com.hoonsor.alarm.data.TimerState
import com.hoonsor.alarm.data.isActive
import com.hoonsor.alarm.data.isSkipping
import com.hoonsor.alarm.domain.Days
import com.hoonsor.alarm.domain.Fmt
import com.hoonsor.alarm.domain.NextTrigger
import com.hoonsor.alarm.scheduler.AlarmScheduler
import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.agent.AgentRequest
import tw.hoonsor.agent.HoonsorAgent
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent API 各動作的實作。寫入一律走 [com.hoonsor.alarm.data.Repository]（寫完它會重新排程），
 * 這裡只負責選對象、組參數與產生要唸出來的 summary。
 */
object AlarmAgent {

    suspend fun execute(req: AgentRequest): JSONObject {
        val p = req.params
        return when (req.action) {
            "alarm_list" -> alarmList(p)
            "alarm_create" -> alarmCreate(p, req.dryRun)
            "alarm_update" -> alarmUpdate(p, req.dryRun)
            "alarm_toggle" -> alarmToggle(p, req.dryRun)
            "alarm_skip_next" -> alarmSkipNext(p, req.dryRun)
            "alarm_delete" -> alarmDelete(p, req.dryRun, req.confirmToken)
            "group_toggle" -> groupToggle(p, req.dryRun)
            "timer_list" -> timerList(p)
            "timer_start" -> timerStart(p, req.dryRun)
            "timer_control" -> timerControl(p, req.dryRun)
            "timer_delete" -> timerDelete(p, req.dryRun, req.confirmToken)
            else -> HoonsorAgent.fail(HoonsorAgent.NOT_FOUND, "倬的鬧鐘沒有「${req.action}」這個動作。")
        }
    }

    // ---------- 讀資料 ----------

    private val db get() = Graph.db
    private val repo get() = Graph.repo

    private suspend fun alarms() = db.alarmDao().getAll().sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))
    private suspend fun alarmGroups() = db.groupDao().getAll().associateBy { it.id }
    private suspend fun timers() = db.timerDao().getAll().sortedBy { it.createdAt }
    private suspend fun timerGroups() = db.timerGroupDao().getAll().associateBy { it.id }

    private fun now() = System.currentTimeMillis()

    private fun remainingMs(t: TimerEntity): Long = NextTrigger.timerRemaining(
        t, System.currentTimeMillis(), SystemClock.elapsedRealtime(), AlarmScheduler.bootCount(Graph.appContext),
    )

    private fun timerLabel(t: TimerEntity, groups: Map<Long, TimerGroupEntity>): String {
        val name = t.label.trim().ifEmpty { t.groupId?.let { groups[it]?.name }.orEmpty() }.ifEmpty { "計時器" }
        val state = when (t.state) {
            TimerState.RUNNING -> "剩 ${AgentLogic.durationText(remainingMs(t))}"
            TimerState.PAUSED -> if (t.remainingMs < t.durationMs) "暫停，剩 ${AgentLogic.durationText(t.remainingMs)}" else "未開始，${AgentLogic.durationText(t.durationMs)}"
            TimerState.RINGING -> "正在響"
            TimerState.DONE -> "已結束"
        }
        return "$name（$state）"
    }

    private fun alarmJson(a: AlarmEntity, groups: Map<Long, AlarmGroupEntity>, nowMs: Long): JSONObject {
        val next = NextTrigger.forAlarm(a, nowMs, groups)
        return JSONObject()
            .put("id", a.id)
            .put("time", Fmt.hm(a.hour, a.minute))
            .put("repeat", Days.summary(a.daysMask))
            .put("days", AgentLogic.daysArray(a.daysMask))
            .put("memo", a.memo)
            .put("enabled", a.enabled)
            .put("active", a.isActive(groups))
            .put("group", a.groupId?.let { groups[it]?.name } ?: JSONObject.NULL)
            .put("skipping_next", a.isSkipping(nowMs))
            .put("next", next?.let { AgentLogic.whenText(it, nowMs) } ?: JSONObject.NULL)
    }

    // ---------- 鬧鐘 ----------

    private suspend fun alarmList(p: JSONObject): JSONObject {
        val groups = alarmGroups()
        val nowMs = now()
        var list = alarms()
        AgentLogic.optText(p, "query")?.let { list = AgentLogic.matchAlarms(list, groups, it) }
        AgentLogic.optText(p, "group")?.let { g -> list = list.filter { a -> a.groupId?.let { groups[it]?.name }?.contains(g, ignoreCase = true) == true } }
        if (p.optBoolean("enabled_only", false)) list = list.filter { it.isActive(groups) }
        val limit = p.optInt("limit", 30).coerceIn(1, 200)

        val data = JSONObject()
            .put("total", list.size)
            .put("alarms", JSONArray(list.take(limit).map { alarmJson(it, groups, nowMs) }))
            .put("groups", JSONArray(groups.values.sortedBy { it.sortOrder }.map { JSONObject().put("id", it.id).put("name", it.name).put("enabled", it.enabled) }))

        val active = list.filter { it.isActive(groups) }
        val next = active.mapNotNull { a -> NextTrigger.forAlarm(a, nowMs, groups)?.let { it to a } }.minByOrNull { it.first }
        val summary = when {
            list.isEmpty() -> if (p.length() == 0) "目前沒有任何鬧鐘。" else "沒有符合條件的鬧鐘。"
            next == null -> "共有 ${list.size} 個鬧鐘，目前都不會響。"
            else -> "共有 ${list.size} 個鬧鐘，開著 ${active.size} 個；下一個是${AgentLogic.whenText(next.first, nowMs)}" +
                next.second.memo.trim().takeIf { it.isNotEmpty() }?.let { " 的$it" }.orEmpty() + "。"
        }
        return HoonsorAgent.ok(summary, data)
    }

    private suspend fun alarmCreate(p: JSONObject, dryRun: Boolean): JSONObject {
        if (!p.has("hour")) return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "要設幾點的鬧鐘？")
        val hour = p.getInt("hour")
        val minute = p.optInt("minute", 0)
        if (hour !in 0..23 || minute !in 0..59) return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "時間不正確：$hour 點 $minute 分。")
        val mask = AgentLogic.parseDaysMask(p) ?: 0
        val zone = ZoneId.systemDefault()
        val date = if (mask == 0) AgentLogic.parseDate(AgentLogic.optText(p, "date"), LocalDate.now(zone)) else null
        val notBefore = date?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        if (date != null && date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli() <= now()) {
            return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "${date.monthValue}/${date.dayOfMonth} ${Fmt.hm(hour, minute)} 已經過了，要改成明天嗎？")
        }
        val memo = AgentLogic.optText(p, "memo").orEmpty()
        val groupName = AgentLogic.optText(p, "group")
        val draft = AlarmEntity(hour = hour, minute = minute, daysMask = mask, memo = memo, notBefore = notBefore)
        val nextMs = NextTrigger.nextRegular(draft, now(), zone)
        val whenText = if (mask == 0) nextMs?.let { AgentLogic.whenText(it, now()) } ?: Fmt.hm(hour, minute) else "${Days.summary(mask)} ${Fmt.hm(hour, minute)}"
        val what = whenText + (if (memo.isNotEmpty()) "「$memo」" else "") + "的鬧鐘" + (groupName?.let { "（放在$it 群組）" }.orEmpty())
        if (dryRun) return HoonsorAgent.ok("（試跑）會新增$what")

        val groupId = groupName?.let { repo.findOrCreateGroup(it) }
        val id = repo.addAlarm(draft.copy(groupId = groupId))
        val data = JSONObject().put("id", id).put("next", nextMs?.let { AgentLogic.whenText(it, now()) } ?: JSONObject.NULL)
        return HoonsorAgent.ok("已新增$what。", data, "alarm_delete", JSONObject().put("ids", JSONArray(listOf(id))))
    }

    private suspend fun alarmUpdate(p: JSONObject, dryRun: Boolean): JSONObject {
        val groups = alarmGroups()
        val a = when (val pick = AgentLogic.pickAlarms(p, alarms(), groups, allowMany = false)) {
            is Pick.Fail -> return pick.error
            is Pick.Found -> pick.items.first()
        }
        val hour = if (p.has("hour")) p.getInt("hour") else a.hour
        val minute = if (p.has("minute")) p.getInt("minute") else if (p.has("hour")) 0 else a.minute
        if (hour !in 0..23 || minute !in 0..59) return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "時間不正確：$hour 點 $minute 分。")
        val mask = AgentLogic.parseDaysMask(p) ?: a.daysMask
        val memo = if (p.has("memo") && !p.isNull("memo")) p.optString("memo").trim() else a.memo
        val groupChange = p.has("group") && !p.isNull("group")
        val groupName = if (groupChange) p.optString("group").trim() else null
        val timeChanged = hour != a.hour || minute != a.minute || mask != a.daysMask
        if (!timeChanged && memo == a.memo && !groupChange) {
            return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "要把「${AgentLogic.alarmLabel(a, groups)}」改成什麼？")
        }
        val after = a.copy(hour = hour, minute = minute, daysMask = mask, memo = memo)
        val label = AgentLogic.alarmLabel(after, groups)
        if (dryRun) return HoonsorAgent.ok("（試跑）會把「${AgentLogic.alarmLabel(a, groups)}」改成「$label」")

        repo.updateAlarm(a.id, "語音總控修改") { cur ->
            cur.copy(hour = hour, minute = minute, daysMask = mask, memo = memo, enabled = if (timeChanged) true else cur.enabled)
        }
        if (groupChange) {
            val gid = groupName?.takeIf { it.isNotEmpty() }?.let { repo.findOrCreateGroup(it) }
            repo.moveToGroup(listOf(a.id), gid)
        }
        val undo = JSONObject().put("ids", JSONArray(listOf(a.id))).put("hour", a.hour).put("minute", a.minute)
            .put("days", AgentLogic.daysArray(a.daysMask)).put("memo", a.memo)
        if (groupChange) undo.put("group", a.groupId?.let { groups[it]?.name } ?: "")
        return HoonsorAgent.ok("已把鬧鐘改成 $label。", JSONObject().put("id", a.id), "alarm_update", undo)
    }

    private suspend fun alarmToggle(p: JSONObject, dryRun: Boolean): JSONObject {
        if (!p.has("enabled")) return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "要打開還是關閉？")
        val enabled = p.getBoolean("enabled")
        val groups = alarmGroups()
        val picked = when (val pick = AgentLogic.pickAlarms(p, alarms(), groups, allowMany = true)) {
            is Pick.Fail -> return pick.error
            is Pick.Found -> pick.items
        }
        val changing = picked.filter { it.enabled != enabled }
        val verb = if (enabled) "打開" else "關閉"
        if (changing.isEmpty()) return HoonsorAgent.ok("${AgentLogic.labels(picked, groups)}本來就是${verb}的。")
        if (dryRun) return HoonsorAgent.ok("（試跑）會$verb ${AgentLogic.labels(changing, groups)}")

        repo.setEnabled(changing.map { it.id }, enabled)
        val hiddenByGroup = enabled && changing.any { a -> a.groupId?.let { groups[it]?.enabled } == false }
        val note = if (hiddenByGroup) "，不過它所在的群組是關著的，要一起打開群組才會響" else ""
        return HoonsorAgent.ok(
            "已$verb ${AgentLogic.labels(changing, groups)}$note。",
            JSONObject().put("ids", JSONArray(changing.map { it.id })),
            "alarm_toggle", JSONObject().put("ids", JSONArray(changing.map { it.id })).put("enabled", !enabled),
        )
    }

    private suspend fun alarmSkipNext(p: JSONObject, dryRun: Boolean): JSONObject {
        val skip = p.optBoolean("skip", true)
        val groups = alarmGroups()
        val picked = when (val pick = AgentLogic.pickAlarms(p, alarms(), groups, allowMany = true)) {
            is Pick.Fail -> return pick.error
            is Pick.Found -> pick.items
        }
        val nowMs = now()
        if (!skip) {
            val skipping = picked.filter { it.skipUntil != null }
            if (skipping.isEmpty()) return HoonsorAgent.ok("${AgentLogic.labels(picked, groups)}沒有被略過。")
            if (dryRun) return HoonsorAgent.ok("（試跑）會取消略過 ${AgentLogic.labels(skipping, groups)}")
            repo.unskip(skipping.map { it.id })
            return HoonsorAgent.ok(
                "已取消略過，${AgentLogic.labels(skipping, groups)}下次照常響。",
                JSONObject().put("ids", JSONArray(skipping.map { it.id })),
                "alarm_skip_next", JSONObject().put("ids", JSONArray(skipping.map { it.id })).put("skip", true),
            )
        }
        val eligible = picked.filter { it.daysMask != 0 && it.isActive(groups) && !it.isSkipping(nowMs) }
        if (eligible.isEmpty()) {
            val reason = when {
                picked.all { it.daysMask == 0 } -> "只響一次的鬧鐘不能略過，要不要直接關掉？"
                picked.all { it.isSkipping(nowMs) } -> "已經略過下一次了。"
                else -> "鬧鐘是關著的，本來就不會響。"
            }
            return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "${AgentLogic.labels(picked, groups)}：$reason")
        }
        val targets = eligible.mapNotNull { a -> NextTrigger.nextRegular(a.copy(skipUntil = null), nowMs)?.let { a to it } }
        val detail = targets.joinToString("、") { (a, ms) -> AgentLogic.whenText(ms, nowMs) + a.memo.trim().takeIf { it.isNotEmpty() }?.let { " 的$it" }.orEmpty() }
        if (dryRun) return HoonsorAgent.ok("（試跑）會略過 $detail")

        repo.skipNext(eligible.map { it.id })
        val skippedOnce = picked.size - eligible.size
        val note = if (skippedOnce > 0) "；另外 $skippedOnce 個是只響一次或關著的，沒有變動" else ""
        return HoonsorAgent.ok(
            "已略過 $detail，之後照常響$note。",
            JSONObject().put("ids", JSONArray(eligible.map { it.id })),
            "alarm_skip_next", JSONObject().put("ids", JSONArray(eligible.map { it.id })).put("skip", false),
        )
    }

    private suspend fun alarmDelete(p: JSONObject, dryRun: Boolean, token: String?): JSONObject {
        val groups = alarmGroups()
        val picked = when (val pick = AgentLogic.pickAlarms(p, alarms(), groups, allowMany = true)) {
            is Pick.Fail -> return pick.error
            is Pick.Found -> pick.items
        }
        val ids = picked.map { it.id }
        val key = "alarm_delete:" + ids.sorted().joinToString(",")
        val what = AgentLogic.labels(picked, groups)
        if (dryRun) return HoonsorAgent.ok("（試跑）會刪除 $what")
        if (!ConfirmTokens.consume(token, key)) {
            return HoonsorAgent.needsConfirmation("要刪除 $what，刪了就不能復原", ConfirmTokens.issue(key))
        }
        val removed = repo.deleteAlarms(ids)
        repo.purgeUnusedImages(removed.map { it.imagePath })
        repo.purgeUnusedSounds(removed.map { it.soundUri })
        repo.purgeUnusedTts(removed.map { it.ttsCachePath })
        return HoonsorAgent.ok("已刪除 $what。", JSONObject().put("ids", JSONArray(ids)))
    }

    private suspend fun groupToggle(p: JSONObject, dryRun: Boolean): JSONObject {
        val name = AgentLogic.optText(p, "name") ?: return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "要開關哪個群組？")
        if (!p.has("enabled")) return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "要打開還是關閉「$name」群組？")
        val enabled = p.getBoolean("enabled")
        val forTimer = p.optString("kind", "alarm") == "timer"
        val kindText = if (forTimer) "計時器" else "鬧鐘"
        data class G(val id: Long, val name: String, val enabled: Boolean)
        val all = if (forTimer) timerGroups().values.map { G(it.id, it.name, it.enabled) } else alarmGroups().values.map { G(it.id, it.name, it.enabled) }
        val exact = all.filter { it.name.trim().equals(name, ignoreCase = true) }
        val found = exact.ifEmpty { all.filter { it.name.contains(name, ignoreCase = true) } }
        when {
            found.isEmpty() -> return HoonsorAgent.fail(
                HoonsorAgent.NOT_FOUND,
                if (all.isEmpty()) "目前沒有任何${kindText}群組。" else "找不到「$name」${kindText}群組，現有的是：${all.joinToString("、") { it.name }}。",
            )
            found.size > 1 -> return HoonsorAgent.ambiguous("有 ${found.size} 個${kindText}群組符合，要哪一個？", found.map { it.name })
        }
        val g = found.first()
        val verb = if (enabled) "打開" else "關閉"
        if (g.enabled == enabled) return HoonsorAgent.ok("「${g.name}」${kindText}群組本來就是${verb}的。")
        if (dryRun) return HoonsorAgent.ok("（試跑）會$verb「${g.name}」${kindText}群組")
        if (forTimer) repo.setTimerGroupEnabled(g.id, enabled) else repo.setGroupEnabled(g.id, enabled)
        val effect = when {
            forTimer && enabled -> "，被群組暫停的計時器已繼續"
            forTimer -> "，群組內執行中的計時器已暫停"
            enabled -> "，群組內的鬧鐘恢復原本的開關"
            else -> "，群組內的鬧鐘都不會響"
        }
        return HoonsorAgent.ok(
            "已$verb「${g.name}」${kindText}群組$effect。",
            JSONObject().put("id", g.id),
            "group_toggle", JSONObject().put("name", g.name).put("enabled", !enabled).put("kind", if (forTimer) "timer" else "alarm"),
        )
    }

    // ---------- 計時器 ----------

    private suspend fun timerList(p: JSONObject): JSONObject {
        val groups = timerGroups()
        var list = timers()
        AgentLogic.optText(p, "query")?.let { list = AgentLogic.matchTimers(list, groups, it) }
        val nowMs = now()
        val arr = JSONArray(
            list.map { t ->
                val rem = remainingMs(t)
                JSONObject().put("id", t.id).put("label", t.label)
                    .put("state", t.state.name.lowercase())
                    .put("remaining", Fmt.duration(rem)).put("remaining_seconds", (rem + 999) / 1000)
                    .put("duration", Fmt.duration(t.durationMs))
                    .put("group", t.groupId?.let { groups[it]?.name } ?: JSONObject.NULL)
                    .put("ends_at", if (t.state == TimerState.RUNNING) AgentLogic.whenText(nowMs + rem, nowMs) else JSONObject.NULL)
            },
        )
        val running = list.filter { it.state == TimerState.RUNNING }
        val summary = when {
            list.isEmpty() -> "目前沒有任何計時器。"
            running.isEmpty() -> "共有 ${list.size} 個計時器，都沒有在跑。"
            running.size == 1 -> "共有 ${list.size} 個計時器，${timerLabel(running.first(), groups)}。"
            else -> "共有 ${list.size} 個計時器，${running.size} 個在跑，最快的${timerLabel(running.minBy { remainingMs(it) }, groups)}。"
        }
        return HoonsorAgent.ok(summary, JSONObject().put("total", list.size).put("timers", arr))
    }

    private suspend fun timerStart(p: JSONObject, dryRun: Boolean): JSONObject {
        if (!p.has("seconds")) return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "要計時多久？")
        val seconds = p.getLong("seconds")
        if (seconds !in 1..AgentCatalog.TIMER_MAX_SECONDS.toLong()) {
            return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "計時器最長 99 小時 59 分 59 秒。")
        }
        val label = AgentLogic.optText(p, "label").orEmpty()
        val groupName = AgentLogic.optText(p, "group")
        val ms = seconds * 1000
        val what = (if (label.isNotEmpty()) "「$label」" else "") + AgentLogic.durationText(ms) + "的計時"
        if (dryRun) return HoonsorAgent.ok("（試跑）會開始$what")
        val groupId = groupName?.let { repo.findOrCreateTimerGroup(it) }
        val id = repo.startTimer(ms, label, groupId)
        val ends = AgentLogic.whenText(now() + ms, now())
        return HoonsorAgent.ok(
            "已開始$what，$ends 響。",
            JSONObject().put("id", id).put("ends_at", ends),
            "timer_delete", JSONObject().put("ids", JSONArray(listOf(id))),
        )
    }

    private suspend fun timerControl(p: JSONObject, dryRun: Boolean): JSONObject {
        val command = AgentLogic.optText(p, "command")
            ?: return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "要暫停、繼續還是重設計時器？")
        val groups = timerGroups()
        val applicable: (TimerEntity) -> Boolean = when (command) {
            "pause" -> { t -> t.state == TimerState.RUNNING }
            "resume" -> { t -> t.state == TimerState.PAUSED && t.remainingMs > 0 }
            "reset" -> { t -> t.state != TimerState.PAUSED || t.remainingMs < t.durationMs }
            else -> return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "command 只能是 pause、resume、reset。")
        }
        val picked = when (val pick = AgentLogic.pickTimers(p, timers(), groups, applicable) { timerLabel(it, groups) }) {
            is Pick.Fail -> return pick.error
            is Pick.Found -> pick.items
        }
        val verb = mapOf("pause" to "暫停", "resume" to "繼續", "reset" to "重設")[command]!!
        val targets = picked.filter(applicable)
        val names = targets.joinToString("、") { it.label.trim().ifEmpty { "計時器" } }
        if (targets.isEmpty()) {
            val state = when (command) {
                "pause" -> "沒有在跑"
                "resume" -> "沒有暫停中的"
                else -> "本來就是完整時長"
            }
            return HoonsorAgent.ok("${picked.joinToString("、") { timerLabel(it, groups) }}$state，不用$verb。")
        }
        if (dryRun) return HoonsorAgent.ok("（試跑）會$verb $names")
        val ids = targets.map { it.id }
        when (command) {
            "pause" -> repo.pauseTimers(ids)
            "resume" -> repo.resumeTimers(ids)
            else -> repo.resetTimers(ids)
        }
        val after = db.timerDao().getAll().filter { it.id in ids }
        val undo = when (command) {
            "pause" -> "resume"
            "resume" -> "pause"
            else -> null
        }
        return HoonsorAgent.ok(
            "已$verb ${after.joinToString("、") { timerLabel(it, groups) }}。",
            JSONObject().put("ids", JSONArray(ids)),
            undo?.let { "timer_control" }, undo?.let { JSONObject().put("ids", JSONArray(ids)).put("command", it) },
        )
    }

    private suspend fun timerDelete(p: JSONObject, dryRun: Boolean, token: String?): JSONObject {
        val groups = timerGroups()
        val picked = when (val pick = AgentLogic.pickTimers(p, timers(), groups) { timerLabel(it, groups) }) {
            is Pick.Fail -> return pick.error
            is Pick.Found -> pick.items
        }
        val ids = picked.map { it.id }
        val key = "timer_delete:" + ids.sorted().joinToString(",")
        val what = picked.joinToString("、") { timerLabel(it, groups) }
        if (dryRun) return HoonsorAgent.ok("（試跑）會刪除 $what")
        if (!ConfirmTokens.consume(token, key)) {
            return HoonsorAgent.needsConfirmation("要刪除 $what", ConfirmTokens.issue(key))
        }
        repo.deleteTimers(ids)
        return HoonsorAgent.ok("已刪除 $what。", JSONObject().put("ids", JSONArray(ids)))
    }
}

/** destructive 動作的確認碼：綁定「動作＋對象」，兩分鐘內有效、只能用一次 */
object ConfirmTokens {
    private const val TTL_MS = 120_000L
    private val issued = ConcurrentHashMap<String, Pair<String, Long>>()
    private val random = SecureRandom()

    fun issue(key: String, nowMs: Long = System.currentTimeMillis()): String {
        issued.entries.removeIf { it.value.second < nowMs }
        val token = (1..16).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")
        issued[token] = key to nowMs + TTL_MS
        return token
    }

    fun consume(token: String?, key: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (token.isNullOrBlank()) return false
        val (k, expires) = issued.remove(token) ?: return false
        return k == key && expires >= nowMs
    }
}
