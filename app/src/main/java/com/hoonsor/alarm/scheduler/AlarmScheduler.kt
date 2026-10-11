package com.hoonsor.alarm.scheduler

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.hoonsor.alarm.data.AppDatabase
import com.hoonsor.alarm.data.AppSettings
import com.hoonsor.alarm.domain.NextTrigger
import com.hoonsor.alarm.ui.MainActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FireType { ALARM, TIMER }

/**
 * 可靠度核心。
 *
 * 原則：資料庫是唯一的資料來源。任何變動（編輯、響鈴、開機、改時間、App 更新）
 * 都呼叫 [rescheduleAll]，從頭重新計算並向系統登記全部項目，
 * 不依賴「響完再排下一次」這種單點流程。
 *
 * 每個項目登記兩個排程：
 *  - 主排程：setAlarmClock()，系統優先權最高，最不受省電機制影響
 *  - 備援排程：主排程 60 秒後的 setExactAndAllowWhileIdle()；
 *    若主排程已觸發（以 triggerAt 去重），備援會被忽略
 */
class AlarmScheduler(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: AppSettings,
) {
    private val am = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()

    suspend fun rescheduleAll(reason: String) = mutex.withLock {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val boot = bootCount(context)
        val newCodes = mutableSetOf<Int>()

        val groups = db.groupDao().getAll().associateBy { it.id }
        for (alarm in db.alarmDao().getAll()) {
            val at = NextTrigger.forAlarm(alarm, nowWall, groups) ?: continue
            register(FireType.ALARM, alarm.id, at, newCodes)
        }
        for (timer in db.timerDao().getAll()) {
            val at = NextTrigger.forTimer(timer, nowWall, nowElapsed, boot) ?: continue
            // 已經過期（例如關機期間到期）的計時器，立刻補響
            register(FireType.TIMER, timer.id, maxOf(at, nowWall + 1000), newCodes)
        }

        // 取消已不存在或不需要排程的項目
        for (code in settings.scheduledCodes - newCodes) {
            cancel(code)
        }
        settings.scheduledCodes = newCodes
        settings.lastRescheduleAt = nowWall
        settings.lastRescheduleReason = reason
        settings.lastScheduledCount = newCodes.size / 2
        Log.i(TAG, "rescheduleAll($reason): ${newCodes.size / 2} items")
    }

    /** App 預期的下一次響鈴（給可靠度檢查頁比對用） */
    suspend fun expectedNext(): Long? {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val boot = bootCount(context)
        val groups = db.groupDao().getAll().associateBy { it.id }
        val a = db.alarmDao().getAll().mapNotNull { NextTrigger.forAlarm(it, nowWall, groups) }
        val t = db.timerDao().getAll().mapNotNull { NextTrigger.forTimer(it, nowWall, nowElapsed, boot) }
        return (a + t).minOrNull()
    }

    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true

    @SuppressLint("ScheduleExactAlarm", "MissingPermission") // 已由 canScheduleExact() 判斷並提供退路
    private fun register(type: FireType, id: Long, triggerAt: Long, codes: MutableSet<Int>) {
        val primaryCode = requestCode(type, id, backup = false)
        val backupCode = requestCode(type, id, backup = true)
        val primary = firePendingIntent(type, id, triggerAt, backup = false, code = primaryCode)
        val backup = firePendingIntent(type, id, triggerAt, backup = true, code = backupCode)
        try {
            if (canScheduleExact()) {
                val show = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), primary)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt + BACKUP_DELAY_MS, backup)
            } else {
                // 權限被收回時的退路：不精準但至少會響，健康檢查頁會亮紅燈
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, primary)
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt + BACKUP_DELAY_MS, backup)
            }
            codes += primaryCode
            codes += backupCode
        } catch (e: SecurityException) {
            Log.e(TAG, "schedule failed", e)
        }
    }

    private fun cancel(code: Int) {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE)
        val pi = PendingIntent.getBroadcast(
            context, code, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    private fun firePendingIntent(type: FireType, id: Long, triggerAt: Long, backup: Boolean, code: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .putExtra(AlarmReceiver.EXTRA_TYPE, type.name)
            .putExtra(AlarmReceiver.EXTRA_ID, id)
            .putExtra(AlarmReceiver.EXTRA_TRIGGER_AT, triggerAt)
            .putExtra(AlarmReceiver.EXTRA_BACKUP, backup)
        return PendingIntent.getBroadcast(
            context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "AlarmScheduler"
        const val BACKUP_DELAY_MS = 60_000L
        private const val TIMER_CODE_BASE = 1_000_000

        fun requestCode(type: FireType, id: Long, backup: Boolean): Int {
            val base = if (type == FireType.ALARM) 0 else TIMER_CODE_BASE
            return base + (id * 2).toInt() + if (backup) 1 else 0
        }

        fun bootCount(context: Context): Int =
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }
}
