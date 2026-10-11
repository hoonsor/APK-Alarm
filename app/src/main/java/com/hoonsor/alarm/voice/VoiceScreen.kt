package com.hoonsor.alarm.voice

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.data.AlarmEntity
import com.hoonsor.alarm.data.AlarmGroupEntity
import com.hoonsor.alarm.domain.Days
import com.hoonsor.alarm.domain.Fmt
import com.hoonsor.alarm.ui.common.DayChip
import com.hoonsor.alarm.ui.common.Glyph
import com.hoonsor.alarm.ui.common.GlyphIcon
import com.hoonsor.alarm.ui.common.textFieldColors
import com.hoonsor.alarm.ui.theme.C
import kotlinx.coroutines.launch
import java.time.ZoneId

private enum class Phase { LISTENING, THINKING, RESULT, ERROR }

/**
 * 語音建立：Android 語音辨識 → AI（MiniMax M3）解析，失敗或未設定時改用離線規則解析
 * → 確認卡片 → 寫入。辨識不準時可以直接修改文字重新解析。
 */
@Composable
fun VoiceScreen(onClose: () -> Unit, onCreated: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.LISTENING) }
    var partial by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<VoiceIntent?>(null) }
    var source by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var alarms by remember { mutableStateOf<List<AlarmEntity>>(emptyList()) }
    var groups by remember { mutableStateOf<List<AlarmGroupEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        alarms = Graph.db.alarmDao().getAll()
        groups = Graph.db.groupDao().getAll()
    }

    fun analyze(text: String) {
        transcript = text
        if (text.isBlank()) {
            phase = Phase.ERROR; error = "沒有內容可以解析"; return
        }
        if (!AiParser.isConfigured()) {
            result = VoiceParser.parse(text, groups = groups.map { it.id to it.name })
            source = "離線解析（未設定 AI，可在設定頁填入 MiniMax API key）"
            phase = Phase.RESULT
            return
        }
        phase = Phase.THINKING
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val latestAlarms = Graph.db.alarmDao().getAll().also { alarms = it }
            val latestGroups = Graph.db.groupDao().getAll().also { groups = it }
            val latestTimerGroups = Graph.db.timerGroupDao().getAll()
            try {
                result = AiParser.parse(text, latestAlarms, latestGroups, latestTimerGroups)
                val sec = (SystemClock.elapsedRealtime() - t0) / 100 / 10.0
                source = "${Graph.settings.aiModel} 解析完成 · $sec 秒"
            } catch (e: Exception) {
                result = VoiceParser.parse(text, groups = latestGroups.map { it.id to it.name })
                source = "AI 無法使用，已改用離線解析（${e.message ?: e.javaClass.simpleName}）"
            }
            phase = Phase.RESULT
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) attempt++ else {
            phase = Phase.ERROR
            error = "需要麥克風權限才能語音建立（也可以直接在下方輸入文字）"
        }
    }

    // 系統語音輸入畫面（Google 的語音輸入對話框）：所有辨識服務都失敗時的最後備援
    val systemVoiceInput = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) analyze(text)
        else if (phase == Phase.LISTENING) {
            phase = Phase.ERROR
            error = "沒有收到語音內容，可以再試一次，或直接在下方輸入文字"
        }
    }

    fun launchSystemVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "說出鬧鐘內容")
        try {
            phase = Phase.LISTENING
            systemVoiceInput.launch(intent)
        } catch (e: Exception) {
            phase = Phase.ERROR
            error = "找不到系統語音輸入，請安裝或更新「Google」App，或直接在下方輸入文字"
        }
    }

    /**
     * 辨識嘗試順序：Google App 的辨識服務 → Google 語音服務 → 手機預設服務，
     * 每個服務依序試 zh-TW、cmn-Hant-TW、手機預設語言。
     * 遇到「不支援此語言」（代碼 12/13）或服務錯誤就自動換下一組，全部失敗才開系統語音輸入畫面。
     */
    val attempts = remember {
        val services = context.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        val preferred = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts")
            .flatMap { pkg -> services.filter { it.packageName == pkg } }
        val componentOrder: List<ComponentName?> = preferred + listOf(null)
        componentOrder.flatMap { c -> listOf<String?>("zh-TW", "cmn-Hant-TW", null).map { lang -> c to lang } }
    }
    var attemptIndex by remember { mutableIntStateOf(0) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }

    fun startRecognition(index: Int) {
        recognizer?.destroy()
        recognizer = null
        if (index >= attempts.size || !SpeechRecognizer.isRecognitionAvailable(context)) {
            launchSystemVoiceInput()
            return
        }
        attemptIndex = index
        val (component, lang) = attempts[index]
        val r = runCatching {
            if (component != null) SpeechRecognizer.createSpeechRecognizer(context, component)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull()
        if (r == null) {
            startRecognition(index + 1)
            return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: partial
            }
            override fun onResults(results: Bundle?) {
                analyze(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: partial)
            }
            override fun onError(code: Int) {
                if (phase != Phase.LISTENING) return
                when (code) {
                    // 12 不支援此語言、13 語言暫不可用、5 用戶端錯誤、11 服務中斷、10 請求太多：換下一組
                    12, 13, SpeechRecognizer.ERROR_CLIENT, 11, 10 -> startRecognition(index + 1)
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        phase = Phase.ERROR
                        error = "沒有聽清楚，請再說一次，或直接在下方輸入文字"
                    }
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> startRecognition(index + 1)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        phase = Phase.ERROR
                        error = "需要麥克風權限"
                    }
                    else -> startRecognition(index + 1)
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (lang != null) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        runCatching { r.startListening(intent) }.onFailure { startRecognition(index + 1) }
    }

    LaunchedEffect(attempt) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return@LaunchedEffect
        }
        phase = Phase.LISTENING
        partial = ""
        result = null
        // 上次成功的組合優先，省去重試時間
        startRecognition(if (attempt == 0) 0 else attemptIndex.coerceAtMost(attempts.size))
    }

    fun runIntent(intent: VoiceIntent) {
        scope.launch {
            val msg = execute(intent, alarms)
            if (msg != null) onCreated(msg)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { recognizer?.cancel(); onClose() }) { GlyphIcon(Glyph.Close, "取消並返回", tint = C.text) }
            Text("語音建立", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.text)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 你說的內容（辨識完成後可直接修改再重新解析）
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface)
                    .border(1.dp, C.border, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Mic, null, tint = if (phase == Phase.LISTENING) C.accent else C.muted, size = 14.dp)
                    Text(if (phase == Phase.LISTENING) "  聆聽中…" else "  你說（可直接修改）", color = C.muted, fontSize = 12.sp)
                }
                if (phase == Phase.LISTENING) {
                    Text("「${partial.ifBlank { "請說出鬧鐘內容" }}」", color = C.text, fontSize = 19.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp)
                } else {
                    var edit by remember(transcript) { mutableStateOf(transcript) }
                    OutlinedTextField(
                        value = edit, onValueChange = { edit = it },
                        placeholder = { Text("例如：每週一三五早上七點十五叫我晨跑", color = C.dim) },
                        shape = RoundedCornerShape(12.dp), colors = textFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { attempt++ }, shape = RoundedCornerShape(18.dp)) { Text("重新說一次", color = C.textSoft) }
                        if (edit != transcript || phase == Phase.ERROR) {
                            OutlinedButton(
                                onClick = { analyze(edit) }, enabled = phase != Phase.THINKING && edit.isNotBlank(),
                                shape = RoundedCornerShape(18.dp),
                            ) { Text("用這段文字解析", color = C.accentSoft) }
                        }
                    }
                }
            }

            when (phase) {
                Phase.THINKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Text("  ${Graph.settings.aiModel} 解析中…", color = C.muted, fontSize = 14.sp)
                }
                Phase.ERROR -> {
                    Text(error, color = C.warnText, fontSize = 15.sp, lineHeight = 22.sp)
                    OutlinedButton(onClick = { launchSystemVoiceInput() }, shape = RoundedCornerShape(18.dp)) {
                        Text("改用系統語音輸入畫面", color = C.accentSoft)
                    }
                }
                Phase.RESULT -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.Sparkle, null, tint = if (source.startsWith("AI 無法")) C.warn else C.ok, size = 14.dp)
                        Text("  $source", color = if (source.startsWith("AI 無法")) C.warnText else C.ok, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    when (val r = result) {
                        is VoiceIntent.Ambiguous -> {
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.warnBg)
                                    .border(1.dp, C.warnBorder, RoundedCornerShape(20.dp)).padding(16.dp),
                            ) { Text(r.question, color = C.warnText, fontSize = 15.sp, lineHeight = 22.sp) }
                            r.options.forEach { (label, opt) ->
                                Column(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surfaceHi)
                                        .border(1.dp, C.accent.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                        .clickable { runIntent(opt) }.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(label, color = C.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                    Text(describe(opt, alarms, groups), color = C.muted, fontSize = 13.sp)
                                }
                            }
                        }
                        is VoiceIntent.Unknown -> Text(r.reason, color = C.warnText, fontSize = 15.sp)
                        is VoiceIntent.Alarm, is VoiceIntent.Timer, is VoiceIntent.Update, is VoiceIntent.Batch,
                        is VoiceIntent.CreateGroup, is VoiceIntent.MoveAlarms, is VoiceIntent.GroupSwitch ->
                            Proposal(r, alarms, groups)
                        null -> Unit
                    }
                }
                Phase.LISTENING -> Text(
                    "可以這樣說：\n「每週一三五早上七點十五叫我晨跑」\n「平日六點半」\n「二十五分鐘後提醒我起來走動」\n「把晨跑那個改成六點半」\n「新增鬧鐘群組叫上班，平日七點叫我出門」\n「關閉上班群組」",
                    color = C.muted, fontSize = 14.sp, lineHeight = 24.sp,
                )
            }
        }

        val r = result
        val confirmable = phase == Phase.RESULT && r != null && r !is VoiceIntent.Ambiguous && r !is VoiceIntent.Unknown
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { recognizer?.cancel(); onClose() },
                modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp),
            ) { Text("取消", color = C.textSoft, fontSize = 16.sp) }
            if (confirmable && r != null) {
                Button(
                    onClick = { runIntent(r) },
                    modifier = Modifier.weight(2f).height(54.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent),
                ) {
                    val label = when (r) {
                        is VoiceIntent.Update, is VoiceIntent.MoveAlarms, is VoiceIntent.GroupSwitch -> "確認修改"
                        else -> "確認建立"
                    }
                    Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 執行解析結果，回傳給使用者看的摘要 */
private suspend fun execute(intent: VoiceIntent, alarms: List<AlarmEntity>): String? {
    val repo = Graph.repo
    return when (intent) {
        is VoiceIntent.Alarm -> {
            val notBefore = intent.notBeforeDate?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            val groupId = intent.groupId ?: intent.groupName?.takeIf { it.isNotBlank() }?.let { repo.findOrCreateGroup(it) }
            repo.addAlarm(
                AlarmEntity(
                    hour = intent.hour, minute = intent.minute, daysMask = intent.daysMask,
                    memo = intent.memo, notBefore = notBefore, groupId = groupId,
                ),
            )
            "已建立 ${Fmt.hm(intent.hour, intent.minute)} 鬧鐘（${Days.summary(intent.daysMask)}）"
        }
        is VoiceIntent.Timer -> {
            val groupId = intent.groupName?.takeIf { it.isNotBlank() }?.let { repo.findOrCreateTimerGroup(it) }
            repo.startTimer(intent.durationMs, intent.memo, groupId)
            "已開始 ${Fmt.duration(intent.durationMs)} 計時"
        }
        is VoiceIntent.CreateGroup -> {
            if (intent.forTimer) repo.findOrCreateTimerGroup(intent.name) else repo.findOrCreateGroup(intent.name)
            "已建立${if (intent.forTimer) "計時器" else "鬧鐘"}群組「${intent.name}」"
        }
        is VoiceIntent.MoveAlarms -> {
            val groupId = intent.groupId ?: intent.groupName?.takeIf { it.isNotBlank() }?.let { repo.findOrCreateGroup(it) }
            repo.moveToGroup(intent.alarmIds, groupId)
            "已移動 ${intent.alarmIds.size} 個鬧鐘"
        }
        is VoiceIntent.GroupSwitch -> {
            repo.setGroupEnabled(intent.groupId, intent.enabled)
            if (intent.enabled) "已開啟群組" else "已關閉群組"
        }
        is VoiceIntent.Update -> {
            repo.updateAlarm(intent.alarmId, "語音修改") { a ->
                a.copy(
                    hour = intent.hour ?: a.hour,
                    minute = intent.minute ?: a.minute,
                    daysMask = intent.daysMask ?: a.daysMask,
                    memo = intent.memo ?: a.memo,
                    enabled = intent.enabled ?: if (intent.hour != null || intent.minute != null || intent.daysMask != null) true else a.enabled,
                )
            }
            "已修改鬧鐘：" + describe(intent, alarms)
        }
        is VoiceIntent.Batch -> {
            intent.actions.forEach { execute(it, alarms) }
            "已完成 ${intent.actions.size} 個動作"
        }
        else -> null
    }
}

/** 一句話描述動作，用在選項卡片與完成提示 */
/** 群組顯示名稱；名稱指定但尚未存在時標示「新」 */
private fun groupLabel(groupId: Long?, groupName: String?, groups: List<AlarmGroupEntity>): String? {
    groupId?.let { id -> groups.firstOrNull { it.id == id }?.let { return it.name } }
    val n = groupName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (groups.any { it.name.trim().equals(n, ignoreCase = true) }) n else "$n（新）"
}

private fun describe(intent: VoiceIntent, alarms: List<AlarmEntity>, groups: List<AlarmGroupEntity> = emptyList()): String = when (intent) {
    is VoiceIntent.Alarm -> buildString {
        append("鬧鐘 ${Fmt.hm(intent.hour, intent.minute)} · ${Days.summary(intent.daysMask)}")
        intent.notBeforeDate?.let { append(" · ${it.monthValue}/${it.dayOfMonth}") }
        if (intent.memo.isNotBlank()) append(" · ${intent.memo}")
        groupLabel(intent.groupId, intent.groupName, groups)?.let { append(" · 群組「$it」") }
    }
    is VoiceIntent.Timer -> "計時 ${Fmt.duration(intent.durationMs)}" + (if (intent.memo.isNotBlank()) " · ${intent.memo}" else "") +
        (intent.groupName?.let { " · 群組「$it」" } ?: "")
    is VoiceIntent.CreateGroup -> "建立${if (intent.forTimer) "計時器" else "鬧鐘"}群組「${intent.name}」"
    is VoiceIntent.MoveAlarms -> {
        val names = intent.alarmIds.mapNotNull { id -> alarms.firstOrNull { it.id == id } }
            .joinToString("、") { Fmt.hm(it.hour, it.minute) + if (it.memo.isNotBlank()) " ${it.memo}" else "" }
        val target = groupLabel(intent.groupId, intent.groupName, groups)?.let { "群組「$it」" } ?: "未分組"
        "把 $names 移到$target"
    }
    is VoiceIntent.GroupSwitch -> {
        val name = groups.firstOrNull { it.id == intent.groupId }?.name ?: "群組"
        (if (intent.enabled) "開啟" else "關閉") + "群組「$name」" + if (intent.enabled) "（各鬧鐘恢復原本開關）" else "（群組內鬧鐘暫停）"
    }
    is VoiceIntent.Update -> {
        val a = alarms.firstOrNull { it.id == intent.alarmId }
        val before = a?.let { Fmt.hm(it.hour, it.minute) + if (it.memo.isNotBlank()) " ${it.memo}" else "" } ?: "鬧鐘"
        val changes = buildList {
            if (intent.hour != null || intent.minute != null) {
                add("時間 → " + Fmt.hm(intent.hour ?: a?.hour ?: 0, intent.minute ?: a?.minute ?: 0))
            }
            intent.daysMask?.let { add("重複 → " + Days.summary(it)) }
            intent.memo?.let { add("提醒 → $it") }
            intent.enabled?.let { add(if (it) "啟用" else "停用") }
        }
        "$before：" + changes.ifEmpty { listOf("沒有變更") }.joinToString("、")
    }
    is VoiceIntent.Batch -> intent.actions.joinToString("；") { describe(it, alarms, groups) }
    is VoiceIntent.Ambiguous -> intent.question
    is VoiceIntent.Unknown -> intent.reason
}

@Composable
private fun Proposal(r: VoiceIntent, alarms: List<AlarmEntity>, groups: List<AlarmGroupEntity>) {
    when (r) {
        is VoiceIntent.Alarm -> AlarmProposal(r, groups)
        is VoiceIntent.Timer -> TimerProposal(r)
        is VoiceIntent.Update -> UpdateProposal(r, alarms)
        is VoiceIntent.CreateGroup, is VoiceIntent.MoveAlarms, is VoiceIntent.GroupSwitch ->
            SimpleProposal(
                when (r) {
                    is VoiceIntent.CreateGroup -> "建立群組"
                    is VoiceIntent.GroupSwitch -> "群組開關"
                    else -> "移動群組"
                },
                describe(r, alarms, groups),
            )
        is VoiceIntent.Batch -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("共 ${r.actions.size} 個動作", color = C.muted, fontSize = 13.sp)
            r.actions.forEach { Proposal(it, alarms, groups) }
        }
        else -> Unit
    }
}

@Composable
private fun AlarmProposal(r: VoiceIntent.Alarm, groups: List<AlarmGroupEntity>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.surfaceHi)
            .border(1.dp, C.accent.copy(alpha = 0.55f), RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val groupName = groupLabel(r.groupId, r.groupName, groups)
        Text("新增鬧鐘" + (groupName?.let { " · 群組「$it」" } ?: ""), color = C.accentSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Fmt.hm(r.hour, r.minute), color = C.text, fontSize = 56.sp, fontWeight = FontWeight.SemiBold)
            val day = r.notBeforeDate?.let { " · ${it.monthValue}/${it.dayOfMonth}" } ?: ""
            Text("  " + Days.summary(r.daysMask) + day, color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Days.LABELS.forEachIndexed { i, l -> DayChip(l, Days.has(r.daysMask, i), size = 40.dp, onClick = null) }
        }
        if (r.memo.isNotBlank()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.field).padding(12.dp)) {
                Text("響鈴時顯示的提醒", color = C.muted, fontSize = 12.sp)
                Text(r.memo, color = C.text, fontSize = 16.sp)
            }
        }
        Text("建立後可在鬧鐘總覽點開修改", color = C.nav, fontSize = 12.sp)
    }
}

@Composable
private fun TimerProposal(r: VoiceIntent.Timer) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.surfaceHi)
            .border(1.dp, C.accent.copy(alpha = 0.55f), RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("開始計時" + (r.groupName?.let { " · 群組「$it」" } ?: ""), color = C.accentSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(Fmt.duration(r.durationMs), color = C.text, fontSize = 56.sp, fontWeight = FontWeight.SemiBold)
        if (r.memo.isNotBlank()) Text("提醒：${r.memo}", color = C.textSoft, fontSize = 16.sp)
    }
}

@Composable
private fun UpdateProposal(r: VoiceIntent.Update, alarms: List<AlarmEntity>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.surfaceHi)
            .border(1.dp, C.accent.copy(alpha = 0.55f), RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("修改鬧鐘", color = C.accentSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(describe(r, alarms), color = C.text, fontSize = 17.sp, lineHeight = 26.sp)
    }
}

@Composable
private fun SimpleProposal(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.surfaceHi)
            .border(1.dp, C.accent.copy(alpha = 0.55f), RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = C.accentSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = C.text, fontSize = 17.sp, lineHeight = 26.sp)
    }
}
