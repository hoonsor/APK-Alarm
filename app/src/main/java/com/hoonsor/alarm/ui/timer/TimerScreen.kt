package com.hoonsor.alarm.ui.timer

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.hoonsor.alarm.data.TimerEntity
import com.hoonsor.alarm.data.TimerState
import com.hoonsor.alarm.domain.Fmt
import com.hoonsor.alarm.domain.NextTrigger
import com.hoonsor.alarm.scheduler.AlarmScheduler
import com.hoonsor.alarm.ui.common.Glyph
import com.hoonsor.alarm.ui.common.GlyphIcon
import com.hoonsor.alarm.ui.theme.C
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PRESETS_MIN = listOf(1, 3, 5, 10, 15, 25, 45, 60)

@Composable
fun TimerScreen() {
    val context = LocalContext.current
    val timersFlow = remember { Graph.db.timerDao().observeAll() }
    val timers by timersFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val repo = Graph.repo
    val boot = remember { AlarmScheduler.bootCount(context) }

    var nowWall by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowWall = System.currentTimeMillis()
            nowElapsed = SystemClock.elapsedRealtime()
            delay(200)
        }
    }

    var pickedMs by rememberSaveable { mutableLongStateOf(Graph.settings.lastTimerMs) }
    var label by rememberSaveable { mutableStateOf("") }
    var keypad by remember { mutableStateOf(false) }
    val running = timers.count { it.state == TimerState.RUNNING }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.padding(start = 4.dp, top = 20.dp, bottom = 4.dp)) {
            Text("計時器", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.text)
            Text("$running 個進行中 · 時間到會以鬧鐘等級響鈴", fontSize = 14.sp, color = C.muted)
        }

        timers.forEach { t ->
            TimerCard(
                t = t,
                remaining = NextTrigger.timerRemaining(t, nowWall, nowElapsed, boot),
                onPause = { scope.launch { repo.pauseTimer(t.id) } },
                onResume = { scope.launch { repo.resumeTimer(t.id) } },
                onReset = { scope.launch { repo.resetTimer(t.id) } },
                onDelete = { scope.launch { repo.deleteTimer(t.id) } },
            )
        }

        Column(
            Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(20.dp)).background(C.surfaceHi)
                .border(1.dp, C.accent.copy(alpha = 0.45f), RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("新增計時器", color = C.muted, fontSize = 12.sp, letterSpacing = 1.sp)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.field)
                    .clickable(onClickLabel = "自訂時長") { keypad = true }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(Fmt.duration(pickedMs), fontSize = 48.sp, fontWeight = FontWeight.SemiBold, color = C.text)
                Text("點一下輸入任意時間", fontSize = 12.sp, color = C.muted)
            }
            PRESETS_MIN.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { m ->
                        val sel = m * 60_000L == pickedMs
                        Box(
                            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (sel) C.accent else Color.Transparent)
                                .border(1.dp, if (sel) C.accent else C.chipBorder, RoundedCornerShape(12.dp))
                                .clickable { pickedMs = m * 60_000L },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (m == 60) "1 小時" else "$m 分",
                                color = if (sel) C.onAccent else C.textSoft, fontSize = 14.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(-5, -1, 1, 5).forEach { d ->
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp)).background(C.field)
                            .clickable { pickedMs = (pickedMs + d * 60_000L).coerceIn(1_000L, 99 * 3_600_000L) },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (d > 0) "+$d 分" else "$d 分", color = C.muted, fontSize = 13.sp) }
                }
            }
            OutlinedTextField(
                value = label, onValueChange = { label = it }, singleLine = true,
                label = { Text("響鈴時顯示的提醒") },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = C.field, unfocusedContainerColor = C.field,
                    focusedBorderColor = C.accent, unfocusedBorderColor = C.borderHi,
                    focusedTextColor = C.text, unfocusedTextColor = C.text,
                    focusedLabelColor = C.accent, unfocusedLabelColor = C.muted,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val ms = pickedMs
                    val l = label.trim()
                    Graph.settings.lastTimerMs = ms
                    scope.launch { repo.startTimer(ms, l) }
                    label = ""
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent),
            ) {
                GlyphIcon(Glyph.Play, null, tint = C.onAccent, size = 18.dp)
                Text("  開始 ${Fmt.duration(pickedMs)}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (keypad) {
        DurationKeypadDialog(
            initialMs = pickedMs,
            onConfirm = { pickedMs = it; keypad = false },
            onDismiss = { keypad = false },
        )
    }
}

@Composable
private fun TimerCard(
    t: TimerEntity,
    remaining: Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
) {
    val running = t.state == TimerState.RUNNING
    val done = t.state == TimerState.DONE || t.state == TimerState.RINGING
    val progress = if (t.durationMs > 0) (remaining.toFloat() / t.durationMs).coerceIn(0f, 1f) else 0f
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface)
            .border(1.dp, C.border, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                val status = when (t.state) {
                    TimerState.PAUSED -> " · 已暫停"
                    TimerState.DONE -> " · 已完成"
                    TimerState.RINGING -> " · 響鈴中"
                    TimerState.RUNNING -> ""
                }
                Text(t.label.ifBlank { "計時器" } + status, color = C.textSoft, fontSize = 14.sp)
                Text(
                    Fmt.duration(remaining), fontSize = 38.sp, fontWeight = FontWeight.SemiBold,
                    color = if (running) C.text else C.muted,
                )
                Text("共 ${Fmt.duration(t.durationMs)}", color = C.muted, fontSize = 12.sp)
            }
            when {
                running -> RoundBtn(Glyph.Pause, "暫停", filled = true, onClick = onPause)
                done -> RoundBtn(Glyph.Reset, "重設", filled = true, onClick = onReset)
                else -> RoundBtn(Glyph.Play, "繼續", filled = true, onClick = onResume)
            }
            if (running) RoundBtn(Glyph.Reset, "重設", filled = false, onClick = onReset)
            else RoundBtn(Glyph.Trash, "刪除", filled = false, onClick = onDelete, tint = C.danger)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF262C3A))) {
            Box(
                Modifier.fillMaxWidth(progress).height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (running) C.accent else C.nav),
            )
        }
    }
}

@Composable
private fun RoundBtn(glyph: Glyph, label: String, filled: Boolean, onClick: () -> Unit, tint: Color = C.text) {
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .background(if (filled) C.accent else Color.Transparent)
            .border(1.dp, if (filled) C.accent else C.borderHi, CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, label, tint = if (filled) C.onAccent else tint, size = 20.dp) }
}
