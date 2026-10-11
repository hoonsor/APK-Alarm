package com.hoonsor.alarm

import android.app.Application
import android.content.Context
import android.media.AudioManager
import com.hoonsor.alarm.data.AppDatabase
import com.hoonsor.alarm.data.AppSettings
import com.hoonsor.alarm.data.Repository
import com.hoonsor.alarm.ringing.Notifications
import com.hoonsor.alarm.scheduler.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 簡單的 service locator，避免引入 DI 框架。 */
object Graph {
    lateinit var appContext: Context
        private set
    val db: AppDatabase by lazy { AppDatabase.build(appContext) }
    val settings: AppSettings by lazy { AppSettings(appContext) }
    val scheduler: AlarmScheduler by lazy { AlarmScheduler(appContext, db, settings) }
    val repo: Repository by lazy { Repository(db, scheduler) }

    /** 跟 App 生命週期一樣長的背景工作範圍 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}

class AlarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifications.createChannels(this)
        // 上次響鈴中途 App 被系統結束、沒來得及還原鬧鐘音量時，在這裡補還原
        val saved = Graph.settings.savedAlarmVolume
        if (saved >= 0) {
            runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
            Graph.settings.savedAlarmVolume = -1
        }
    }
}
