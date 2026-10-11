package com.hoonsor.alarm.data

import android.content.Context
import android.content.SharedPreferences

enum class VignetteShape { ELLIPSE, SQUARE }

/** 輕量設定與狀態，存在 SharedPreferences。 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val acks: SharedPreferences = context.getSharedPreferences("acks", Context.MODE_PRIVATE)

    var vignetteShape: VignetteShape
        get() = runCatching { VignetteShape.valueOf(prefs.getString("vignetteShape", null) ?: "ELLIPSE") }
            .getOrDefault(VignetteShape.ELLIPSE)
        set(v) = prefs.edit().putString("vignetteShape", v.name).apply()

    /** 0–100 */
    var vignetteStrength: Int
        get() = prefs.getInt("vignetteStrength", 70)
        set(v) = prefs.edit().putInt("vignetteStrength", v.coerceIn(0, 100)).apply()

    /** 未個別設定圖片的鬧鐘，響鈴時使用的全域預設圖片 */
    var defaultImagePath: String?
        get() = prefs.getString("defaultImagePath", null)
        set(v) = prefs.edit().putString("defaultImagePath", v).apply()

    /** 鬧鐘預設鈴聲；null = 系統鬧鐘鈴聲。可為 content:// URI 或 App 內部音檔的絕對路徑 */
    var alarmSoundUri: String?
        get() = prefs.getString("alarmSoundUri", null)
        set(v) = prefs.edit().putString("alarmSoundUri", v).apply()

    /** 計時器鈴聲；null = 系統鬧鐘鈴聲 */
    var timerSoundUri: String?
        get() = prefs.getString("timerSoundUri", null)
        set(v) = prefs.edit().putString("timerSoundUri", v).apply()

    // ---------- AI 語音解析（OpenAI 相容 API，預設 MiniMax） ----------

    var aiBaseUrl: String
        get() = prefs.getString("aiBaseUrl", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_AI_BASE_URL
        set(v) = prefs.edit().putString("aiBaseUrl", v.trim()).apply()

    var aiModel: String
        get() = prefs.getString("aiModel", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_AI_MODEL
        set(v) = prefs.edit().putString("aiModel", v.trim()).apply()

    /** 以 Android Keystore 加密後的 API key（Base64），明文不落地 */
    var aiKeyCipher: String?
        get() = prefs.getString("aiKeyCipher", null)
        set(v) = prefs.edit().putString("aiKeyCipher", v).apply()

    var aiEnabled: Boolean
        get() = prefs.getBoolean("aiEnabled", true)
        set(v) = prefs.edit().putBoolean("aiEnabled", v).apply()

    // ---------- 語音播報 ----------

    /** "system" = 手機內建 TTS；"minimax" = MiniMax 雲端預先產生 */
    var ttsEngine: String
        get() = prefs.getString("ttsEngine", "system") ?: "system"
        set(v) = prefs.edit().putString("ttsEngine", v).apply()

    var ttsVoice: String
        get() = prefs.getString("ttsVoice", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TTS_VOICE
        set(v) = prefs.edit().putString("ttsVoice", v.trim()).apply()

    var ttsModel: String
        get() = prefs.getString("ttsModel", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TTS_MODEL
        set(v) = prefs.edit().putString("ttsModel", v.trim()).apply()

    /** 語速 0.5–2.0 */
    var ttsRate: Float
        get() = prefs.getFloat("ttsRate", 1.0f)
        set(v) = prefs.edit().putFloat("ttsRate", v.coerceIn(0.5f, 2.0f)).apply()

    /** 語音增幅（dB），0 = 不增幅；語音通常比鈴聲小聲，預設 +8 dB */
    var ttsBoostDb: Int
        get() = prefs.getInt("ttsBoostDb", 8)
        set(v) = prefs.edit().putInt("ttsBoostDb", v.coerceIn(0, 15)).apply()

    /** 念提醒前先念出時間，例如「現在是早上 7 點 15 分」 */
    var ttsSpeakTime: Boolean
        get() = prefs.getBoolean("ttsSpeakTime", true)
        set(v) = prefs.edit().putBoolean("ttsSpeakTime", v).apply()

    /** 響鈴期間暫時調整鬧鐘音量前的原值，服務異常結束時下次啟動可還原；-1 代表無 */
    var savedAlarmVolume: Int
        get() = prefs.getInt("savedAlarmVolume", -1)
        set(v) = prefs.edit().putInt("savedAlarmVolume", v).commit().let { }

    var rampSeconds: Int
        get() = prefs.getInt("rampSeconds", 30)
        set(v) = prefs.edit().putInt("rampSeconds", v.coerceIn(0, 120)).apply()

    var lastRescheduleAt: Long
        get() = prefs.getLong("lastRescheduleAt", 0L)
        set(v) = prefs.edit().putLong("lastRescheduleAt", v).apply()

    var lastRescheduleReason: String
        get() = prefs.getString("lastRescheduleReason", "") ?: ""
        set(v) = prefs.edit().putString("lastRescheduleReason", v).apply()

    var lastScheduledCount: Int
        get() = prefs.getInt("lastScheduledCount", 0)
        set(v) = prefs.edit().putInt("lastScheduledCount", v).apply()

    /** 使用者手動確認廠牌自啟動設定的時間 */
    var oemConfirmedAt: Long
        get() = prefs.getLong("oemConfirmedAt", 0L)
        set(v) = prefs.edit().putLong("oemConfirmedAt", v).apply()

    /** 上一次排程時登記過的 PendingIntent request code，用來取消已刪除項目的排程 */
    var scheduledCodes: Set<Int>
        get() = prefs.getStringSet("scheduledCodes", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = prefs.edit().putStringSet("scheduledCodes", v.map { it.toString() }.toSet()).apply()

    /**
     * 主排程與備援排程的去重：同一次響鈴（同一個 triggerAt）只處理一次。
     * 用 commit() 同步寫入，避免兩個廣播幾乎同時到達時重複響鈴。
     */
    @Synchronized
    fun tryAcknowledge(key: String, triggerAt: Long): Boolean {
        if (acks.getLong(key, Long.MIN_VALUE) == triggerAt) return false
        acks.edit().putLong(key, triggerAt).commit()
        return true
    }

    // 碼錶狀態
    var swRunning: Boolean
        get() = prefs.getBoolean("swRunning", false)
        set(v) = prefs.edit().putBoolean("swRunning", v).apply()
    var swAccumulatedMs: Long
        get() = prefs.getLong("swAccumulated", 0L)
        set(v) = prefs.edit().putLong("swAccumulated", v).apply()
    var swStartElapsed: Long
        get() = prefs.getLong("swStartElapsed", 0L)
        set(v) = prefs.edit().putLong("swStartElapsed", v).apply()
    var swStartWall: Long
        get() = prefs.getLong("swStartWall", 0L)
        set(v) = prefs.edit().putLong("swStartWall", v).apply()
    var swBootCount: Int
        get() = prefs.getInt("swBootCount", -1)
        set(v) = prefs.edit().putInt("swBootCount", v).apply()
    /** 每個分段的累計時間（ms），以逗號分隔 */
    var swLaps: List<Long>
        get() = (prefs.getString("swLaps", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
        set(v) = prefs.edit().putString("swLaps", v.joinToString(",")).apply()

    /** 計時器上次自訂的時長，下次開啟沿用 */
    var lastTimerMs: Long
        get() = prefs.getLong("lastTimerMs", 25 * 60_000L)
        set(v) = prefs.edit().putLong("lastTimerMs", v).apply()

    companion object {
        const val DEFAULT_AI_BASE_URL = "https://api.minimax.io/v1"
        const val DEFAULT_AI_MODEL = "MiniMax-M3"
        const val DEFAULT_TTS_VOICE = "Chinese (Mandarin)_Lyrical_Voice"
        const val DEFAULT_TTS_MODEL = "speech-2.8-turbo"
    }
}
