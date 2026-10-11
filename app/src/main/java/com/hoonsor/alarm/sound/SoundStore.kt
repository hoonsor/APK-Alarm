package com.hoonsor.alarm.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 鈴聲的儲存與讀取。
 * 鈴聲值有三種：
 *  - null：系統鬧鐘鈴聲
 *  - "content://…"：系統鈴聲選擇器挑的鈴聲
 *  - "/data/…/files/sounds/…"：使用者匯入的音檔（已複製到 App 內部，原檔刪除也不影響）
 */
object SoundStore {
    private const val TAG = "SoundStore"
    private const val SEP = "__"
    private const val MAX_BYTES = 50L * 1024 * 1024

    /** 把使用者選的音檔複製到 App 內部，回傳內部路徑；失敗或太大回傳 null */
    suspend fun import(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val name = displayNameOf(context, uri) ?: "鈴聲.mp3"
            val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
            if (size != null && size > MAX_BYTES) return@runCatching null
            val dir = File(context.filesDir, "sounds").apply { mkdirs() }
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
            val file = File(dir, UUID.randomUUID().toString().take(8) + SEP + safe)
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            } ?: return@runCatching null
            // 確認真的能播放，避免匯入損毀檔案後響鈴才發現
            if (!isPlayable(context, file.absolutePath)) {
                file.delete()
                return@runCatching null
            }
            file.absolutePath
        }.onFailure { Log.e(TAG, "import failed", it) }.getOrNull()
    }

    fun toUri(value: String): Uri = if (value.startsWith("/")) Uri.fromFile(File(value)) else Uri.parse(value)

    fun displayName(context: Context, value: String?): String {
        if (value.isNullOrBlank()) return "系統鬧鐘鈴聲"
        if (value.startsWith("/")) {
            val f = File(value)
            if (!f.exists()) return "（檔案遺失，將改用系統鈴聲）"
            return f.name.substringAfter(SEP)
        }
        return runCatching { RingtoneManager.getRingtone(context, Uri.parse(value))?.getTitle(context) }
            .getOrNull() ?: "系統鈴聲"
    }

    fun isPlayable(context: Context, value: String): Boolean = runCatching {
        MediaPlayer().apply {
            setDataSource(context, toUri(value))
            prepare()
            release()
        }
        true
    }.getOrDefault(false)

    private fun displayNameOf(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}

/** 設定頁與編輯面板的試聽：同一時間只播一個，以鬧鐘音量播放 */
object SoundPreview {
    private var player: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    var playingValue: String? = null
        private set

    /** boostDb > 0 時用 LoudnessEnhancer 增幅（語音試聽用） */
    fun play(context: Context, value: String?, boostDb: Int = 0, onComplete: () -> Unit = {}) {
        stop()
        val uri = value?.let { SoundStore.toUri(it) }
            ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = false
                prepare()
                start()
                setOnCompletionListener { this@SoundPreview.stop(); onComplete() }
            }
        }.onFailure { Log.w("SoundPreview", "preview failed", it) }.getOrNull()
        if (boostDb > 0) player?.let { p ->
            enhancer = runCatching {
                LoudnessEnhancer(p.audioSessionId).apply { setTargetGain(boostDb * 100); enabled = true }
            }.onFailure { Log.w("SoundPreview", "boost failed", it) }.getOrNull()
        }
        playingValue = if (player != null) (value ?: "") else null
    }

    fun stop() {
        enhancer?.let { runCatching { it.release() } }
        enhancer = null
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playingValue = null
    }
}
