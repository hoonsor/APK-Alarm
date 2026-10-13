package com.hoonsor.alarm.ui.timer

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import com.hoonsor.alarm.ui.common.textFieldColors
import com.hoonsor.alarm.ui.theme.C
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private val ITEM_H = 40.dp
private const val MAX_MS = (99 * 3600L + 59 * 60L + 59) * 1000L

/**
 * 上下滑動的數字滾輪（一次顯示 3 格，中間那格是選取值）。
 * 外部改值（例如按「+5 分」）時會自動捲到新的值。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelPicker(range: IntRange, value: Int, onValue: (Int) -> Unit, unit: String, modifier: Modifier = Modifier) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (value - range.first).coerceAtLeast(0))
    val fling = rememberSnapFlingBehavior(state)
    val itemPx = with(LocalDensity.current) { ITEM_H.toPx() }
    val centered by remember {
        derivedStateOf {
            val idx = state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0
            (range.first + idx).coerceIn(range)
        }
    }
    val currentValue by rememberUpdatedState(value)
    val currentOnValue by rememberUpdatedState(onValue)
    // 停止捲動時回報選取值
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to centered }
            .filter { !it.first }
            .distinctUntilChanged()
            .collect { (_, v) -> if (v != currentValue) currentOnValue(v) }
    }
    // 外部改值時捲過去
    LaunchedEffect(value) {
        if (!state.isScrollInProgress && centered != value) state.animateScrollToItem((value - range.first).coerceAtLeast(0))
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(ITEM_H * 3), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxWidth().height(ITEM_H).clip(RoundedCornerShape(12.dp))
                    .background(C.accent.copy(alpha = 0.14f)).border(1.dp, C.accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
            )
            LazyColumn(
                state = state,
                flingBehavior = fling,
                contentPadding = PaddingValues(vertical = ITEM_H),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().height(ITEM_H * 3),
            ) {
                items(range.count()) { i ->
                    val v = range.first + i
                    Box(Modifier.height(ITEM_H).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "%02d".format(v),
                            color = if (v == centered) C.text else C.dim,
                            fontSize = if (v == centered) 26.sp else 20.sp,
                            fontWeight = if (v == centered) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        Text(unit, color = C.muted, fontSize = 12.sp)
    }
}

/** 時／分／秒三個滾輪 */
@Composable
fun DurationWheels(ms: Long, onChange: (Long) -> Unit) {
    val total = (ms / 1000).coerceIn(0, MAX_MS / 1000)
    val h = (total / 3600).toInt()
    val m = ((total % 3600) / 60).toInt()
    val s = (total % 60).toInt()
    fun emit(nh: Int, nm: Int, ns: Int) = onChange((nh * 3600L + nm * 60L + ns) * 1000L)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WheelPicker(0..99, h, { emit(it, m, s) }, "時", Modifier.weight(1f))
        WheelPicker(0..59, m, { emit(h, it, s) }, "分", Modifier.weight(1f))
        WheelPicker(0..59, s, { emit(h, m, it) }, "秒", Modifier.weight(1f))
    }
}

/** 快速增加時間：+1 / +5 / +10 / +30 分、+1 時，以及自訂分鐘 */
@Composable
fun AddTimeRow(ms: Long, onChange: (Long) -> Unit) {
    var custom by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("+1 分" to 1, "+5 分" to 5, "+10 分" to 10, "+30 分" to 30, "+1 時" to 60).forEach { (label, min) ->
                Box(
                    Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp)).background(C.field)
                        .clickable { onChange((ms + min * 60_000L).coerceAtMost(MAX_MS)) },
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = C.textSoft, fontSize = 12.sp, maxLines = 1) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("增加", color = C.muted, fontSize = 13.sp)
            OutlinedTextField(
                value = custom, onValueChange = { v -> custom = v.filter { it.isDigit() }.take(4) }, singleLine = true,
                placeholder = { Text("分鐘", color = C.dim) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(10.dp), colors = textFieldColors(), modifier = Modifier.width(96.dp),
            )
            Text("分鐘", color = C.muted, fontSize = 13.sp)
            Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).background(if (custom.isNotEmpty()) C.accent else C.field)
                    .clickable(enabled = custom.isNotEmpty()) {
                        onChange((ms + (custom.toLongOrNull() ?: 0) * 60_000L).coerceAtMost(MAX_MS)); custom = ""
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("加上", color = if (custom.isNotEmpty()) C.onAccent else C.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * 響鈴時刻提示。
 * fixedAt = null：「現在開始的話 14:35:20 響」，以現在時間＋時長計算（還沒開始的計時器，晚按開始就晚響，所以會跟著時間走）。
 * fixedAt ≠ null：執行中的計時器，直接顯示固定的響鈴時刻，不會隨畫面停留時間漂移。
 */
@Composable
fun RingAtLine(ms: Long, prefix: String = "現在開始的話", fixedAt: Long? = null) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(Glyph.Bell, null, tint = C.accentSoft, size = 14.dp)
        Text(
            if (ms > 0) "  $prefix ${ringAt(fixedAt ?: (now + ms), now)} 響鈴" else "  請設定時間",
            color = C.accentSoft, fontSize = 14.sp,
        )
    }
}

/**
 * 點計時器卡片：修改剩下的時間與提醒。
 * 「重設」一律回到最初建立時的時長，不受這裡的修改影響。
 */
@Composable
fun TimerEditDialog(timer: TimerEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val boot = remember { AlarmScheduler.bootCount(context) }
    // 打開視窗那一刻的牆上時間與剩餘時間（精確到毫秒，不做進位）
    val openedAt = remember(timer.id) { System.currentTimeMillis() }
    val initial = remember(timer.id) {
        NextTrigger.timerRemaining(timer, openedAt, SystemClock.elapsedRealtime(), boot)
            .let { if (it <= 0) timer.durationMs else it }
    }
    var ms by remember(timer.id) { mutableLongStateOf(initial) }
    var label by remember(timer.id) { mutableStateOf(timer.label) }
    val running = timer.state == TimerState.RUNNING
    // 執行中：響鈴時刻＝原本的響鈴時刻＋使用者增減的量；視窗開著的這段時間計時照樣在跑，不會被算進去
    val runningRingAt = if (running) openedAt + ms else null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.surfaceHi,
        title = { Text("編輯計時器", color = C.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (running) "調整剩下的時間（計時繼續進行）" else "調整剩下的時間",
                    color = C.muted, fontSize = 12.sp,
                )
                DurationWheels(ms) { ms = it }
                AddTimeRow(ms) { ms = it }
                RingAtLine(ms, if (running) "改完後" else "按開始的話", fixedAt = runningRingAt)
                OutlinedTextField(
                    value = label, onValueChange = { label = it }, singleLine = true,
                    label = { Text("響鈴時顯示的提醒") },
                    shape = RoundedCornerShape(12.dp), colors = textFieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                Text("「重設」會回到最初建立的 ${Fmt.duration(timer.durationMs)}", color = C.dim, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = ms > 0, onClick = {
                val m = ms; val l = label.trim()
                onDismiss()
                scope.launch {
                    if (running) Graph.repo.shiftRunningTimer(timer.id, m - initial, l)
                    else Graph.repo.editTimer(timer.id, m, l, start = true)
                }
            }) { Text(if (running) "儲存" else "儲存並開始", color = if (ms > 0) C.accent else C.dim, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                if (!running) {
                    TextButton(enabled = ms > 0, onClick = {
                        val m = ms; val l = label.trim()
                        onDismiss()
                        scope.launch { Graph.repo.editTimer(timer.id, m, l, start = false) }
                    }) { Text("只儲存", color = C.textSoft) }
                }
                TextButton(onClick = onDismiss) { Text("取消", color = Color(0xFF9AA3B5)) }
            }
        },
    )
}
