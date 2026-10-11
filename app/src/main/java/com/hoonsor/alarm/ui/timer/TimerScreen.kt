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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.data.TimerEntity
import com.hoonsor.alarm.data.TimerGroupEntity
import com.hoonsor.alarm.data.TimerState
import com.hoonsor.alarm.domain.Fmt
import com.hoonsor.alarm.domain.NextTrigger
import com.hoonsor.alarm.scheduler.AlarmScheduler
import com.hoonsor.alarm.ui.common.Glyph
import com.hoonsor.alarm.ui.common.GlyphIcon
import com.hoonsor.alarm.ui.common.ChoiceDialog
import com.hoonsor.alarm.ui.common.ChoiceItem
import com.hoonsor.alarm.ui.common.MiniSwitch
import com.hoonsor.alarm.ui.common.NameDialog
import com.hoonsor.alarm.ui.theme.C
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PRESETS_MIN = listOf(1, 3, 5, 10, 15, 25, 45, 60)

@Composable
fun TimerScreen() {
    val context = LocalContext.current
    val timersFlow = remember { Graph.db.timerDao().observeAll() }
    val timers by timersFlow.collectAsState(initial = emptyList())
    val groupsFlow = remember { Graph.db.timerGroupDao().observeAll() }
    val groups by groupsFlow.collectAsState(initial = emptyList())
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
    /** 新計時器要放的群組；-1 = 未分組 */
    var newGroupId by rememberSaveable { mutableLongStateOf(-1L) }
    var renaming by remember { mutableStateOf<TimerGroupEntity?>(null) }
    var deleting by remember { mutableStateOf<TimerGroupEntity?>(null) }
    var moving by remember { mutableStateOf<TimerEntity?>(null) }
    /** 新增群組對話框；selectForNew = true 時建立後自動選為新計時器的群組 */
    var newGroupDialog by remember { mutableStateOf<Boolean?>(null) }
    val groupIds = groups.map { it.id }.toSet()
    val ungrouped = timers.filter { it.groupId == null || it.groupId !in groupIds }
    LaunchedEffect(groupIds) { if (newGroupId != -1L && newGroupId !in groupIds) newGroupId = -1L }

    @Composable
    fun card(t: TimerEntity, groupOff: Boolean) = TimerCard(
        t = t,
        remaining = NextTrigger.timerRemaining(t, nowWall, nowElapsed, boot),
        groupOff = groupOff,
        onPause = { scope.launch { repo.pauseTimer(t.id) } },
        onResume = { scope.launch { repo.resumeTimer(t.id) } },
        onReset = { scope.launch { repo.resetTimer(t.id) } },
        onDelete = { scope.launch { repo.deleteTimer(t.id) } },
        onMove = { moving = t },
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.padding(start = 4.dp, top = 20.dp, bottom = 4.dp)) {
            Text("計時器", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.text)
            Text("$running 個進行中 · 時間到會以鬧鐘等級響鈴", fontSize = 14.sp, color = C.muted)
        }

        groups.forEach { g ->
            val list = timers.filter { it.groupId == g.id }
            TimerGroupHeader(
                group = g, timers = list,
                onToggleExpanded = { scope.launch { repo.setTimerGroupExpanded(g.id, !g.expanded) } },
                onToggleEnabled = { scope.launch { repo.setTimerGroupEnabled(g.id, !g.enabled) } },
                onRename = { renaming = g },
                onDelete = { deleting = g },
            )
            if (g.expanded) {
                if (list.isEmpty()) {
                    Text(
                        "這個群組還沒有計時器。新增計時器時選擇這個群組，或點計時器上的資料夾圖示移過來。",
                        color = C.dim, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                list.forEach { card(it, groupOff = !g.enabled) }
            }
        }
        if (groups.isNotEmpty() && ungrouped.isNotEmpty()) {
            Text(
                "未分組 · ${ungrouped.size}", color = C.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp, top = 8.dp),
            )
        }
        ungrouped.forEach { card(it, groupOff = false) }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { newGroupDialog = false }.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.Folder, null, tint = C.muted, size = 18.dp)
            Text("  新增計時器群組", color = C.muted, fontSize = 14.sp)
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
                Text(hms(pickedMs), fontSize = 48.sp, fontWeight = FontWeight.SemiBold, color = C.text)
                Text("時 : 分 : 秒 · 點一下輸入任意時間", fontSize = 12.sp, color = C.muted)
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
                listOf(-60, -5, -1, 1, 5, 60).forEach { d ->
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp)).background(C.field)
                            .clickable { pickedMs = (pickedMs + d * 60_000L).coerceIn(1_000L, 99 * 3_600_000L) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val label = when (d) { 60 -> "+1 時"; -60 -> "-1 時"; else -> if (d > 0) "+$d 分" else "$d 分" }
                        Text(label, color = C.muted, fontSize = 12.sp, maxLines = 1)
                    }
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
            Text("群組", color = C.muted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickChip("未分組", newGroupId == -1L) { newGroupId = -1L }
                groups.forEach { g -> PickChip(g.name, newGroupId == g.id) { newGroupId = g.id } }
                PickChip("＋ 新群組", false) { newGroupDialog = true }
            }
            Button(
                onClick = {
                    val ms = pickedMs
                    val l = label.trim()
                    val gid = newGroupId.takeIf { it != -1L }
                    Graph.settings.lastTimerMs = ms
                    scope.launch { repo.startTimer(ms, l, gid) }
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

    newGroupDialog?.let { selectForNew ->
        NameDialog(
            title = "新增計時器群組", initial = "", confirmLabel = "建立",
            onConfirm = { name ->
                newGroupDialog = null
                scope.launch {
                    val id = repo.addTimerGroup(name)
                    if (selectForNew) newGroupId = id
                }
            },
            onDismiss = { newGroupDialog = null },
        )
    }
    renaming?.let { g ->
        NameDialog(
            title = "重新命名群組", initial = g.name,
            onConfirm = { name -> renaming = null; scope.launch { repo.renameTimerGroup(g.id, name) } },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { g ->
        val count = timers.count { it.groupId == g.id }
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = C.surfaceHi,
            title = { Text("刪除群組「${g.name}」？", color = C.text) },
            text = { Text(if (count > 0) "群組內的 $count 個計時器不會被刪除，會移到「未分組」。" else "這個群組沒有計時器。", color = C.textSoft) },
            confirmButton = {
                TextButton(onClick = { deleting = null; scope.launch { repo.deleteTimerGroup(g.id) } }) { Text("刪除群組", color = C.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消", color = C.muted) } },
        )
    }
    moving?.let { t ->
        val current = t.groupId?.takeIf { it in groupIds }
        ChoiceDialog(
            title = "移到群組",
            items = listOf(ChoiceItem("未分組", selected = current == null) { moving = null; scope.launch { repo.moveTimerToGroup(t.id, null) } }) +
                groups.map { g ->
                    ChoiceItem(g.name, detail = if (g.enabled) null else "已關閉", selected = current == g.id) {
                        moving = null; scope.launch { repo.moveTimerToGroup(t.id, g.id) }
                    }
                },
            onDismiss = { moving = null },
        )
    }
}

/** 新增計時器的大字顯示：一律顯示時:分:秒，讓小時欄位一目了然 */
private fun hms(ms: Long): String {
    val total = (ms + 999) / 1000
    return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}

@Composable
private fun PickChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (selected) C.accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) C.accent.copy(alpha = 0.6f) else C.chipBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) C.accentSoft else C.textSoft, fontSize = 13.sp, maxLines = 1) }
}

@Composable
private fun TimerGroupHeader(
    group: TimerGroupEntity,
    timers: List<TimerEntity>,
    onToggleExpanded: () -> Unit,
    onToggleEnabled: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val runningCount = timers.count { it.state == TimerState.RUNNING }
    val summary = "${timers.size} 個" + if (group.enabled) " · $runningCount 個進行中" else " · 已關閉（計時暫停）"
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(if (group.enabled) C.surface else C.bgDeep)
            .border(1.dp, C.border, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = if (group.expanded) "收合群組" else "展開群組", onClick = onToggleExpanded)
            .padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(if (group.expanded) Glyph.ChevronDown else Glyph.ChevronRight, null, tint = C.muted, size = 20.dp)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                group.name, color = if (group.enabled) C.text else C.dim, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(summary, color = if (group.enabled) C.muted else C.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MiniSwitch(group.enabled, onToggleEnabled, contentDescription = "計時器群組「${group.name}」開關")
        Box {
            IconButton(onClick = { menu = true }) { GlyphIcon(Glyph.More, "群組選項", tint = C.muted, size = 20.dp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = C.surfaceHi) {
                DropdownMenuItem(text = { Text("重新命名", color = C.text) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("刪除群組", color = C.danger) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun TimerCard(
    t: TimerEntity,
    remaining: Long,
    groupOff: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
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
                    TimerState.PAUSED -> if (t.pausedByGroup || groupOff) " · 群組已關閉" else " · 已暫停"
                    TimerState.DONE -> " · 已完成"
                    TimerState.RINGING -> " · 響鈴中"
                    TimerState.RUNNING -> ""
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.label.ifBlank { "計時器" } + status, color = C.textSoft, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Box(
                        Modifier.padding(start = 4.dp).size(28.dp).clip(CircleShape).clickable(onClickLabel = "移到群組", onClick = onMove),
                        contentAlignment = Alignment.Center,
                    ) { GlyphIcon(Glyph.Folder, "移到群組", tint = C.muted, size = 15.dp) }
                }
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
