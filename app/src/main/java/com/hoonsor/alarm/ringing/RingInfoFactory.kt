package com.hoonsor.alarm.ringing

import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.data.AlarmEntity
import com.hoonsor.alarm.domain.Days
import com.hoonsor.alarm.domain.Fmt
import com.hoonsor.alarm.scheduler.FireType
import com.hoonsor.alarm.tts.TtsEngine

/** 由鬧鐘資料組出響鈴內容；正式響鈴與「立即測試」共用，確保測試結果與實際一致。 */
object RingInfoFactory {
    fun forAlarm(a: AlarmEntity, triggerAt: Long, wasSnooze: Boolean = false, isTest: Boolean = false): RingInfo = RingInfo(
        type = FireType.ALARM,
        id = a.id,
        triggerAt = triggerAt,
        title = Fmt.hm(a.hour, a.minute),
        subtitle = (if (isTest) "測試 · " else "鬧鐘 · ") + Days.summary(a.daysMask) + if (wasSnooze) "（貪睡）" else "",
        memo = a.memo,
        imagePath = a.imagePath ?: Graph.settings.defaultImagePath,
        soundUri = a.soundUri ?: Graph.settings.alarmSoundUri,
        volume = a.volume,
        // 純震動鬧鐘一定要震動，否則等於沒有提醒
        vibrate = a.vibrate || a.silent,
        silent = a.silent,
        speakText = if (a.speakMemo && !a.silent) TtsEngine.speechText(a).takeIf { it.isNotBlank() } else null,
        speakFile = TtsEngine.validCache(a),
        speakOnce = a.speakOnceThenRing,
        isTest = isTest,
    )
}
