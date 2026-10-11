package com.hoonsor.alarm.ui.alarms

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoonsor.alarm.data.AlarmEntity
import com.hoonsor.alarm.data.AlarmGroupEntity
import com.hoonsor.alarm.domain.Fmt
import com.hoonsor.alarm.ui.theme.C
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** 放置目標：群組、未分組、新群組 */
sealed interface DropTarget {
    data class Group(val id: Long, val name: String) : DropTarget
    data object Ungrouped : DropTarget
    data object NewGroup : DropTarget
}

/**
 * 多選卡片拖曳到群組。
 * 座標一律使用 compose root 座標（boundsInRoot），卡片位置在版面配置時登記。
 */
class GroupDragController(private val scope: CoroutineScope) {
    var active by mutableStateOf(false)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var ids by mutableStateOf<List<Long>>(emptyList())
        private set
    var hovered by mutableStateOf<DropTarget?>(null)
        private set

    /** 0 → 1：卡片從原位置收合到手指的動畫進度 */
    val progress = Animatable(0f)

    /** 收合動畫的起點（各卡片原本的位置） */
    var startRects: List<Rect> = emptyList()
        private set

    val tileBounds = mutableMapOf<Long, Rect>()
    val targetBounds = mutableMapOf<DropTarget, Rect>()
    private var suppressClickUntil = 0L

    fun start(selected: List<Long>, pointerInRoot: Offset) {
        ids = selected
        startRects = selected.mapNotNull { tileBounds[it] }
        pointer = pointerInRoot
        hovered = null
        active = true
        scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = 300, easing = FastOutSlowInEasing))
        }
    }

    fun move(delta: Offset) {
        pointer += delta
        hovered = targetBounds.entries.firstOrNull { it.value.contains(pointer) }?.key
    }

    /** 放開手指：回傳放置的目標（沒有放在目標上回傳 null） */
    fun end(): DropTarget? {
        val target = hovered
        active = false
        hovered = null
        suppressClickUntil = SystemClock.uptimeMillis() + 400
        return target
    }

    fun cancel() {
        active = false
        hovered = null
        suppressClickUntil = SystemClock.uptimeMillis() + 400
    }

    /** 拖曳結束後短時間內忽略點擊，避免放開時誤觸取消勾選 */
    fun shouldIgnoreClick(): Boolean = SystemClock.uptimeMillis() < suppressClickUntil
}

/** 拖曳時出現在底部的放置區 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DropPanel(groups: List<AlarmGroupEntity>, drag: GroupDragController, modifier: Modifier = Modifier) {
    DisposableEffect(Unit) { onDispose { drag.targetBounds.clear() } }
    val targets = groups.map { DropTarget.Group(it.id, it.name) } + listOf(DropTarget.Ungrouped, DropTarget.NewGroup)
    Column(
        modifier.fillMaxWidth().padding(12.dp)
            .shadow(16.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp)).background(C.surfaceHi)
            .border(1.dp, C.accent.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("拖曳到群組後放開", color = C.muted, fontSize = 12.sp, letterSpacing = 1.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            targets.forEach { t ->
                val label = when (t) {
                    is DropTarget.Group -> t.name
                    DropTarget.Ungrouped -> "未分組"
                    DropTarget.NewGroup -> "＋ 新群組"
                }
                val hot = drag.hovered == t
                Box(
                    Modifier
                        .onGloballyPositioned { drag.targetBounds[t] = it.boundsInRoot() }
                        .graphicsLayer { scaleX = if (hot) 1.08f else 1f; scaleY = if (hot) 1.08f else 1f }
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (hot) C.accent else C.field)
                        .border(1.dp, if (hot) C.accent else C.chipBorder, RoundedCornerShape(18.dp))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, color = if (hot) C.onAccent else C.text, fontSize = 15.sp,
                        fontWeight = if (hot) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 收合成一疊、跟著手指移動的卡片 */
@Composable
fun DragGhosts(drag: GroupDragController, alarms: List<AlarmEntity>, overlayOrigin: Offset) {
    val density = LocalDensity.current
    val ghostW = with(density) { 132.dp.toPx() }
    val ghostH = with(density) { 70.dp.toPx() }
    val lift = with(density) { 72.dp.toPx() }
    val step = with(density) { 6.dp.toPx() }
    val p = drag.progress.value
    val shown = drag.ids.take(3)
    // 由下往上畫，第一張在最上面
    shown.indices.reversed().forEach { i ->
        val alarm = alarms.firstOrNull { it.id == shown[i] } ?: return@forEach
        val start = drag.startRects.getOrNull(i) ?: Rect(drag.pointer, androidx.compose.ui.geometry.Size(ghostW, ghostH))
        val targetCenter = drag.pointer + Offset(i * step, i * step - lift)
        val cx = lerp(start.center.x, targetCenter.x, p)
        val cy = lerp(start.center.y, targetCenter.y, p)
        val w = lerp(start.width, ghostW, p)
        val h = lerp(start.height, ghostH, p)
        val rotation = lerp(0f, listOf(0f, -5f, 5f)[i], p)
        Box(
            Modifier
                .offset { IntOffset((cx - w / 2 - overlayOrigin.x).roundToInt(), (cy - h / 2 - overlayOrigin.y).roundToInt()) }
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .graphicsLayer { rotationZ = rotation }
                .shadow(12.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(C.surfaceHi)
                .border(2.dp, C.accent, RoundedCornerShape(16.dp))
                .padding(10.dp),
        ) {
            Column {
                Text(Fmt.hm(alarm.hour, alarm.minute), color = C.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(alarm.memo.ifBlank { " " }, color = C.muted, fontSize = 11.sp, maxLines = 1)
            }
            if (i == 0 && drag.ids.size > 1) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(26.dp).clip(CircleShape).background(C.accent),
                    contentAlignment = Alignment.Center,
                ) { Text("${drag.ids.size}", color = C.onAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
