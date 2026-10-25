package com.hoonsor.alarm.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 鬧鐘。
 * daysMask：bit0 = 週一 … bit6 = 週日；0 代表只響一次。
 * notBefore：只響一次的鬧鐘若指定「明天」「後天」，下次響鈴不得早於此時間（epoch ms）。
 * snoozeUntil：貪睡中的下一次響鈴時間（epoch ms），即使鬧鐘已停用仍有效。
 * groupId：所屬群組；null 代表未分組。
 * soundUri：個別指定的鈴聲；null 代表跟隨「鬧鐘預設鈴聲」。
 * volume：響鈴音量 10–100（%）；null 代表使用手機目前的鬧鐘音量。
 * vibrate：響鈴時是否震動。
 * speakMemo：響鈴時用語音念出提醒，取代鈴聲。
 * speakOnceThenRing：語音只念一次，之後改播鈴聲（避免一直重複念）。
 * silent：純震動鬧鐘，不播放任何聲音（鈴聲與語音都不播）。
 * skipUntil：「略過下次」—被略過的那一次響鈴時間（epoch ms）；這個時間（含）之前的響鈴都不排，之後恢復正常。
 * ttsCachePath / ttsCacheKey：雲端預先產生的語音檔與它對應的內容；內容不符時改用手機內建語音。
 * autoDelete：一次性鬧鐘，響完（按停止或響太久自動停止）就自動刪除；只對只響一次的鬧鐘有效，貪睡時不刪。
 */
@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val daysMask: Int = 0,
    val enabled: Boolean = true,
    val memo: String = "",
    val imagePath: String? = null,
    val notBefore: Long? = null,
    val snoozeUntil: Long? = null,
    val isCopy: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val groupId: Long? = null,
    val soundUri: String? = null,
    val volume: Int? = null,
    @ColumnInfo(defaultValue = "1") val vibrate: Boolean = true,
    @ColumnInfo(defaultValue = "0") val speakMemo: Boolean = false,
    @ColumnInfo(defaultValue = "1") val speakOnceThenRing: Boolean = true,
    @ColumnInfo(defaultValue = "0") val silent: Boolean = false,
    val skipUntil: Long? = null,
    val ttsCachePath: String? = null,
    val ttsCacheKey: String? = null,
    @ColumnInfo(defaultValue = "0") val autoDelete: Boolean = false,
)

/**
 * 鬧鐘群組。
 * enabled = false 時，群組內所有鬧鐘暫停；重新開啟後，各鬧鐘恢復自己原本的開關狀態。
 */
@Entity(tableName = "alarm_groups")
data class AlarmGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val expanded: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** 群組底色（調色盤索引）；-1 代表舊資料尚未指定，顯示時依 id 自動挑一個 */
    @ColumnInfo(defaultValue = "-1") val colorIndex: Int = -1,
)

/**
 * 倒數計時器。
 * 執行中時以 endAtElapsed（同一次開機內最準確，不受改系統時間影響）為主，
 * 重新開機後 elapsedRealtime 歸零，改用 endAtWall。
 * groupId：所屬計時器群組；null 代表未分組。
 * pausedByGroup：因為群組被關閉而暫停；群組重新開啟時只繼續這些計時器。
 * autoDelete：一次性計時器，響完（按停止或響太久自動停止）就自動刪除；貪睡時不刪。
 */
@Entity(tableName = "timers")
data class TimerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String = "",
    val durationMs: Long,
    val state: TimerState = TimerState.PAUSED,
    val remainingMs: Long = durationMs,
    val endAtWall: Long? = null,
    val endAtElapsed: Long? = null,
    val bootCount: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val groupId: Long? = null,
    @ColumnInfo(defaultValue = "0") val pausedByGroup: Boolean = false,
    @ColumnInfo(defaultValue = "0") val autoDelete: Boolean = false,
)

/**
 * 計時器群組（與鬧鐘群組分開）。
 * 關閉群組＝暫停群組內所有執行中的計時器；重新開啟＝繼續那些因關閉而暫停的計時器。
 */
@Entity(tableName = "timer_groups")
data class TimerGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val expanded: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "-1") val colorIndex: Int = -1,
)

/** 群組調色盤的顏色數量（實際顏色定義在 ui.theme.GroupColors） */
const val GROUP_COLOR_COUNT = 8

/** 實際使用的顏色索引：未指定時依 id 決定，確保舊群組也有顏色且彼此不同 */
fun effectiveColor(colorIndex: Int, id: Long): Int =
    if (colorIndex in 0 until GROUP_COLOR_COUNT) colorIndex else (id % GROUP_COLOR_COUNT).toInt()

val AlarmGroupEntity.color: Int get() = effectiveColor(colorIndex, id)
val TimerGroupEntity.color: Int get() = effectiveColor(colorIndex, id)

/** 新群組自動配色：挑目前用得最少的顏色，同樣少時取前面的 */
fun nextGroupColor(used: List<Int>): Int =
    (0 until GROUP_COLOR_COUNT).minBy { c -> used.count { it == c } * GROUP_COLOR_COUNT + c }

enum class TimerState { RUNNING, PAUSED, RINGING, DONE }

/** 一次性鬧鐘：響完會自動刪除（重複鬧鐘不適用） */
val AlarmEntity.deletesAfterRing: Boolean get() = autoDelete && daysMask == 0

/** 是否正在「略過下次」（被略過的那一次還沒過） */
fun AlarmEntity.isSkipping(nowMs: Long): Boolean = daysMask != 0 && skipUntil != null && skipUntil > nowMs

/** 鬧鐘實際是否會響：自己開著，且所屬群組也開著 */
fun AlarmEntity.isActive(groups: Map<Long, AlarmGroupEntity>): Boolean =
    enabled && (groupId == null || groups[groupId]?.enabled != false)
