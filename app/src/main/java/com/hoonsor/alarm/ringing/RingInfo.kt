package com.hoonsor.alarm.ringing

import android.content.Intent
import com.hoonsor.alarm.scheduler.FireType

/** 一次響鈴所需的全部顯示資料。 */
data class RingInfo(
    val type: FireType,
    val id: Long,
    val triggerAt: Long,
    val title: String,
    val subtitle: String,
    val memo: String,
    val imagePath: String?,
    val soundUri: String? = null,
    /** 10–100；null 代表沿用手機目前的鬧鐘音量 */
    val volume: Int? = null,
    val vibrate: Boolean = true,
    /** 不為 null 時以語音念出這段文字取代鈴聲 */
    val speakText: String? = null,
    /** 預先產生的雲端語音檔；不存在時改用手機內建語音 */
    val speakFile: String? = null,
    /** 語音念一次後改播鈴聲 */
    val speakOnce: Boolean = false,
    /** 純震動：不播放任何聲音 */
    val silent: Boolean = false,
    /** 測試響鈴：不更動任何鬧鐘資料、不留下錯過通知 */
    val isTest: Boolean = false,
) {
    fun writeTo(intent: Intent): Intent = intent
        .putExtra("ri_type", type.name)
        .putExtra("ri_id", id)
        .putExtra("ri_trigger", triggerAt)
        .putExtra("ri_title", title)
        .putExtra("ri_subtitle", subtitle)
        .putExtra("ri_memo", memo)
        .putExtra("ri_image", imagePath)
        .putExtra("ri_sound", soundUri)
        .putExtra("ri_volume", volume ?: -1)
        .putExtra("ri_vibrate", vibrate)
        .putExtra("ri_speak", speakText)
        .putExtra("ri_speak_file", speakFile)
        .putExtra("ri_speak_once", speakOnce)
        .putExtra("ri_silent", silent)
        .putExtra("ri_test", isTest)

    companion object {
        fun from(intent: Intent?): RingInfo? {
            intent ?: return null
            val type = runCatching { FireType.valueOf(intent.getStringExtra("ri_type") ?: "") }.getOrNull() ?: return null
            return RingInfo(
                type = type,
                id = intent.getLongExtra("ri_id", -1),
                triggerAt = intent.getLongExtra("ri_trigger", 0),
                title = intent.getStringExtra("ri_title") ?: "",
                subtitle = intent.getStringExtra("ri_subtitle") ?: "",
                memo = intent.getStringExtra("ri_memo") ?: "",
                imagePath = intent.getStringExtra("ri_image"),
                soundUri = intent.getStringExtra("ri_sound"),
                volume = intent.getIntExtra("ri_volume", -1).takeIf { it in 1..100 },
                vibrate = intent.getBooleanExtra("ri_vibrate", true),
                speakText = intent.getStringExtra("ri_speak"),
                speakFile = intent.getStringExtra("ri_speak_file"),
                speakOnce = intent.getBooleanExtra("ri_speak_once", false),
                silent = intent.getBooleanExtra("ri_silent", false),
                isTest = intent.getBooleanExtra("ri_test", false),
            )
        }
    }
}
