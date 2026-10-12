package com.hoonsor.alarm.ui.alarms

import com.hoonsor.alarm.data.isSkipping
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.data.AlarmEntity
import com.hoonsor.alarm.data.AlarmGroupEntity
import com.hoonsor.alarm.image.ImageStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

class AlarmsViewModel : ViewModel() {
    private val repo = Graph.repo

    val alarms: StateFlow<List<AlarmEntity>> =
        Graph.db.alarmDao().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val groups: StateFlow<List<AlarmGroupEntity>> =
        Graph.db.groupDao().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 底部編輯面板目前開啟的鬧鐘 */
    var editingId by mutableStateOf<Long?>(null)
        private set

    /** 選取模式 */
    var selecting by mutableStateOf(false)
        private set
    var selection by mutableStateOf<Set<Long>>(emptySet())
        private set

    fun open(id: Long) { editingId = id }
    fun close() { editingId = null }

    fun toggleEnabled(a: AlarmEntity) = launch {
        repo.updateAlarm(a.id, if (a.enabled) "停用鬧鐘" else "啟用鬧鐘") { it.copy(enabled = !it.enabled, snoozeUntil = null) }
    }

    fun setDays(id: Long, mask: Int) = launch { repo.updateAlarm(id) { it.copy(daysMask = mask, enabled = true) } }

    fun setTime(id: Long, hour: Int, minute: Int) = launch {
        repo.updateAlarm(id) { it.copy(hour = hour, minute = minute, enabled = true, isCopy = false) }
    }

    fun setMemo(id: Long, memo: String) = launch { repo.updateAlarm(id, "編輯提醒") { it.copy(memo = memo) } }

    fun setImage(id: Long, path: String?) = launch {
        val old = alarms.value.firstOrNull { it.id == id }?.imagePath
        repo.updateAlarm(id, "更換圖片") { it.copy(imagePath = path) }
        if (old != null && old != path) repo.purgeUnusedImages(listOf(old))
    }

    fun setSound(id: Long, value: String?) = launch {
        val old = alarms.value.firstOrNull { it.id == id }?.soundUri
        repo.updateAlarm(id, "更換鈴聲") { it.copy(soundUri = value) }
        if (old != null && old != value) repo.purgeUnusedSounds(listOf(old))
    }

    fun setSpeak(id: Long, speak: Boolean) = launch { repo.updateAlarm(id, "響鈴方式") { it.copy(speakMemo = speak, silent = false) } }
    fun setSilent(id: Long) = launch { repo.updateAlarm(id, "響鈴方式") { it.copy(silent = true, speakMemo = false) } }
    fun setVolume(id: Long, volume: Int?) = launch { repo.updateAlarm(id, "音量") { it.copy(volume = volume?.coerceIn(10, 100)) } }
    fun setSpeakOnce(id: Long, once: Boolean) = launch { repo.updateAlarm(id, "單次語音") { it.copy(speakOnceThenRing = once) } }
    fun setVibrate(id: Long, vibrate: Boolean) = launch { repo.updateAlarm(id, "震動") { it.copy(vibrate = vibrate) } }

    fun add() = launch {
        val next = LocalTime.now().plusHours(1).withMinute(0)
        val id = repo.addAlarm(AlarmEntity(hour = next.hour, minute = 0))
        editingId = id
    }

    fun copyOne(id: Long) = launch {
        repo.copyAlarms(listOf(id)).firstOrNull()?.let { editingId = it }
    }

    suspend fun deleteOne(id: Long): List<AlarmEntity> {
        editingId = null
        return repo.deleteAlarms(listOf(id))
    }

    // ---------- 群組 ----------

    fun toggleGroupEnabled(g: AlarmGroupEntity) = launch { repo.setGroupEnabled(g.id, !g.enabled) }
    fun toggleGroupExpanded(g: AlarmGroupEntity) = launch { repo.setGroupExpanded(g.id, !g.expanded) }
    fun renameGroup(id: Long, name: String) = launch { repo.renameGroup(id, name) }
    fun deleteGroup(id: Long) = launch { repo.deleteGroup(id) }
    fun setGroupColor(id: Long, color: Int) = launch { repo.setGroupColor(id, color) }

    /** 新增群組，並可同時把指定的鬧鐘放進去 */
    fun addGroup(name: String, assign: List<Long> = emptyList()) = launch {
        val id = repo.addGroup(name)
        if (assign.isNotEmpty()) repo.moveToGroup(assign, id)
    }

    fun setAlarmGroup(alarmId: Long, groupId: Long?) = launch { repo.moveToGroup(listOf(alarmId), groupId) }

    // ---------- 選取模式 ----------

    fun startSelect(id: Long) {
        editingId = null
        selecting = true
        selection = setOf(id)
    }

    fun toggleSelect(id: Long) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun toggleSelectAll() {
        val all = alarms.value.map { it.id }.toSet()
        selection = if (selection.size == all.size) emptySet() else all
    }

    fun exitSelect() {
        selecting = false
        selection = emptySet()
    }

    /** 選取的鬧鐘是否全部都已啟用（決定批次按鈕顯示「停用」或「啟用」） */
    fun selectionAllEnabled(): Boolean {
        val picked = alarms.value.filter { it.id in selection }
        return picked.isNotEmpty() && picked.all { it.enabled }
    }

    /** 選取的鬧鐘是否全部都已「略過下次」 */
    fun selectionAllSkipping(): Boolean {
        val now = System.currentTimeMillis()
        val picked = alarms.value.filter { it.id in selection }
        return picked.isNotEmpty() && picked.all { it.isSkipping(now) }
    }

    /** 批次：全部已略過 → 取消略過；否則全部略過下次 */
    fun bulkSkip() {
        val ids = selection.toList()
        if (ids.isEmpty()) return
        val undo = selectionAllSkipping()
        exitSelect()
        launch { if (undo) repo.unskip(ids) else repo.skipNext(ids) }
    }

    fun skipNext(id: Long) = launch { repo.skipNext(listOf(id)) }
    fun unskip(id: Long) = launch { repo.unskip(listOf(id)) }

    fun bulkToggle() = launch {
        if (selection.isEmpty()) return@launch
        repo.setEnabled(selection.toList(), !selectionAllEnabled())
    }

    fun bulkCopy() = launch {
        if (selection.isEmpty()) return@launch
        repo.copyAlarms(selection.toList())
        exitSelect()
    }

    fun moveSelection(groupId: Long?) {
        val ids = selection.toList()
        exitSelect()
        launch { repo.moveToGroup(ids, groupId) }
    }

    fun moveSelectionToNewGroup(name: String) {
        val ids = selection.toList()
        exitSelect()
        addGroup(name, ids)
    }

    suspend fun bulkDelete(): List<AlarmEntity> {
        val ids = selection.toList()
        exitSelect()
        return if (ids.isEmpty()) emptyList() else repo.deleteAlarms(ids)
    }

    fun restore(list: List<AlarmEntity>) = launch { repo.restoreAlarms(list) }

    /** 復原期限過後，清掉沒有其他鬧鐘使用的圖片與音檔 */
    fun purgeFiles(list: List<AlarmEntity>) = launch {
        repo.purgeUnusedImages(list.map { it.imagePath })
        repo.purgeUnusedSounds(list.map { it.soundUri })
        repo.purgeUnusedTts(list.map { it.ttsCachePath })
    }

    fun importImage(id: Long, uri: android.net.Uri) = launch {
        val path = ImageStore.import(Graph.appContext, uri) ?: return@launch
        setImage(id, path)
    }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }
}
