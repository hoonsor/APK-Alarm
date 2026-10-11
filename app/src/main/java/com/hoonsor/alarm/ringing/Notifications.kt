package com.hoonsor.alarm.ringing

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hoonsor.alarm.R
import com.hoonsor.alarm.ui.MainActivity

object Notifications {
    const val CH_RINGING = "ringing"
    const val CH_MISSED = "missed"
    const val ID_RINGING = 1001
    private const val ID_MISSED_BASE = 2000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ringing = NotificationChannel(CH_RINGING, "響鈴中", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "鬧鐘與計時器響鈴時的全螢幕通知"
            setSound(null, null) // 聲音由 RingingService 以鬧鐘音量通道播放
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val missed = NotificationChannel(CH_MISSED, "錯過的鬧鐘", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "響鈴 10 分鐘無人處理時的提醒"
        }
        nm.createNotificationChannels(listOf(ringing, missed))
    }

    fun ringing(context: Context, info: RingInfo): Notification {
        val fullScreen = PendingIntent.getActivity(
            context, 0,
            Intent(context, RingingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getService(
            context, 1, RingingService.dismissIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snooze = PendingIntent.getService(
            context, 2, RingingService.snoozeIntent(context, 5),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = info.memo.ifBlank { info.subtitle }
        return NotificationCompat.Builder(context, CH_RINGING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(info.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, "貪睡 5 分", snooze)
            .addAction(0, "停止", dismiss)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @SuppressLint("MissingPermission") // 權限缺失時 notify 會被系統忽略，另由可靠度檢查頁提示
    fun postMissed(context: Context, info: RingInfo) {
        val open = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CH_MISSED)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("已錯過 ${info.title}")
            .setContentText(info.memo.ifBlank { info.subtitle })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_MISSED_BASE + (info.id % 1000).toInt(), n) }
    }
}
