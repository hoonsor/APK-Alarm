package com.hoonsor.alarm.ui.settings

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.data.AppSettings
import com.hoonsor.alarm.data.KeyVault
import com.hoonsor.alarm.ui.common.MiniSwitch
import com.hoonsor.alarm.ui.common.textFieldColors
import com.hoonsor.alarm.ui.theme.C
import com.hoonsor.alarm.voice.AiParser
import com.hoonsor.alarm.voice.VoiceIntent
import kotlinx.coroutines.launch

/** 設定頁：AI 語音解析（OpenAI 相容 API，預設 MiniMax M3） */
@Composable
fun AiCard() {
    val s = Graph.settings
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(s.aiEnabled) }
    var savedTail by remember { mutableStateOf(KeyVault.decrypt(s.aiKeyCipher)?.takeLast(4)) }
    var keyInput by remember { mutableStateOf("") }
    var editingKey by remember { mutableStateOf(savedTail == null) }
    var baseUrl by remember { mutableStateOf(s.aiBaseUrl) }
    var model by remember { mutableStateOf(s.aiModel) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface)
            .border(1.dp, C.border, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI 語音解析", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("語音建立時交給 AI 理解，未設定或失敗時自動改用離線解析", color = C.muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            MiniSwitch(enabled, { enabled = !enabled; s.aiEnabled = enabled }, large = true, contentDescription = "啟用 AI 語音解析")
        }

        Text("API key", color = C.muted, fontSize = 13.sp)
        if (!editingKey && savedTail != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已加密儲存（••••$savedTail）", color = C.textSoft, fontSize = 14.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { editingKey = true; keyInput = "" }) { Text("更換", color = C.text) }
                OutlinedButton(onClick = {
                    s.aiKeyCipher = null; savedTail = null; editingKey = true; testResult = null
                }) { Text("刪除", color = C.danger) }
            }
        } else {
            OutlinedTextField(
                value = keyInput, onValueChange = { keyInput = it.trim() }, singleLine = true,
                placeholder = { Text("貼上 MiniMax API key", color = C.dim) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(12.dp), colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = keyInput.length >= 8,
                    onClick = {
                        val cipher = KeyVault.encrypt(keyInput)
                        if (cipher != null) {
                            s.aiKeyCipher = cipher
                            savedTail = keyInput.takeLast(4)
                            keyInput = ""
                            editingKey = false
                            testResult = null
                        } else {
                            testResult = false to "加密儲存失敗，請重試"
                        }
                    },
                ) { Text("儲存", color = C.accentSoft) }
                if (savedTail != null) TextButton(onClick = { editingKey = false; keyInput = "" }) { Text("取消", color = C.muted) }
            }
            Text("到 platform.minimax.io 登入後，在「API Keys」建立。key 以手機 Keystore 加密保存，不會以明文存放。", color = C.dim, fontSize = 12.sp, lineHeight = 18.sp)
        }

        Text("API 網址", color = C.muted, fontSize = 13.sp)
        OutlinedTextField(
            value = baseUrl, onValueChange = { baseUrl = it; s.aiBaseUrl = it }, singleLine = true,
            shape = RoundedCornerShape(12.dp), colors = textFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Text("模型名稱", color = C.muted, fontSize = 13.sp)
        OutlinedTextField(
            value = model, onValueChange = { model = it; s.aiModel = it }, singleLine = true,
            shape = RoundedCornerShape(12.dp), colors = textFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        if (baseUrl != AppSettings.DEFAULT_AI_BASE_URL || model != AppSettings.DEFAULT_AI_MODEL) {
            TextButton(onClick = {
                baseUrl = AppSettings.DEFAULT_AI_BASE_URL; model = AppSettings.DEFAULT_AI_MODEL
                s.aiBaseUrl = baseUrl; s.aiModel = model
            }) { Text("恢復 MiniMax 預設值", color = C.muted) }
        }
        Text("也可以填其他 OpenAI 相容服務（例如 DeepSeek：https://api.deepseek.com、deepseek-chat）。", color = C.dim, fontSize = 12.sp, lineHeight = 18.sp)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                enabled = savedTail != null && !testing,
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        val t0 = SystemClock.elapsedRealtime()
                        testResult = try {
                            val r = AiParser.parse("明天早上七點叫我起床", emptyList(), emptyList())
                            val sec = (SystemClock.elapsedRealtime() - t0) / 100 / 10.0
                            val ok = r !is VoiceIntent.Unknown
                            ok to (if (ok) "連線成功（$sec 秒），測試句解析正常" else "連線成功，但解析失敗：${(r as VoiceIntent.Unknown).reason}")
                        } catch (e: Exception) {
                            false to (e.message ?: e.javaClass.simpleName)
                        }
                        testing = false
                    }
                },
            ) { Text("測試連線", color = C.text) }
            if (testing) CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
        testResult?.let { (ok, msg) ->
            Text(msg, color = if (ok) C.ok else C.warnText, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}
