package com.hoonsor.alarm.ui.timer

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.hoonsor.alarm.data.color
import com.hoonsor.alarm.domain.Fmt
import com.hoonsor.alarm.domain.NextTrigger
import com.hoonsor.alarm.scheduler.AlarmScheduler
import com.hoonsor.alarm.ui.common.ChoiceDialog
import com.hoonsor.alarm.ui.common.ChoiceItem
import com.hoonsor.alarm.ui.common.Glyph
import com.hoonsor.alarm.ui.common.GlyphIcon
import com.hoonsor.alarm.ui.common.GroupColorDialog
import com.hoonsor.alarm.ui.common.MiniSwitch
import com.hoonsor.alarm.ui.common.NameDialog
import com.hoonsor.alarm.ui.theme.C
import com.hoonsor.alarm.ui.theme.GroupColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PRESETS_MIN = listOf(1, 3, 5, 10, 15, 25, 45, 60)
private val TIMER_TILE_HEIGHT = 140.dp

/** 計時器多選狀態；由 MainActivity 持有，選取時底部導覽列換成批次操作列 */
class TimerSelection {
    var active by mutableStateOf(false)
        private set
    val ids = mutableStateListOf<Long>()

    fun start(id: Long) { active = true; ids.clear(); ids.add(id) }
    fun toggle(id: Long) { if (id in ids) ids.remove(id) else ids.add(id); if (ids.isEmpty()) active = false }
    fun setAll(all: List<Long>) { ids.clear(); ids.addAll(all) }
    fun exit() { active = false; ids.clear() }
}

private enum class TimerSort(val key: String, val label: String, val short: String) {
    REMAINING_ASC("remaining_asc", "剩餘時間：短 → 長", "剩餘時間 ↑"),
    REMAINING_DESC("remaining_desc", "剩餘時間：長 → 短", "剩餘時間 ↓"),
    CREATED_DESC("created_desc", "建立時間：新 → 舊", "建立時間 ↓"),
    CREATED_ASC("created_asc", "建立時間：舊 → 新", "建立時間 ↑"),
    NAME_ASC("name_asc", "名稱：A → Z", "名稱"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: CREATED_DESC
    }
}

@Composable
fun TimerScreen(selection: TimerSelection, onVoice: () -> Unit) {
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
    var sort by remember { mutableStateOf(TimerSort.of(Graph.settings.timerSort)) }
    var sortDialog by remember { mutableStateOf(false) }
    val running = timers.count { it.state == TimerState.RUNNING }
    /** 新計時器要放的群組；-1 = 未分組 */
    var newGroupId by rememberSaveable { mutableLongStateOf(-1L) }
    var renaming by remember { mutableStateOf<TimerGroupEntity?>(null) }
    var coloring by remember { mutableStateOf<TimerGroupEntity?>(null) }
    var deleting by remember { mutableStateOf<TimerGroupEntity?>(null) }
    /** 新增群組對話框；true = 建立後自動選為新計時器的群組 */
    var newGroupDialog by remember { mutableStateOf<Boolean?>(null) }
    val groupIds = groups.map { it.id }.toSet()
    LaunchedEffect(groupIds) { if (newGroupId != -1L && newGroupId !in groupIds) newGroupId = -1L }
    // 被刪掉的計時器自動移出選取
    LaunchedEffect(timers) {
        val alive = timers.map { it.id }.toSet()
        selection.ids.filter { it !in alive }.forEach { selection.toggle(it) }
    }
    BackHandler(enabled = selection.active) { selection.exit() }

    fun remainingOf(t: TimerEntity) = NextTrigger.timerRemaining(t, nowWall, nowElapsed, boot)
    fun sorted(list: List<TimerEntity>): List<TimerEntity> = when (sort) {
        TimerSort.REMAINING_ASC -> list.sortedBy { remainingOf(it) }
        TimerSort.REMAINING_DESC -> list.sortedByDescending { remainingOf(it) }
        TimerSort.CREATED_DESC -> list.sortedByDescending { it.createdAt }
        TimerSort.CREATED_ASC -> list.sortedBy { it.createdAt }
        TimerSort.NAME_ASC -> list.sortedBy { it.label.ifBlank { "￿" } }
    }
    val ungrouped = sorted(timers.filter { it.groupId == null || it.groupId !in groupIds })

    Column(Modifier.fillMaxSize()) {
        if (selection.active) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 8.dp, end = 16.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selection.exit() }) { GlyphIcon(Glyph.Close, "結束選取", tint = C.text) }
                Column(Modifier.weight(1f)) {
                    Text("已選取 ${selection.ids.size} 個計時器", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.text)
                    Text("用下方按鈕分組、暫停、繼續、重設或刪除", fontSize = 12.sp, color = C.muted)
                }
                val all = selection.ids.size == timers.size && timers.isNotEmpty()
                OutlinedButton(
                    onClick = { if (all) selection.exit() else selection.setAll(timers.map { it.id }) },
                    shape = RoundedCornerShape(20.dp),
                ) { Text(if (all) "取消全選" else "全選", color = C.text) }
            }
        } else {
            Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("計時器", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.text, modifier = Modifier.weight(1f))
                    Row(
                        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(C.accent)
                            .clickable(onClickLabel = "語音建立計時器", onClick = onVoice).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlyphIcon(Glyph.Mic, null, tint = C.onAccent, size = 18.dp)
                        Text(" 語音", color = C.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$running 個進行中 · 長按可多選", fontSize = 14.sp, color = C.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = "變更排序") { sortDialog = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("排序：${sort.short}", color = C.accentSoft, fontSize = 13.sp)
                        GlyphIcon(Glyph.ChevronDown, null, tint = C.accentSoft, size = 14.dp)
                    }
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            fun LazyGridScope.cards(list: List<TimerEntity>, groupOff: Boolean, groupColor: Int?) {
                items(list, key = { it.id }) { t ->
                    TimerCard(
                        t = t,
                        remaining = remainingOf(t),
                        nowWall = nowWall,
                        groupOff = groupOff,
                        groupColor = groupColor,
                        selecting = selection.active,
                        selected = t.id in selection.ids,
                        onClick = { if (selection.active) selection.toggle(t.id) },
                        onLongClick = { if (selection.active) selection.toggle(t.id) else selection.start(t.id) },
                        onPause = { scope.launch { repo.pauseTimer(t.id) } },
                        onResume = { scope.launch { repo.resumeTimer(t.id) } },
                        onReset = { scope.launch { repo.resetTimer(t.id) } },
                        onDelete = { scope.launch { repo.deleteTimer(t.id) } },
                    )
                }
            }

            groups.forEach { g ->
                val list = sorted(timers.filter { it.groupId == g.id })
                fullRow("tg_${g.id}") {
                    TimerGroupHeader(
                        group = g, timers = list,
                        onToggleExpanded = { scope.launch { repo.setTimerGroupExpanded(g.id, !g.expanded) } },
                        onToggleEnabled = { scope.launch { repo.setTimerGroupEnabled(g.id, !g.enabled) } },
                        onRename = { renaming = g },
                        onColor = { coloring = g },
                        onDelete = { deleting = g },
                    )
                }
                if (g.expanded) {
                    if (list.isEmpty()) {
                        fullRow("tg_empty_${g.id}") {
                            Text(
                                "這個群組還沒有計時器。新增計時器時選擇這個群組，或長按計時器多選後按「群組」。",
                                color = C.dim, fontSize = 12.sp, lineHeight = 18.sp,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                    cards(list, groupOff = !g.enabled, groupColor = g.color)
                }
            }
            if (groups.isNotEmpty() && ungrouped.isNotEmpty()) {
                fullRow("t_ungrouped") {
                    Text(
                        "未分組 · ${ungrouped.size}", color = C.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                    )
                }
            }
            cards(ungrouped, groupOff = false, groupColor = null)

            if (!selection.active) {
                fullRow("t_add_group") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { newGroupDialog = false }.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlyphIcon(Glyph.Folder, null, tint = C.muted, size = 18.dp)
                        Text("  新增計時器群組", color = C.muted, fontSize = 14.sp)
                    }
                }
                fullRow("t_new") {
                    NewTimerForm(
                        pickedMs = pickedMs,
                        onPick = { pickedMs = it },
                        onKeypad = { keypad = true },
                        label = label,
                        onLabel = { label = it },
                        groups = groups,
                        newGroupId = newGroupId,
                        onGroup = { newGroupId = it },
                        onNewGroup = { newGroupDialog = true },
                        onStart = {
                            val ms = pickedMs
                            val l = label.trim()
                            val gid = newGroupId.takeIf { it != -1L }
                            Graph.settings.lastTimerMs = ms
                            scope.launch { repo.startTimer(ms, l, gid) }
                            label = ""
                        },
                    )
                }
            }
        }
    }

    if (keypad) {
        DurationKeypadDialog(
            initialMs = pickedMs,
            onConfirm = { pickedMs = it; keypad = false },
            onDismiss = { keypad = false },
        )
    }
    if (sortDialog) {
        ChoiceDialog(
            title = "計時器排序",
            items = TimerSort.entries.map { o ->
                ChoiceItem(o.label, selected = o == sort) {
                    sort = o; Graph.settings.timerSort = o.key; sortDialog = false
                }
            },
            onDismiss = { sortDialog = false },
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
    coloring?.let { g ->
        GroupColorDialog(
            "「${g.name}」的顏色", g.color,
            onPick = { c -> coloring = null; scope.launch { repo.setTimerGroupColor(g.id, c) } },
            onDismiss = { coloring = null },
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
}

private fun LazyGridScope.fullRow(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** 選取模式的底部操作列（取代導覽列） */
@Composable
fun TimerSelectionBar(selection: TimerSelection) {
    val scope = rememberCoroutineScope()
    val repo = Graph.repo
    val groupsFlow = remember { Graph.db.timerGroupDao().observeAll() }
    val groups by groupsFlow.collectAsState(initial = emptyList())
    var chooseGroup by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val enabled = selection.ids.isNotEmpty()
    val ids = selection.ids.toList()

    Row(Modifier.fillMaxWidth().height(76.dp).background(C.bgDeep), horizontalArrangement = Arrangement.SpaceEvenly) {
        BarAction(Glyph.Folder, "群組", C.textSoft, enabled, Modifier.weight(1f)) { chooseGroup = true }
        BarAction(Glyph.Pause, "暫停", C.textSoft, enabled, Modifier.weight(1f)) {
            scope.launch { repo.pauseTimers(ids); selection.exit() }
        }
        BarAction(Glyph.Play, "繼續", C.textSoft, enabled, Modifier.weight(1f)) {
            scope.launch { repo.resumeTimers(ids); selection.exit() }
        }
        BarAction(Glyph.Reset, "全部重設", C.textSoft, enabled, Modifier.weight(1f)) {
            scope.launch { repo.resetTimers(ids); selection.exit() }
        }
        BarAction(Glyph.Trash, "刪除", C.danger, enabled, Modifier.weight(1f)) { confirmDelete = true }
    }

    if (chooseGroup) {
        val items = buildList {
            groups.forEach { g ->
                add(ChoiceItem(g.name) { chooseGroup = false; scope.launch { repo.moveTimersToGroup(ids, g.id); selection.exit() } })
            }
            add(ChoiceItem("未分組", "移出群組") { chooseGroup = false; scope.launch { repo.moveTimersToGroup(ids, null); selection.exit() } })
            add(ChoiceItem("＋ 新增群組…") { chooseGroup = false; newGroup = true })
        }
        ChoiceDialog("移到群組（${ids.size} 個計時器）", items, onDismiss = { chooseGroup = false })
    }
    if (newGroup) {
        NameDialog(
            "新增計時器群組", "", confirmLabel = "建立並移入",
            onConfirm = { name ->
                newGroup = false
                scope.launch { val gid = repo.addTimerGroup(name); repo.moveTimersToGroup(ids, gid); selection.exit() }
            },
            onDismiss = { newGroup = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = C.surfaceHi,
            title = { Text("刪除 ${ids.size} 個計時器？", color = C.text) },
            text = { Text("執行中的計時器會一併停止，刪除後無法復原。", color = C.textSoft) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; scope.launch { repo.deleteTimers(ids); selection.exit() } }) {
                    Text("刪除", color = C.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", color = C.muted) } },
        )
    }
}

@Composable
private fun BarAction(glyph: Glyph, label: String, tint: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = if (enabled) tint else tint.copy(alpha = 0.4f)
    Column(
        modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlyphIcon(glyph, null, tint = t)
        Spacer(Modifier.height(4.dp))
        Text(label, color = t, fontSize = 12.sp)
    }
}

@Composable
private fun NewTimerForm(
    pickedMs: Long,
    onPick: (Long) -> Unit,
    onKeypad: () -> Unit,
    label: String,
    onLabel: (String) -> Unit,
    groups: List<TimerGroupEntity>,
    newGroupId: Long,
    onGroup: (Long) -> Unit,
    onNewGroup: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(20.dp)).background(C.surfaceHi)
            .border(1.dp, C.accent.copy(alpha = 0.45f), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("新增計時器", color = C.muted, fontSize = 12.sp, letterSpacing = 1.sp)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.field)
                .clickable(onClickLabel = "自訂時長", onClick = onKeypad).padding(vertical = 10.dp),
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
                            .clickable { onPick(m * 60_000L) },
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
                        .clickable { onPick((pickedMs + d * 60_000L).coerceIn(1_000L, 99 * 3_600_000L)) },
                    contentAlignment = Alignment.Center,
                ) {
                    val text = when (d) { 60 -> "+1 時"; -60 -> "-1 時"; else -> if (d > 0) "+$d 分" else "$d 分" }
                    Text(text, color = C.muted, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        OutlinedTextField(
            value = label, onValueChange = onLabel, singleLine = true,
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
            PickChip("未分組", newGroupId == -1L, null) { onGroup(-1L) }
            groups.forEach { g -> PickChip(g.name, newGroupId == g.id, g.color) { onGroup(g.id) } }
            PickChip("＋ 新群組", false, null, onNewGroup)
        }
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent),
        ) {
            GlyphIcon(Glyph.Play, null, tint = C.onAccent, size = 18.dp)
            Text("  開始 ${Fmt.duration(pickedMs)}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 新增計時器的大字顯示：一律顯示時:分:秒，讓小時欄位一目了然 */
private fun hms(ms: Long): String {
    val total = (ms + 999) / 1000
    return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}

@Composable
private fun PickChip(label: String, selected: Boolean, color: Int?, onClick: () -> Unit) {
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (selected) C.accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) C.accent.copy(alpha = 0.6f) else C.chipBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (color != null) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(GroupColors.of(color)))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = if (selected) C.accentSoft else C.textSoft, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun TimerGroupHeader(
    group: TimerGroupEntity,
    timers: List<TimerEntity>,
    onToggleExpanded: () -> Unit,
    onToggleEnabled: () -> Unit,
    onRename: () -> Unit,
    onColor: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val tint = group.color
    val runningCount = timers.count { it.state == TimerState.RUNNING }
    val summary = "${timers.size} 個" + if (group.enabled) " · $runningCount 個進行中" else " · 已關閉（計時暫停）"
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(if (group.enabled) C.surface else C.bgDeep)
            .background(if (group.enabled) GroupColors.header(tint) else GroupColors.tile(tint))
            .border(1.dp, if (group.enabled) GroupColors.border(tint) else C.border, RoundedCornerShape(16.dp))
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
            Text(summary, color = if (group.enabled) C.textSoft else C.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MiniSwitch(group.enabled, onToggleEnabled, contentDescription = "計時器群組「${group.name}」開關")
        Box {
            IconButton(onClick = { menu = true }) { GlyphIcon(Glyph.More, "群組選項", tint = C.muted, size = 20.dp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = C.surfaceHi) {
                DropdownMenuItem(text = { Text("重新命名", color = C.text) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("變更顏色", color = C.text) }, onClick = { menu = false; onColor() })
                DropdownMenuItem(text = { Text("刪除群組", color = C.danger) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimerCard(
    t: TimerEntity,
    remaining: Long,
    nowWall: Long,
    groupOff: Boolean,
    groupColor: Int?,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
) {
    val running = t.state == TimerState.RUNNING
    val done = t.state == TimerState.DONE || t.state == TimerState.RINGING
    val progress = if (t.durationMs > 0) (remaining.toFloat() / t.durationMs).coerceIn(0f, 1f) else 0f
    val shape = RoundedCornerShape(18.dp)
    val status = when (t.state) {
        TimerState.PAUSED -> if (t.pausedByGroup || groupOff) "群組已關閉" else "已暫停"
        TimerState.DONE -> "已完成"
        TimerState.RINGING -> "響鈴中"
        TimerState.RUNNING -> "進行中"
    }
    Column(
        Modifier.height(TIMER_TILE_HEIGHT).clip(shape)
            .background(if (selected) Color(0xFF221F1A) else C.surface)
            .then(if (groupColor != null && !selected) Modifier.background(GroupColors.tile(groupColor)) else Modifier)
            .border(
                if (selected) 2.dp else 1.dp,
                when {
                    selected -> C.accent
                    groupColor != null -> GroupColors.of(groupColor).copy(alpha = 0.28f)
                    else -> C.border
                },
                shape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            t.label.ifBlank { "計時器" }, color = C.textSoft, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            Fmt.duration(remaining), fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (running) C.text else C.muted,
        )
        // 幾點響：執行中＝實際響鈴時刻；暫停中＝現在按繼續的話幾點響
        val ringText = when {
            running -> "${ringAt(nowWall + remaining, nowWall)} 響"
            t.state == TimerState.PAUSED && remaining > 0 -> "繼續則 ${ringAt(nowWall + remaining, nowWall)} 響"
            else -> null
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Bell, null, tint = if (running) C.accentSoft else C.dim, size = 12.dp)
            Text(
                " " + (ringText ?: status), color = if (running) C.accentSoft else C.muted, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            (if (ringText != null) "$status · " else "") + "共 ${Fmt.duration(t.durationMs)}",
            color = C.dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF262C3A))) {
            Box(
                Modifier.fillMaxWidth(progress).height(4.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (running) C.accent else C.nav),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selecting) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape)
                        .background(if (selected) C.accent else Color.Transparent)
                        .border(1.5.dp, if (selected) C.accent else C.chipBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (selected) GlyphIcon(Glyph.Check, "已選取", tint = C.onAccent, size = 16.dp) }
            } else {
                when {
                    running -> RoundBtn(Glyph.Pause, "暫停", filled = true, onClick = onPause)
                    done -> RoundBtn(Glyph.Reset, "重設", filled = true, onClick = onReset)
                    else -> RoundBtn(Glyph.Play, if (remaining < t.durationMs) "繼續" else "開始", filled = true, onClick = onResume)
                }
                // 停止＝停下並回到完整時長（執行中，或暫停在中途時才需要）
                if (!done && (running || remaining < t.durationMs - 999)) {
                    RoundBtn(Glyph.Stop, "停止", filled = false, onClick = onReset)
                }
                RoundBtn(Glyph.Trash, "刪除", filled = false, onClick = onDelete, tint = C.danger)
            }
        }
    }
}

@Composable
private fun RoundBtn(glyph: Glyph, label: String, filled: Boolean, onClick: () -> Unit, tint: Color = C.text) {
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .background(if (filled) C.accent else Color.Transparent)
            .border(1.dp, if (filled) C.accent else C.borderHi, CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, label, tint = if (filled) C.onAccent else tint, size = 15.dp) }
}

/** 響鈴時刻：今天顯示 14:35:20；跨日加「明天」或日期 */
private fun ringAt(at: Long, now: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val t = java.time.Instant.ofEpochMilli(at).atZone(zone)
    val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), t.toLocalDate())
    val time = "%02d:%02d:%02d".format(t.hour, t.minute, t.second)
    return when (days) {
        0L -> time
        1L -> "明天 $time"
        else -> "${t.monthValue}/${t.dayOfMonth} $time"
    }
}
