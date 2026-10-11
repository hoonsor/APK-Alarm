package com.hoonsor.alarm.ui.health

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.domain.Fmt
import com.hoonsor.alarm.ringing.RingInfo
import com.hoonsor.alarm.ringing.RingTester
import com.hoonsor.alarm.scheduler.FireType
import com.hoonsor.alarm.tts.TtsEngine
import com.hoonsor.alarm.ui.common.Glyph
import com.hoonsor.alarm.ui.common.GlyphIcon
import com.hoonsor.alarm.ui.theme.C
import kotlinx.coroutines.launch

@Composable
fun HealthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val report by produceState<HealthReport?>(null, refresh) { value = HealthChecker.run(context) }
    var testScheduled by remember { mutableStateOf(false) }
    var preparingVoice by remember { mutableStateOf(false) }

    fun open(intent: Intent?) {
        intent ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { GlyphIcon(Glyph.Back, "返回", tint = C.text) }
            Text("響鈴可靠度", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.text)
        }
        val r = report ?: return@Column
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (r.issues > 0) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.warnBg)
                        .border(1.dp, C.warnBorder, RoundedCornerShape(20.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.Warn, null, tint = C.warnText)
                        Text("  ${r.issues} 項需要處理", color = C.warnText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("以下標示為橘色的項目可能讓鬧鐘延遲或不響，請逐一處理。", color = C.warnText.copy(alpha = 0.85f), fontSize = 14.sp)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.ok.copy(alpha = 0.10f)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphIcon(Glyph.CheckCircle, null, tint = C.ok)
                    Text("  全部正常", color = C.ok, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }

            Text("權限與系統設定", color = C.muted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface).border(1.dp, C.border, RoundedCornerShape(20.dp))) {
                r.items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(color = C.border, modifier = Modifier.padding(horizontal = 16.dp))
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (glyph, tint) = when (item.level) {
                                Level.OK -> Glyph.CheckCircle to C.ok
                                Level.WARN -> Glyph.Warn to C.warn
                                Level.INFO -> Glyph.Info to C.muted
                            }
                            GlyphIcon(glyph, null, tint = tint, size = 20.dp)
                            Text("  " + item.title, color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(
                                item.status, fontSize = 13.sp,
                                color = if (item.level == Level.WARN) C.warnText else C.muted,
                                fontWeight = if (item.level == Level.WARN) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                        item.detail?.let { Text(it, color = C.muted, fontSize = 12.sp, lineHeight = 18.sp) }
                        if (item.level != Level.OK && item.fixIntent != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { open(item.fixIntent) }, shape = RoundedCornerShape(12.dp)) {
                                    Text(item.fixLabel ?: "前往設定", color = C.accentSoft)
                                }
                                if (item.title == "廠牌自啟動") {
                                    TextButton(onClick = {
                                        Graph.settings.oemConfirmedAt = System.currentTimeMillis()
                                        refresh++
                                    }) { Text("已確認", color = C.muted) }
                                }
                            }
                        }
                    }
                }
            }

            Text("系統登記比對", color = C.muted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface)
                    .border(1.dp, C.border, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoBox("App 預期下次響鈴", r.expectedNext?.let { Fmt.dateTime(it) } ?: "沒有排程", Modifier.weight(1f))
                    InfoBox(
                        "系統實際登記",
                        r.systemNext?.let { Fmt.dateTime(it) + if (!r.systemNextIsOurs) "\n（其他 App）" else "" } ?: "沒有登記",
                        Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(if (r.consistent) Glyph.Check else Glyph.Warn, null, tint = if (r.consistent) C.ok else C.warn, size = 16.dp)
                    Text(
                        if (r.consistent) "  一致 · 已登記 ${r.scheduledCount} 個項目（各含備援）" else "  不一致，請按下方「立即重新排程」",
                        color = if (r.consistent) C.ok else C.warnText, fontSize = 14.sp,
                    )
                }
                Text(
                    "最近一次全量重排：" + (if (r.lastRescheduleAt > 0) Fmt.dateTime(r.lastRescheduleAt) else "尚未執行") +
                        if (r.lastRescheduleReason.isNotBlank()) "（${r.lastRescheduleReason}）" else "",
                    color = C.muted, fontSize = 13.sp,
                )
                OutlinedButton(
                    onClick = { scope.launch { Graph.scheduler.rescheduleAll("手動重排"); refresh++ } },
                    shape = RoundedCornerShape(12.dp),
                ) { Text("立即重新排程", color = C.text) }
            }

            Text("立即測試", color = C.muted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { RingTester.start(context, testInfo(speak = false)) },
                    modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp),
                ) {
                    GlyphIcon(Glyph.Bell, null, tint = C.text, size = 18.dp)
                    Text("  鈴聲", color = C.text)
                }
                OutlinedButton(
                    enabled = !preparingVoice,
                    onClick = {
                        scope.launch {
                            preparingVoice = true
                            val base = testInfo(speak = true)
                            // 使用 MiniMax 時先產生試聽音檔，與正式響鈴一樣播放本地檔
                            val file = if (Graph.settings.ttsEngine == TtsEngine.ENGINE_MINIMAX) {
                                runCatching { TtsEngine.synthesizeToTemp(base.speakText ?: "") }.getOrNull()?.absolutePath
                            } else null
                            preparingVoice = false
                            RingTester.start(context, base.copy(speakFile = file))
                        }
                    },
                    modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp),
                ) {
                    GlyphIcon(Glyph.Speaker, null, tint = C.text, size = 18.dp)
                    Text(if (preparingVoice) "  產生中…" else "  語音播報", color = C.text)
                }
            }
            Text("「語音播報」會念一次後改播鈴聲；個別鬧鐘可在編輯面板按「測試」，用該鬧鐘的實際設定試響。", color = C.dim, fontSize = 12.sp, lineHeight = 18.sp)

            OutlinedButton(
                onClick = {
                    scope.launch {
                        Graph.repo.startTimer(60_000L, "測試響鈴")
                        testScheduled = true
                        refresh++
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                GlyphIcon(Glyph.Bell, null, tint = C.accentSoft, size = 18.dp)
                Text(if (testScheduled) "  已排定：1 分鐘後響鈴（可鎖屏測試）" else "  測試響鈴：1 分鐘後", color = C.accentSoft, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoBox(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(C.field).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = C.muted, fontSize = 12.sp)
        Text(value, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 立即測試用的響鈴內容（使用設定頁的預設鈴聲與語音） */
private fun testInfo(speak: Boolean): RingInfo {
    val now = java.time.LocalTime.now()
    val memo = if (speak) "這是語音播報測試" else "這是鈴聲測試"
    return RingInfo(
        type = FireType.ALARM,
        id = -1,
        triggerAt = System.currentTimeMillis(),
        title = Fmt.hm(now.hour, now.minute),
        subtitle = "測試",
        memo = memo,
        imagePath = Graph.settings.defaultImagePath,
        soundUri = Graph.settings.alarmSoundUri,
        speakText = if (speak) TtsEngine.speechText(now.hour, now.minute, memo) else null,
        speakOnce = true,
        isTest = true,
    )
}
