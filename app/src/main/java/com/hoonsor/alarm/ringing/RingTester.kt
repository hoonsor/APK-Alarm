package com.hoonsor.alarm.ringing

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** 立即測試響鈴：用與正式響鈴相同的服務與畫面，但不更動任何鬧鐘資料 */
object RingTester {
    fun start(context: Context, info: RingInfo) {
        val test = info.copy(isTest = true, triggerAt = System.currentTimeMillis())
        ContextCompat.startForegroundService(context, RingingService.startIntent(context, test))
        // App 在前景時全螢幕通知只會顯示成橫幅，測試時直接開啟響鈴畫面
        runCatching {
            context.startActivity(Intent(context, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
