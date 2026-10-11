package com.hoonsor.alarm.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.data.TimerState
import com.hoonsor.alarm.domain.Fmt
import com.hoonsor.alarm.ringing.RingInfo
import com.hoonsor.alarm.ringing.RingInfoFactory
import com.hoonsor.alarm.ringing.RingingService
import kotlinx.coroutines.launch

/** 主排程與備援排程到期時都會進來；以 triggerAt 去重，同一次響鈴只處理一次。 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val type = runCatching { FireType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "") }.getOrNull() ?: return
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val triggerAt = intent.getLongExtra(EXTRA_TRIGGER_AT, 0)
        val isBackup = intent.getBooleanExtra(EXTRA_BACKUP, false)
        val pending = goAsync()

        Graph.appScope.launch {
            try {
                if (!Graph.settings.tryAcknowledge("${type.name}_$id", triggerAt)) {
                    Log.i(TAG, "already handled $type $id @$triggerAt (backup=$isBackup)")
                    return@launch
                }
                if (isBackup) Log.w(TAG, "PRIMARY MISSED, backup fired for $type $id")
                val info = when (type) {
                    FireType.ALARM -> onAlarm(id, triggerAt)
                    FireType.TIMER -> onTimer(id, triggerAt)
                } ?: return@launch
                ContextCompat.startForegroundService(context, RingingService.startIntent(context, info))
                Graph.scheduler.rescheduleAll(if (isBackup) "備援排程觸發" else "響鈴觸發")
            } catch (e: Exception) {
                Log.e(TAG, "fire failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun onAlarm(id: Long, triggerAt: Long): RingInfo? {
        val dao = Graph.db.alarmDao()
        val a = dao.get(id) ?: return null
        val wasSnooze = a.snoozeUntil?.let { kotlin.math.abs(it - triggerAt) < 1000 } ?: false
        val groupOn = a.groupId?.let { Graph.db.groupDao().get(it)?.enabled } ?: true
        if ((!a.enabled || !groupOn) && !wasSnooze) return null
        // 只響一次的鬧鐘觸發後即停用；之後的貪睡由 snoozeUntil 處理
        dao.update(
            a.copy(
                enabled = if (a.daysMask == 0) false else a.enabled,
                notBefore = if (a.daysMask == 0) null else a.notBefore,
                snoozeUntil = null,
            ),
        )
        return RingInfoFactory.forAlarm(a, triggerAt, wasSnooze)
    }

    private suspend fun onTimer(id: Long, triggerAt: Long): RingInfo? {
        val dao = Graph.db.timerDao()
        val t = dao.get(id) ?: return null
        if (t.state != TimerState.RUNNING) return null
        dao.update(t.copy(state = TimerState.RINGING, remainingMs = 0, endAtWall = null, endAtElapsed = null))
        return RingInfo(
            type = FireType.TIMER,
            id = t.id,
            triggerAt = triggerAt,
            title = "時間到",
            subtitle = "計時器 · " + Fmt.duration(t.durationMs),
            memo = t.label,
            imagePath = Graph.settings.defaultImagePath,
            soundUri = Graph.settings.timerSoundUri,
        )
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_FIRE = "com.hoonsor.alarm.FIRE"
        const val EXTRA_TYPE = "type"
        const val EXTRA_ID = "id"
        const val EXTRA_TRIGGER_AT = "triggerAt"
        const val EXTRA_BACKUP = "backup"
    }
}

/** 開機、改時間、換時區、App 更新、精準鬧鐘權限變更：一律全量重排。 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "開機"
            Intent.ACTION_TIME_CHANGED -> "系統時間變更"
            Intent.ACTION_TIMEZONE_CHANGED -> "時區變更"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "App 更新"
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> "精準鬧鐘權限變更"
            else -> return
        }
        val pending = goAsync()
        Graph.appScope.launch {
            try {
                Graph.scheduler.rescheduleAll(reason)
            } finally {
                pending.finish()
            }
        }
    }
}
