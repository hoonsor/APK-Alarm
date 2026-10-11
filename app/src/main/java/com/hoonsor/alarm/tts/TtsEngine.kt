package com.hoonsor.alarm.tts

import android.util.Log
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.data.AlarmEntity
import com.hoonsor.alarm.data.KeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 語音播報。
 *
 * 可靠度設計：雲端語音只在「儲存鬧鐘時」預先產生，存成 App 內的音檔；
 * 響鈴時只播放本地檔，不依賴網路。快取缺少或內容已變（ttsCacheKey 不符）時，
 * 響鈴服務改用手機內建 TTS 即時念出；內建 TTS 也失敗才回到一般鈴聲。
 */
object TtsEngine {
    private const val TAG = "TtsEngine"
    const val ENGINE_SYSTEM = "system"
    const val ENGINE_MINIMAX = "minimax"

    /** 常用的 MiniMax 中文聲音（也可在設定頁手動輸入其他 voice_id） */
    /**
     * MiniMax 國際版系統聲音（platform.minimax.io 官方清單中的 Chinese (Mandarin) 聲音）。
     * 官方只提供英文名稱，中文標籤依名稱翻譯；實際聲音以試聽為準。
     */
    val MINIMAX_VOICES = listOf(
        "Chinese (Mandarin)_Sweet_Lady" to "甜美女聲",
        "Chinese (Mandarin)_Warm_Girl" to "溫暖女孩",
        "Chinese (Mandarin)_Soft_Girl" to "溫柔女孩",
        "Chinese (Mandarin)_Crisp_Girl" to "清脆女孩",
        "Chinese (Mandarin)_Mature_Woman" to "成熟女聲",
        "Chinese (Mandarin)_HK_Flight_Attendant" to "港式空服員",
        "Chinese (Mandarin)_News_Anchor" to "新聞主播",
        "Chinese (Mandarin)_Radio_Host" to "電台主持",
        "Chinese (Mandarin)_Lyrical_Voice" to "抒情男聲",
        "Chinese (Mandarin)_Gentleman" to "紳士男聲",
        "Chinese (Mandarin)_Gentle_Youth" to "溫柔青年",
        "Chinese (Mandarin)_Male_Announcer" to "男播音員",
    )

    /** 響鈴時要念的完整文字 */
    fun speechText(hour: Int, minute: Int, memo: String, speakTime: Boolean = Graph.settings.ttsSpeakTime): String {
        val body = memo.trim()
        if (!speakTime) return body
        val period = when (hour) {
            in 0..4 -> "凌晨"
            in 5..11 -> "早上"
            12 -> "中午"
            in 13..17 -> "下午"
            else -> "晚上"
        }
        val h12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val time = if (minute == 0) "$h12 點整" else "$h12 點 $minute 分"
        return if (body.isEmpty()) "現在是$period $time。" else "現在是$period $time。$body"
    }

    fun speechText(alarm: AlarmEntity): String = speechText(alarm.hour, alarm.minute, alarm.memo)

    /** 快取鍵：文字、引擎、聲音、模型、語速任一改變，舊檔就作廢 */
    fun cacheKey(text: String): String {
        val s = Graph.settings
        val raw = listOf(s.ttsEngine, s.ttsModel, s.ttsVoice, s.ttsRate.toString(), text).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 這個鬧鐘目前有效的雲端語音檔；沒有或已過期回傳 null */
    fun validCache(alarm: AlarmEntity): String? {
        if (!alarm.speakMemo || Graph.settings.ttsEngine != ENGINE_MINIMAX) return null
        val path = alarm.ttsCachePath ?: return null
        if (alarm.ttsCacheKey != cacheKey(speechText(alarm))) return null
        return path.takeIf { File(it).let { f -> f.exists() && f.length() > 0 } }
    }

    /**
     * 需要時為鬧鐘產生雲端語音檔並寫回資料庫。失敗不丟例外（響鈴會改用內建語音）。
     * 回傳結果文字給介面顯示。
     */
    suspend fun prepare(alarmId: Long): String = withContext(Dispatchers.IO) {
        val dao = Graph.db.alarmDao()
        val alarm = dao.get(alarmId) ?: return@withContext "鬧鐘不存在"
        val s = Graph.settings
        if (!alarm.speakMemo) return@withContext "未啟用語音播報"
        if (s.ttsEngine != ENGINE_MINIMAX) return@withContext "使用手機內建語音"
        val text = speechText(alarm)
        val key = cacheKey(text)
        if (alarm.ttsCacheKey == key && validCache(alarm) != null) return@withContext "已產生雲端語音"
        try {
            val bytes = synthesizeMiniMax(text)
            val dir = File(Graph.appContext.filesDir, "tts").apply { mkdirs() }
            val file = File(dir, "alarm_${alarm.id}_$key.mp3")
            file.writeBytes(bytes)
            val latest = dao.get(alarmId) ?: return@withContext "鬧鐘不存在"
            val old = latest.ttsCachePath
            dao.setTtsCache(alarm.id, file.absolutePath, key)
            if (old != null && old != file.absolutePath) runCatching { File(old).delete() }
            "已產生雲端語音"
        } catch (e: Exception) {
            Log.w(TAG, "prepare failed", e)
            "雲端語音產生失敗，響鈴時改用手機內建語音（${e.message}）"
        }
    }

    /** 設定變更後，為所有開啟語音播報的鬧鐘重新產生 */
    suspend fun prepareAll(): Int {
        var ok = 0
        Graph.db.alarmDao().getAll().filter { it.speakMemo }.forEach {
            if (prepare(it.id) == "已產生雲端語音") ok++
        }
        return ok
    }

    /** 試聽用：產生到暫存檔 */
    suspend fun synthesizeToTemp(text: String): File = withContext(Dispatchers.IO) {
        val file = File(Graph.appContext.cacheDir, "tts_preview.mp3")
        file.writeBytes(synthesizeMiniMax(text))
        file
    }

    // ---------- MiniMax T2A v2 ----------

    private fun synthesizeMiniMax(text: String): ByteArray {
        val s = Graph.settings
        val key = KeyVault.decrypt(s.aiKeyCipher) ?: throw IllegalStateException("尚未設定 MiniMax API key（在「AI 語音解析」填入）")
        val base = s.aiBaseUrl.trimEnd('/')
        if (!base.contains("minimax", ignoreCase = true)) throw IllegalStateException("AI 網址不是 MiniMax，無法使用 MiniMax 語音")
        val body = JSONObject()
            .put("model", s.ttsModel)
            .put("text", text)
            .put("stream", false)
            .put("language_boost", "Chinese")
            .put("output_format", "hex")
            .put(
                "voice_setting",
                JSONObject().put("voice_id", s.ttsVoice).put("speed", s.ttsRate.toDouble()).put("vol", 1.0).put("pitch", 0),
            )
            .put(
                "audio_setting",
                JSONObject().put("sample_rate", 32000).put("bitrate", 128000).put("format", "mp3").put("channel", 1),
            )
        val conn = (URL("$base/t2a_v2").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val json = JSONObject(resp)
            json.optJSONObject("base_resp")?.let { br ->
                val sc = br.optInt("status_code", 0)
                if (sc != 0) throw IllegalStateException("錯誤 $sc：${br.optString("status_msg")}")
            }
            val hex = json.optJSONObject("data")?.optString("audio").orEmpty()
            if (hex.isEmpty()) throw IllegalStateException("沒有收到音訊")
            return hexToBytes(hex)
        } finally {
            conn.disconnect()
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) or Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
