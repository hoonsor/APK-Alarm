package com.hoonsor.alarm.ui.stopwatch

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.data.AppSettings
import com.hoonsor.alarm.domain.Fmt
import com.hoonsor.alarm.scheduler.AlarmScheduler
import com.hoonsor.alarm.ui.common.Glyph
import com.hoonsor.alarm.ui.common.GlyphIcon
import com.hoonsor.alarm.ui.theme.C
import kotlinx.coroutines.delay

/**
 * 碼錶狀態存在 SharedPreferences：App 被關閉、手機鎖屏都不影響計時，
 * 重新開機後改用牆上時間換算。
 */
private class StopwatchModel(private val s: AppSettings, private val context: Context) {
    var running by mutableStateOf(s.swRunning)
    var laps by mutableStateOf(s.swLaps)

    fun elapsed(): Long {
        if (!s.swRunning) return s.swAccumulatedMs
        val sameBoot = s.swBootCount == AlarmScheduler.bootCount(context)
        val since = if (sameBoot) SystemClock.elapsedRealtime() - s.swStartElapsed
        else System.currentTimeMillis() - s.swStartWall
        return s.swAccumulatedMs + since.coerceAtLeast(0)
    }

    fun toggle() {
        if (s.swRunning) {
            s.swAccumulatedMs = elapsed()
            s.swRunning = false
        } else {
            s.swStartElapsed = SystemClock.elapsedRealtime()
            s.swStartWall = System.currentTimeMillis()
            s.swBootCount = AlarmScheduler.bootCount(context)
            s.swRunning = true
        }
        running = s.swRunning
    }

    fun lap() {
        if (!s.swRunning) return
        s.swLaps = s.swLaps + elapsed()
        laps = s.swLaps
    }

    fun reset() {
        s.swRunning = false
        s.swAccumulatedMs = 0
        s.swLaps = emptyList()
        running = false
        laps = emptyList()
    }
}

@Composable
fun StopwatchScreen() {
    val context = LocalContext.current
    val model = remember { StopwatchModel(Graph.settings, context.applicationContext) }
    var now by remember { mutableLongStateOf(model.elapsed()) }
    LaunchedEffect(model.running) {
        now = model.elapsed()
        while (model.running) {
            now = model.elapsed()
            delay(33)
        }
    }

    val laps = model.laps
    val splits = laps.mapIndexed { i, total -> total - (if (i == 0) 0L else laps[i - 1]) }
    val currentLap = now - (laps.lastOrNull() ?: 0L)
    val fastest = if (splits.size >= 2) splits.indexOf(splits.min()) else -1
    val slowest = if (splits.size >= 2) splits.indexOf(splits.max()) else -1

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("碼錶", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.text, modifier = Modifier.weight(1f))
            Text("鎖屏時持續計時", fontSize = 13.sp, color = C.muted)
        }

        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            val sweep = ((now % 60_000L) / 60_000f) * 360f
            Canvas(Modifier.size(248.dp)) {
                val stroke = 2.dp.toPx()
                drawCircle(Color(0xFF262C3A), radius = size.minDimension / 2 - stroke, style = Stroke(stroke))
                drawArc(
                    C.accent, startAngle = -90f, sweepAngle = sweep, useCenter = false,
                    topLeft = Offset(stroke, stroke), size = Size(size.width - 2 * stroke, size.height - 2 * stroke),
                    style = Stroke(stroke * 1.5f, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val text = Fmt.stopwatch(now)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text.dropLast(3), fontSize = 54.sp, fontWeight = FontWeight.SemiBold, color = C.text)
                    Text(text.takeLast(3), fontSize = 30.sp, color = C.muted, modifier = Modifier.padding(bottom = 6.dp))
                }
                if (laps.isNotEmpty()) {
                    Text("分段 #${laps.size + 1} · ${Fmt.stopwatch(currentLap)}", fontSize = 14.sp, color = C.muted)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            val canLap = model.running
            val leftLabel = if (canLap || now == 0L) "分段" else "重設"
            BigRound(
                glyph = if (canLap || now == 0L) Glyph.Flag else Glyph.Reset,
                label = leftLabel, primary = false, enabled = now > 0L,
            ) { if (canLap) model.lap() else { model.reset(); now = 0 } }
            Spacer(Modifier.width(28.dp))
            BigRound(
                glyph = if (model.running) Glyph.Pause else Glyph.Play,
                label = if (model.running) "暫停" else if (now > 0) "繼續" else "開始",
                primary = true, enabled = true,
            ) { model.toggle() }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (laps.isNotEmpty()) {
                item {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text("分段", color = C.muted, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                        Text("單圈", color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("累計", color = C.muted, fontSize = 12.sp)
                    }
                    HorizontalDivider(color = C.border)
                }
                item { LapRow(laps.size + 1, currentLap, now, null) }
                itemsIndexed(splits.reversed()) { ri, split ->
                    val i = splits.size - 1 - ri
                    val tag = when (i) {
                        fastest -> "最快"
                        slowest -> "最慢"
                        else -> null
                    }
                    LapRow(i + 1, split, laps[i], tag)
                }
            }
        }
    }
}

@Composable
private fun LapRow(n: Int, split: Long, total: Long, tag: String?) {
    val color = when (tag) {
        "最快" -> C.fast
        "最慢" -> C.danger
        else -> C.text
    }
    Column {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#$n", color = C.muted, modifier = Modifier.width(52.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.stopwatch(split), color = color, fontSize = 17.sp)
                if (tag != null) {
                    Text(
                        tag, color = color, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 6.dp).background(color.copy(alpha = 0.15f), RoundedCornerShape(9.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Text(Fmt.stopwatch(total), color = C.textSoft, fontSize = 17.sp)
        }
        HorizontalDivider(color = Color(0xFF1C2130))
    }
}

@Composable
private fun BigRound(glyph: Glyph, label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Column(
        Modifier.size(76.dp).clip(CircleShape)
            .background(if (primary) C.accent else C.surface)
            .border(1.dp, if (primary) C.accent else C.borderHi, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val tint = (if (primary) C.onAccent else C.text).copy(alpha = alpha)
        GlyphIcon(glyph, null, tint = tint, size = 20.dp)
        Text(label, color = tint, fontSize = 14.sp, fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium)
    }
}
