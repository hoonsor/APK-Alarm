package com.hoonsor.alarm.ringing

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.data.TimerState
import com.hoonsor.alarm.scheduler.FireType
import com.hoonsor.alarm.sound.SoundStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * 響鈴前景服務：聲音（鬧鐘音量通道、漸強）、震動、WakeLock、全螢幕通知。
 * 10 分鐘無人處理會自動停止並留下「已錯過」通知。
 */
class RingingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var volume = 0f

    private val alarmAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var tts: TextToSpeech? = null
    private var speechActive = false
    private var enhancer: LoudnessEnhancer? = null
    private var speakLoop: Runnable? = null
    private var ttsFallback: Runnable? = null

    /** 音量漸強；語音播報時下一次念出就套用新的音量 */
    private val rampStep = object : Runnable {
        override fun run() {
            val seconds = Graph.settings.rampSeconds
            volume = if (seconds <= 0) 1f else (volume + 0.5f / seconds).coerceAtMost(1f)
            // 語音一律以最大音量播放，漸強只套用在鈴聲
            if (!speechActive) player?.let { p -> runCatching { p.setVolume(volume, volume) } }
            if (volume < 1f) handler.postDelayed(this, 500)
        }
    }

    private val timeout = Runnable { finishRing(snoozeMs = null, missed = true) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val info = RingInfo.from(intent)
                if (info == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                _current.value = info
                startForegroundCompat(info)
                stopAlert()
                startAlert(info)
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, TIMEOUT_MS)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    // Android 9 以下可以直接開啟響鈴畫面
                    runCatching {
                        startActivity(Intent(this, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            ACTION_DISMISS -> finishRing(snoozeMs = null, missed = false)
            ACTION_SNOOZE -> finishRing(snoozeMs = intent.getIntExtra(EXTRA_MINUTES, 5) * 60_000L, missed = false)
            else -> if (_current.value == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(info: RingInfo) {
        val n = Notifications.ringing(this, info)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifications.ID_RINGING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(Notifications.ID_RINGING, n)
        }
    }

    private fun startAlert(info: RingInfo) {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hoonsor:ringing").apply {
            setReferenceCounted(false)
            acquire(TIMEOUT_MS + 60_000)
        }

        // 純震動：不搶音訊焦點（不會打斷正在播的音樂）、不調音量、不播任何聲音
        if (!info.silent) {
            val am = getSystemService(AudioManager::class.java)
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(alarmAttrs)
                .build()
                .also { am.requestAudioFocus(it) }

            applyAlarmVolume(info.volume)
            volume = if (Graph.settings.rampSeconds <= 0) 1f else 0.15f

            val speakText = info.speakText
            when {
                info.speakFile != null && File(info.speakFile).exists() && startSpeechFile(info, info.speakFile) -> Unit
                speakText != null -> startSystemTts(info, speakText)
                else -> startRingtone(info)
            }
            handler.post(rampStep)
        }

        if (info.vibrate || info.silent) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            runCatching {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0), alarmAttrs)
            }
        }
    }

    /** 依序嘗試：指定鈴聲 → 系統鬧鐘鈴聲 → 預設來電鈴聲 → 預設通知音，確保至少有一個能播 */
    private fun startRingtone(info: RingInfo) {
        val candidates = listOfNotNull(
            info.soundUri?.let { SoundStore.toUri(it) },
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            Settings.System.DEFAULT_RINGTONE_URI,
            Settings.System.DEFAULT_NOTIFICATION_URI,
        )
        player = candidates.firstNotNullOfOrNull { createPlayer(it, loop = true) }
        if (player == null) Log.e(TAG, "no playable ringtone; vibration only")
    }

    /** 播放語音檔（雲端快取或內建 TTS 轉出的檔案），最大音量＋增幅；念完停一下再重念或轉鈴聲 */
    private fun startSpeechFile(info: RingInfo, path: String): Boolean {
        val p = createPlayer(Uri.fromFile(File(path)), loop = false, fullVolume = true) ?: return false
        speechActive = true
        attachBoost(p)
        p.setOnCompletionListener { mp ->
            handler.postDelayed({
                if (player !== mp || _current.value == null) return@postDelayed
                if (info.speakOnce) {
                    // 念完一次改播鈴聲
                    releaseBoost()
                    speechActive = false
                    runCatching { mp.release() }
                    player = null
                    startRingtone(info)
                } else {
                    runCatching { mp.start() }
                }
            }, SPEECH_GAP_MS)
        }
        player = p
        return true
    }

    /**
     * 手機內建 TTS：先合成成音檔，再用和雲端語音相同的方式播放（最大音量＋增幅、可念一次後轉鈴聲）。
     * 初始化失敗、不支援中文、合成失敗或逾時都改回鈴聲。
     */
    private fun startSystemTts(info: RingInfo, text: String) {
        var done = false
        val fallback = Runnable {
            if (!done && _current.value != null) {
                done = true
                Log.w(TAG, "system TTS unavailable; ringtone fallback")
                shutdownTts()
                startRingtone(info)
            }
        }
        handler.postDelayed(fallback, TTS_INIT_TIMEOUT_MS)
        ttsFallback = fallback
        val out = File(cacheDir, "ring_tts.wav").apply { delete() }
        tts = TextToSpeech(this) { status ->
            handler.post {
                val engine = tts ?: return@post
                if (_current.value == null || done) return@post
                val langOk = status == TextToSpeech.SUCCESS && listOf(Locale.TRADITIONAL_CHINESE, Locale.CHINESE).any {
                    engine.setLanguage(it) >= TextToSpeech.LANG_AVAILABLE
                }
                if (!langOk) {
                    handler.removeCallbacks(fallback)
                    fallback.run()
                    return@post
                }
                engine.setSpeechRate(Graph.settings.ttsRate)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        handler.post {
                            if (done || _current.value == null) return@post
                            done = true
                            handler.removeCallbacks(fallback)
                            shutdownTts()
                            if (!(out.length() > 0 && startSpeechFile(info, out.absolutePath))) startRingtone(info)
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handler.post { handler.removeCallbacks(fallback); fallback.run() }
                    }
                })
                val result = engine.synthesizeToFile(text, Bundle(), out, "ring")
                if (result != TextToSpeech.SUCCESS) {
                    handler.removeCallbacks(fallback)
                    fallback.run()
                }
            }
        }
    }

    /** 語音增幅：用 LoudnessEnhancer 提高響度（設定頁可調 0–15 dB） */
    private fun attachBoost(p: MediaPlayer) {
        releaseBoost()
        val db = Graph.settings.ttsBoostDb
        if (db <= 0) return
        enhancer = runCatching {
            LoudnessEnhancer(p.audioSessionId).apply {
                setTargetGain(db * 100)
                enabled = true
            }
        }.onFailure { Log.w(TAG, "LoudnessEnhancer failed", it) }.getOrNull()
    }

    private fun releaseBoost() {
        enhancer?.let { runCatching { it.release() } }
        enhancer = null
    }

    private fun shutdownTts() {
        speakLoop?.let { handler.removeCallbacks(it) }
        speakLoop = null
        ttsFallback?.let { handler.removeCallbacks(it) }
        ttsFallback = null
        tts?.let { runCatching { it.stop(); it.shutdown() } }
        tts = null
    }

    /** 個別鬧鐘指定音量時，暫時調整手機的鬧鐘音量，響鈴結束後還原 */
    private fun applyAlarmVolume(percent: Int?) {
        if (percent == null) return
        val am = getSystemService(AudioManager::class.java)
        runCatching {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val current = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (Graph.settings.savedAlarmVolume < 0) Graph.settings.savedAlarmVolume = current
            val target = ((max * percent + 50) / 100).coerceIn(1, max)
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        }.onFailure { Log.w(TAG, "set alarm volume failed", it) }
    }

    private fun restoreAlarmVolume() {
        val saved = Graph.settings.savedAlarmVolume
        if (saved < 0) return
        runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
        Graph.settings.savedAlarmVolume = -1
    }

    private fun createPlayer(uri: Uri, loop: Boolean, fullVolume: Boolean = false): MediaPlayer? = runCatching {
        MediaPlayer().apply {
            setAudioAttributes(alarmAttrs)
            setDataSource(this@RingingService, uri)
            isLooping = loop
            prepare()
            val v = if (fullVolume) 1f else volume
            setVolume(v, v)
            start()
        }
    }.onFailure { Log.w(TAG, "player failed: $uri", it) }.getOrNull()

    private fun stopAlert() {
        handler.removeCallbacks(rampStep)
        shutdownTts()
        releaseBoost()
        speechActive = false
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
        restoreAlarmVolume()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun finishRing(snoozeMs: Long?, missed: Boolean) {
        handler.removeCallbacks(timeout)
        val info = _current.value
        stopAlert()
        _current.value = null
        if (info != null && !info.isTest) {
            val app = applicationContext
            Graph.appScope.launch {
                runCatching { bookkeeping(info, snoozeMs) }.onFailure { Log.e(TAG, "bookkeeping failed", it) }
                if (missed) Notifications.postMissed(app, info)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun bookkeeping(info: RingInfo, snoozeMs: Long?) {
        val repo = Graph.repo
        when (info.type) {
            FireType.ALARM -> if (snoozeMs != null) {
                val until = System.currentTimeMillis() + snoozeMs
                repo.updateAlarm(info.id, "貪睡") { it.copy(snoozeUntil = until) }
            }
            FireType.TIMER -> if (snoozeMs != null) {
                repo.snoozeTimer(info.id, snoozeMs)
            } else {
                val dao = Graph.db.timerDao()
                dao.get(info.id)?.let { dao.update(it.copy(state = TimerState.DONE)) }
            }
        }
        Graph.scheduler.rescheduleAll(if (snoozeMs != null) "貪睡" else "停止響鈴")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopAlert()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RingingService"
        private const val TIMEOUT_MS = 10 * 60_000L
        private const val SPEECH_GAP_MS = 1_500L
        private const val TTS_INIT_TIMEOUT_MS = 8_000L
        const val ACTION_START = "com.hoonsor.alarm.RING_START"
        const val ACTION_DISMISS = "com.hoonsor.alarm.RING_DISMISS"
        const val ACTION_SNOOZE = "com.hoonsor.alarm.RING_SNOOZE"
        const val EXTRA_MINUTES = "minutes"

        private val _current = MutableStateFlow<RingInfo?>(null)
        val current: StateFlow<RingInfo?> = _current

        fun startIntent(context: Context, info: RingInfo): Intent =
            info.writeTo(Intent(context, RingingService::class.java).setAction(ACTION_START))

        fun dismissIntent(context: Context): Intent =
            Intent(context, RingingService::class.java).setAction(ACTION_DISMISS)

        fun snoozeIntent(context: Context, minutes: Int): Intent =
            Intent(context, RingingService::class.java).setAction(ACTION_SNOOZE).putExtra(EXTRA_MINUTES, minutes)
    }
}
