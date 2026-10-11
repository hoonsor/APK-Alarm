package com.hoonsor.alarm.ui.settings

import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.sound.SoundPreview
import com.hoonsor.alarm.tts.TtsEngine
import com.hoonsor.alarm.ui.common.MiniSwitch
import com.hoonsor.alarm.ui.common.textFieldColors
import com.hoonsor.alarm.ui.theme.C
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** 設定頁：語音播報（鬧鐘響時念出提醒） */
@Composable
fun TtsCard() {
    val s = Graph.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf(s.ttsEngine) }
    var voice by remember { mutableStateOf(s.ttsVoice) }
    var model by remember { mutableStateOf(s.ttsModel) }
    var rate by remember { mutableFloatStateOf(s.ttsRate) }
    var speakTime by remember { mutableStateOf(s.ttsSpeakTime) }
    var boost by remember { mutableFloatStateOf(s.ttsBoostDb.toFloat()) }
    var dirty by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    // 手機內建語音：用來顯示引擎名稱與試聽
    var systemTts by remember { mutableStateOf<TextToSpeech?>(null) }
    var systemInfo by remember { mutableStateOf("檢查中…") }
    DisposableEffect(Unit) {
        var t: TextToSpeech? = null
        t = TextToSpeech(context.applicationContext) { status ->
            val engineRef = t
            systemInfo = if (status == TextToSpeech.SUCCESS && engineRef != null) {
                val zh = listOf(Locale.TRADITIONAL_CHINESE, Locale.CHINESE).any { engineRef.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
                val name = engineRef.engines.firstOrNull { it.name == engineRef.defaultEngine }?.label ?: engineRef.defaultEngine
                if (zh) "目前引擎：$name（支援中文）" else "目前引擎：$name，尚未安裝中文語音，請到系統語音設定下載"
            } else "這支手機沒有可用的語音引擎，響鈴時會改用鈴聲"
            systemTts = engineRef
        }
        onDispose {
            SoundPreview.stop()
            t?.shutdown()
            // 改了引擎、聲音或語速，離開設定頁時在背景重新產生所有語音
            if (dirty) Graph.appScope.launch { TtsEngine.prepareAll() }
        }
    }

    fun changed() { dirty = true; message = null }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface)
            .border(1.dp, C.border, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("語音播報", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("鬧鐘選擇「語音念出提醒」時使用", color = C.muted, fontSize = 12.sp)

        Text("語音引擎", color = C.muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("手機內建（免費、離線）", engine == TtsEngine.ENGINE_SYSTEM) {
                engine = TtsEngine.ENGINE_SYSTEM; s.ttsEngine = engine; changed()
            }
            Chip("MiniMax 雲端", engine == TtsEngine.ENGINE_MINIMAX) {
                engine = TtsEngine.ENGINE_MINIMAX; s.ttsEngine = engine; changed()
            }
        }

        if (engine == TtsEngine.ENGINE_SYSTEM) {
            Text(systemInfo, color = C.textSoft, fontSize = 13.sp, lineHeight = 19.sp)
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("開啟系統語音設定", color = C.text) }
            Text("建議安裝「Google 語音服務」並下載「中文（台灣）」語音，念起來是台灣口音。", color = C.dim, fontSize = 12.sp, lineHeight = 18.sp)
        } else {
            Text("聲音", color = C.muted, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TtsEngine.MINIMAX_VOICES.forEach { (id, label) ->
                    Chip(label, voice == id) { voice = id; s.ttsVoice = id; changed() }
                }
            }
            OutlinedTextField(
                value = voice, onValueChange = { voice = it; s.ttsVoice = it; changed() }, singleLine = true,
                label = { Text("voice_id（可貼上其他聲音代碼）") },
                shape = RoundedCornerShape(12.dp), colors = textFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it; s.ttsModel = it; changed() }, singleLine = true,
                label = { Text("語音模型") },
                shape = RoundedCornerShape(12.dp), colors = textFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "使用「AI 語音解析」裡的 MiniMax API key。語音只在儲存鬧鐘時產生一次並存在手機，響鈴時不需要網路；產生失敗會改用手機內建語音。",
                color = C.dim, fontSize = 12.sp, lineHeight = 18.sp,
            )
        }

        Text("語速 ${"%.1f".format(rate)}×", color = C.muted, fontSize = 13.sp)
        Slider(
            value = rate, onValueChange = { rate = (it * 10).toInt() / 10f },
            onValueChangeFinished = { s.ttsRate = rate; changed() },
            valueRange = 0.5f..2.0f, steps = 14,
            colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent),
        )
        Text(
            if (boost.toInt() == 0) "語音增幅：關閉" else "語音增幅 +${boost.toInt()} dB",
            color = C.muted, fontSize = 13.sp,
        )
        Slider(
            value = boost, onValueChange = { boost = it.toInt().toFloat() },
            onValueChangeFinished = { s.ttsBoostDb = boost.toInt() },
            valueRange = 0f..15f, steps = 14,
            colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent),
        )
        Text(
            "語音通常比鈴聲小聲；響鈴時語音一律以鬧鐘的全音量播放，再加上這裡的增幅。太大聲可能破音，建議 +6～+10 dB，改了不必重新產生語音。",
            color = C.dim, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("先念出時間", color = C.textSoft, fontSize = 15.sp)
                Text("例如「現在是早上 7 點 15 分。」再念提醒", color = C.dim, fontSize = 12.sp)
            }
            MiniSwitch(speakTime, { speakTime = !speakTime; s.ttsSpeakTime = speakTime; changed() }, contentDescription = "先念出時間")
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                val sample = TtsEngine.speechText(7, 15, "晨跑，記得帶耳機")
                SoundPreview.stop()
                systemTts?.stop()
                if (engine == TtsEngine.ENGINE_SYSTEM) {
                    val t = systemTts
                    if (t == null) {
                        message = false to "語音引擎尚未就緒"
                    } else {
                        // 和響鈴時相同：先轉成音檔，再以鬧鐘音量＋增幅播放
                        t.setLanguage(Locale.TRADITIONAL_CHINESE)
                        t.setSpeechRate(rate)
                        val out = File(context.cacheDir, "tts_preview.wav")
                        busy = true
                        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {
                                scope.launch {
                                    SoundPreview.play(context, out.absolutePath, boost.toInt())
                                    message = true to "試聽中（增幅 +${boost.toInt()} dB）"
                                    busy = false
                                }
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                scope.launch {
                                    // 轉檔失敗就直接念（無增幅）
                                    t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                                    t.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "preview")
                                    busy = false
                                }
                            }
                        })
                        if (t.synthesizeToFile(sample, Bundle(), out, "preview") != TextToSpeech.SUCCESS) {
                            busy = false
                            message = false to "語音轉檔失敗"
                        }
                    }
                } else {
                    busy = true
                    scope.launch {
                        message = try {
                            val f = TtsEngine.synthesizeToTemp(sample)
                            SoundPreview.play(context, f.absolutePath, boost.toInt())
                            true to "試聽中（MiniMax：$voice）"
                        } catch (e: Exception) {
                            false to "產生失敗：${e.message}"
                        }
                        busy = false
                    }
                }
            }) { Text("試聽", color = C.accentSoft) }
            if (engine == TtsEngine.ENGINE_MINIMAX) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val n = TtsEngine.prepareAll()
                        dirty = false
                        message = true to "已重新產生 $n 個鬧鐘的語音"
                        busy = false
                    }
                }) { Text("重新產生所有語音", color = C.text) }
            }
            if (busy) CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
        message?.let { (ok, msg) -> Text(msg, color = if (ok) C.ok else C.warnText, fontSize = 13.sp, lineHeight = 19.sp) }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
            .background(if (selected) C.accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) C.accent.copy(alpha = 0.6f) else C.chipBorder, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) C.accentSoft else C.textSoft, fontSize = 13.sp, maxLines = 1) }
}
