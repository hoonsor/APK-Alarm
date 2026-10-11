package com.hoonsor.alarm.data

import android.os.SystemClock
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.scheduler.AlarmScheduler
import com.hoonsor.alarm.tts.TtsEngine
import kotlinx.coroutines.launch
import java.io.File

/** 所有寫入都經過這裡，寫完一律重新排程。 */
class Repository(private val db: AppDatabase, private val scheduler: AlarmScheduler) {
    private val alarms = db.alarmDao()
    private val timers = db.timerDao()
    private val groups = db.groupDao()

    // ---------- 鬧鐘 ----------

    suspend fun addAlarm(alarm: AlarmEntity): Long {
        val id = alarms.insert(alarm)
        scheduler.rescheduleAll("新增鬧鐘")
        if (alarm.speakMemo) prepareTtsAsync(id)
        return id
    }

    suspend fun updateAlarm(id: Long, reason: String = "編輯鬧鐘", change: (AlarmEntity) -> AlarmEntity) {
        val current = alarms.get(id) ?: return
        val next = change(current)
        if (next == current) return
        // 只要改了時間或重複設定，就清掉舊的貪睡與「不早於」限制
        val cleaned = if (next.hour != current.hour || next.minute != current.minute || next.daysMask != current.daysMask) {
            next.copy(snoozeUntil = null, notBefore = if (next.daysMask != 0) null else next.notBefore)
        } else next
        alarms.update(cleaned)
        scheduler.rescheduleAll(reason)
        val speechChanged = cleaned.memo != current.memo || cleaned.hour != current.hour ||
            cleaned.minute != current.minute || !current.speakMemo
        if (cleaned.speakMemo && speechChanged) prepareTtsAsync(id)
    }

    /** 在背景為鬧鐘產生雲端語音（未啟用雲端語音時什麼都不做） */
    fun prepareTtsAsync(id: Long) {
        Graph.appScope.launch { TtsEngine.prepare(id) }
    }

    /** 刪除不再被任何鬧鐘使用的語音快取檔 */
    suspend fun purgeUnusedTts(candidates: List<String?>) {
        val inUse = alarms.getAll().mapNotNull { it.ttsCachePath }.toSet()
        candidates.filterNotNull().filter { it !in inUse }.forEach { runCatching { File(it).delete() } }
    }

    suspend fun copyAlarms(ids: List<Long>): List<Long> {
        val newIds = alarms.getMany(ids).map { src ->
            alarms.insert(
                src.copy(
                    id = 0, isCopy = true, snoozeUntil = null, createdAt = System.currentTimeMillis(),
                    ttsCachePath = null, ttsCacheKey = null,
                ),
            ).also { if (src.speakMemo) prepareTtsAsync(it) }
        }
        scheduler.rescheduleAll("複製鬧鐘")
        return newIds
    }

    /** 刪除並回傳被刪的資料，供「復原」使用。圖片檔在復原期限過後才清掉。 */
    suspend fun deleteAlarms(ids: List<Long>): List<AlarmEntity> {
        val removed = alarms.getMany(ids)
        alarms.deleteIds(ids)
        scheduler.rescheduleAll("刪除鬧鐘")
        return removed
    }

    suspend fun restoreAlarms(list: List<AlarmEntity>) {
        alarms.upsertAll(list)
        scheduler.rescheduleAll("復原刪除")
    }

    /** 復原期限過後，刪除已無任何鬧鐘使用的圖片 */
    suspend fun purgeUnusedImages(candidates: List<String?>) {
        val inUse = alarms.getAll().mapNotNull { it.imagePath }.toSet() + setOfNotNull(Graph.settings.defaultImagePath)
        candidates.filterNotNull().filter { it !in inUse }.forEach { runCatching { File(it).delete() } }
    }

    /** 刪除已無任何鬧鐘或預設設定使用的「App 內部」音檔（系統鈴聲 URI 不受影響） */
    suspend fun purgeUnusedSounds(candidates: List<String?>) {
        val s = Graph.settings
        val inUse = alarms.getAll().mapNotNull { it.soundUri }.toSet() + setOfNotNull(s.alarmSoundUri, s.timerSoundUri)
        val soundDir = File(Graph.appContext.filesDir, "sounds").absolutePath
        candidates.filterNotNull()
            .filter { it !in inUse && it.startsWith(soundDir) }
            .forEach { runCatching { File(it).delete() } }
    }

    // ---------- 群組 ----------

    suspend fun addGroup(name: String): Long {
        val order = groups.maxSortOrder() + 1
        return groups.insert(AlarmGroupEntity(name = name.trim().ifBlank { "新群組" }, sortOrder = order))
    }

    suspend fun renameGroup(id: Long, name: String) {
        val g = groups.get(id) ?: return
        groups.update(g.copy(name = name.trim().ifBlank { g.name }))
    }

    suspend fun setGroupEnabled(id: Long, enabled: Boolean) {
        val g = groups.get(id) ?: return
        if (g.enabled == enabled) return
        groups.update(g.copy(enabled = enabled))
        scheduler.rescheduleAll(if (enabled) "開啟群組" else "關閉群組")
    }

    suspend fun setGroupExpanded(id: Long, expanded: Boolean) {
        val g = groups.get(id) ?: return
        groups.update(g.copy(expanded = expanded))
    }

    /** 刪除群組：群組內的鬧鐘移到「未分組」，不會被刪除 */
    suspend fun deleteGroup(id: Long) {
        alarms.ungroup(id)
        groups.delete(id)
        scheduler.rescheduleAll("刪除群組")
    }

    suspend fun moveToGroup(ids: List<Long>, groupId: Long?) {
        if (ids.isEmpty()) return
        alarms.setGroup(ids, groupId)
        scheduler.rescheduleAll("移動群組")
    }

    suspend fun setEnabled(ids: List<Long>, enabled: Boolean) {
        alarms.getMany(ids).forEach { alarms.update(it.copy(enabled = enabled, snoozeUntil = null)) }
        scheduler.rescheduleAll(if (enabled) "啟用鬧鐘" else "停用鬧鐘")
    }

    // ---------- 計時器 ----------

    suspend fun startTimer(durationMs: Long, label: String): Long {
        val t = runningCopy(TimerEntity(durationMs = durationMs, label = label), durationMs)
        val id = timers.insert(t)
        scheduler.rescheduleAll("開始計時")
        return id
    }

    suspend fun pauseTimer(id: Long) {
        val t = timers.get(id) ?: return
        if (t.state != TimerState.RUNNING) return
        val remaining = com.hoonsor.alarm.domain.NextTrigger.timerRemaining(
            t, System.currentTimeMillis(), SystemClock.elapsedRealtime(), AlarmScheduler.bootCount(Graph.appContext),
        )
        timers.update(t.copy(state = TimerState.PAUSED, remainingMs = remaining, endAtWall = null, endAtElapsed = null))
        scheduler.rescheduleAll("暫停計時")
    }

    suspend fun resumeTimer(id: Long) {
        val t = timers.get(id) ?: return
        if (t.state != TimerState.PAUSED || t.remainingMs <= 0) return
        timers.update(runningCopy(t, t.remainingMs))
        scheduler.rescheduleAll("繼續計時")
    }

    suspend fun resetTimer(id: Long) {
        val t = timers.get(id) ?: return
        timers.update(t.copy(state = TimerState.PAUSED, remainingMs = t.durationMs, endAtWall = null, endAtElapsed = null))
        scheduler.rescheduleAll("重設計時")
    }

    suspend fun deleteTimer(id: Long) {
        val t = timers.get(id) ?: return
        timers.delete(t)
        scheduler.rescheduleAll("刪除計時")
    }

    /** 計時器貪睡：從現在起再倒數 n 毫秒 */
    suspend fun snoozeTimer(id: Long, ms: Long) {
        val t = timers.get(id) ?: return
        timers.update(runningCopy(t, ms))
        scheduler.rescheduleAll("計時貪睡")
    }

    private fun runningCopy(t: TimerEntity, remaining: Long): TimerEntity {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        return t.copy(
            state = TimerState.RUNNING,
            remainingMs = remaining,
            endAtWall = nowWall + remaining,
            endAtElapsed = nowElapsed + remaining,
            bootCount = AlarmScheduler.bootCount(Graph.appContext),
        )
    }
}
