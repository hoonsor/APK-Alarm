package com.hoonsor.alarm.ui.health

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.hoonsor.alarm.Graph

enum class Level { OK, WARN, INFO }

data class CheckItem(
    val title: String,
    val status: String,
    val level: Level,
    val detail: String? = null,
    val fixIntent: Intent? = null,
    val fixLabel: String? = null,
)

data class HealthReport(
    val items: List<CheckItem>,
    val expectedNext: Long?,
    val systemNext: Long?,
    val systemNextIsOurs: Boolean,
    val lastRescheduleAt: Long,
    val lastRescheduleReason: String,
    val scheduledCount: Int,
) {
    val issues: Int get() = items.count { it.level == Level.WARN } + if (!consistent) 1 else 0

    /** App 預期的下一次響鈴，系統登記的必須一致（容許 1 秒誤差） */
    val consistent: Boolean
        get() = expectedNext == null ||
            (systemNext != null && systemNextIsOurs && kotlin.math.abs(systemNext - expectedNext) < 1000) ||
            (systemNext != null && !systemNextIsOurs && systemNext < expectedNext)
}

object HealthChecker {

    suspend fun run(context: Context): HealthReport {
        val pkg = context.packageName
        val items = mutableListOf<CheckItem>()
        val scheduler = Graph.scheduler
        val settings = Graph.settings

        val exact = scheduler.canScheduleExact()
        items += CheckItem(
            "精準鬧鐘排程", if (exact) "已允許" else "未允許",
            if (exact) Level.OK else Level.WARN,
            detail = if (exact) null else "未允許時鬧鐘可能延遲數分鐘。",
            fixIntent = if (!exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")) else null,
            fixLabel = "前往設定",
        )

        val nm = context.getSystemService(NotificationManager::class.java)
        val fullScreen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) nm.canUseFullScreenIntent() else true
        items += CheckItem(
            "鎖屏全螢幕響鈴", if (fullScreen) "已允許" else "未允許",
            if (fullScreen) Level.OK else Level.WARN,
            detail = if (fullScreen) null else "未允許時，鎖屏只會出現一般通知，不會跳出響鈴畫面。",
            fixIntent = if (!fullScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg")) else null,
            fixLabel = "前往設定",
        )

        val notif = NotificationManagerCompat.from(context).areNotificationsEnabled()
        items += CheckItem(
            "通知", if (notif) "已允許" else "未允許",
            if (notif) Level.OK else Level.WARN,
            detail = if (notif) null else "響鈴畫面依賴通知顯示，關閉時可能只聽得到聲音。",
            fixIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
            fixLabel = "前往設定",
        )

        val pm = context.getSystemService(PowerManager::class.java)
        val ignoring = pm.isIgnoringBatteryOptimizations(pkg)
        items += CheckItem(
            "電池最佳化豁免", if (ignoring) "已豁免" else "未豁免",
            if (ignoring) Level.OK else Level.WARN,
            detail = if (ignoring) null else "部分廠牌在深度休眠時，可能延遲或略過未豁免 App 的鬧鐘。",
            fixIntent = if (ignoring) null else
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
            fixLabel = "前往設定豁免",
        )

        val am = context.getSystemService(AudioManager::class.java)
        val vol = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
        val pct = vol * 100 / max
        items += CheckItem(
            "鬧鐘音量", "$pct%",
            if (pct >= 30) Level.OK else Level.WARN,
            detail = if (pct >= 30) null else "鬧鐘音量偏低，可能聽不到。",
            fixIntent = Intent(Settings.ACTION_SOUND_SETTINGS),
            fixLabel = "調整音量",
        )

        val oem = settings.oemConfirmedAt
        items += CheckItem(
            "廠牌自啟動", if (oem > 0) "已確認" else "請手動確認",
            if (oem > 0) Level.OK else Level.INFO,
            detail = if (oem > 0) "手動確認於 " + com.hoonsor.alarm.domain.Fmt.dateTime(oem)
            else "小米、OPPO、vivo、三星等品牌另有自啟動或背景限制，系統無法偵測，請在 App 資訊頁確認後按「已確認」。",
            fixIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
            fixLabel = "開啟 App 資訊",
        )

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val next = alarmManager.nextAlarmClock
        return HealthReport(
            items = items,
            expectedNext = scheduler.expectedNext(),
            systemNext = next?.triggerTime,
            systemNextIsOurs = next?.showIntent?.creatorPackage == pkg,
            lastRescheduleAt = settings.lastRescheduleAt,
            lastRescheduleReason = settings.lastRescheduleReason,
            scheduledCount = settings.lastScheduledCount,
        )
    }
}
