package com.hoonsor.alarm.ui.alarms

import android.widget.Toast
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoonsor.alarm.data.AlarmEntity
import com.hoonsor.alarm.data.AlarmGroupEntity
import com.hoonsor.alarm.data.isActive
import com.hoonsor.alarm.domain.Days
import com.hoonsor.alarm.domain.Fmt
import com.hoonsor.alarm.domain.NextTrigger
import com.hoonsor.alarm.ui.common.ChoiceDialog
import com.hoonsor.alarm.ui.common.ChoiceItem
import com.hoonsor.alarm.ui.common.Glyph
import com.hoonsor.alarm.ui.common.GlyphIcon
import com.hoonsor.alarm.ui.common.MiniSwitch
import com.hoonsor.alarm.ui.common.NameDialog
import com.hoonsor.alarm.ui.theme.C
import com.hoonsor.alarm.ui.theme.GroupColors
import com.hoonsor.alarm.ui.common.GroupColorDialog
import com.hoonsor.alarm.data.color
import com.hoonsor.alarm.data.isSkipping

/** 卡片固定高度：不論內容多寡、是否為副本，每張卡一樣大 */
private val TILE_HEIGHT = 112.dp

@Composable
fun AlarmsScreen(
    vm: AlarmsViewModel,
    healthIssues: Int,
    onOpenHealth: () -> Unit,
    onOpenSettings: () -> Unit,
    onVoice: () -> Unit,
) {
    val alarms by vm.alarms.collectAsState()
    val groups by vm.groups.collectAsState()
    val groupMap = groups.associateBy { it.id }
    val now = System.currentTimeMillis()
    val next = alarms.mapNotNull { a -> NextTrigger.forAlarm(a, now, groupMap)?.let { a to it } }.minByOrNull { it.second }
    val onCount = alarms.count { it.isActive(groupMap) }

    var newGroupDialog by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AlarmGroupEntity?>(null) }
    var coloring by remember { mutableStateOf<AlarmGroupEntity?>(null) }
    var deleting by remember { mutableStateOf<AlarmGroupEntity?>(null) }
    var newGroupForSelection by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drag = remember { GroupDragController(scope) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }

    fun onDrop(target: DropTarget?) {
        val n = vm.selection.size
        when (target) {
            is DropTarget.Group -> {
                vm.moveSelection(target.id)
                Toast.makeText(context, "已把 $n 個鬧鐘移到「${target.name}」", Toast.LENGTH_SHORT).show()
            }
            DropTarget.Ungrouped -> {
                vm.moveSelection(null)
                Toast.makeText(context, "已把 $n 個鬧鐘移出群組", Toast.LENGTH_SHORT).show()
            }
            DropTarget.NewGroup -> newGroupForSelection = true
            null -> Unit
        }
    }

    // 鬧鐘依群組分區；群組已被刪除的鬧鐘歸到「未分組」
    val byGroup = alarms.groupBy { a -> a.groupId?.takeIf { it in groupMap } }
    val ungrouped = byGroup[null].orEmpty()

    Box(Modifier.fillMaxSize().onGloballyPositioned { overlayOrigin = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            if (vm.selecting) {
                SelectionHeader(
                    count = vm.selection.size,
                    allSelected = vm.selection.size == alarms.size && alarms.isNotEmpty(),
                    onExit = vm::exitSelect,
                    onToggleAll = vm::toggleSelectAll,
                )
            } else {
                Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("鬧鐘", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.text, modifier = Modifier.weight(1f))
                        HealthPill(healthIssues, onOpenHealth)
                        IconButton(onClick = onOpenSettings) { GlyphIcon(Glyph.Settings, "設定", tint = C.muted) }
                    }
                    val nextText = if (next != null) {
                        "下一個 ${Fmt.hm(next.first.hour, next.first.minute)} · ${Fmt.until(next.second, now)} · "
                    } else ""
                    Text(nextText + "$onCount 個啟用中 · 長按可多選", fontSize = 14.sp, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (alarms.isEmpty() && groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("還沒有鬧鐘\n按右下角的麥克風或 + 建立", color = C.muted, fontSize = 15.sp, lineHeight = 24.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    groups.forEach { g ->
                        val list = byGroup[g.id].orEmpty()
                        fullRow("group_${g.id}") {
                            GroupHeader(
                                group = g,
                                alarms = list,
                                groupMap = groupMap,
                                now = now,
                                onToggleExpanded = { vm.toggleGroupExpanded(g) },
                                onToggleEnabled = { vm.toggleGroupEnabled(g) },
                                onRename = { renaming = g },
                                onColor = { coloring = g },
                                onDelete = { deleting = g },
                            )
                        }
                        if (g.expanded) {
                            if (list.isEmpty()) {
                                fullRow("empty_${g.id}") {
                                    Text(
                                        "這個群組還沒有鬧鐘。長按鬧鐘多選後按「群組」，或在鬧鐘編輯面板選擇群組。",
                                        color = C.dim, fontSize = 12.sp, lineHeight = 18.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            tiles(list, vm, groupEnabled = g.enabled, drag = drag, onDrop = { onDrop(it) }, groupColor = g.color, now = now)
                        }
                    }
                    if (groups.isNotEmpty() && ungrouped.isNotEmpty()) {
                        fullRow("ungrouped_header") {
                            Text(
                                "未分組 · ${ungrouped.size}",
                                color = C.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                            )
                        }
                    }
                    tiles(ungrouped, vm, groupEnabled = true, drag = drag, onDrop = { onDrop(it) }, now = now)
                    if (!vm.selecting) {
                        fullRow("add_group") {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(14.dp))
                                    .clickable { newGroupDialog = true }.padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GlyphIcon(Glyph.Folder, null, tint = C.muted, size = 18.dp)
                                Text("  新增群組", color = C.muted, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        if (!vm.selecting) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(C.surfaceHi).border(1.dp, C.chipBorder, CircleShape)
                        .clickable(onClickLabel = "手動新增鬧鐘") { vm.add() },
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.Plus, "手動新增鬧鐘", tint = C.text) }
                Box(
                    Modifier.size(60.dp).clip(CircleShape).background(C.accent)
                        .clickable(onClickLabel = "語音建立鬧鐘", onClick = onVoice),
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.Mic, "語音建立鬧鐘", tint = C.onAccent, size = 26.dp) }
            }
        }

        if (drag.active) {
            DropPanel(groups, drag, Modifier.align(Alignment.BottomCenter))
            DragGhosts(drag, alarms, overlayOrigin)
        }
    }

    if (newGroupForSelection) {
        NameDialog(
            "新增群組", "", confirmLabel = "建立並移入",
            onConfirm = { vm.moveSelectionToNewGroup(it); newGroupForSelection = false },
            onDismiss = { newGroupForSelection = false },
        )
    }
    if (newGroupDialog) {
        NameDialog("新增群組", "", confirmLabel = "建立", onConfirm = { vm.addGroup(it); newGroupDialog = false }, onDismiss = { newGroupDialog = false })
    }
    coloring?.let { g ->
        GroupColorDialog("「${g.name}」的顏色", g.color, onPick = { vm.setGroupColor(g.id, it); coloring = null }, onDismiss = { coloring = null })
    }
    renaming?.let { g ->
        NameDialog("重新命名群組", g.name, onConfirm = { vm.renameGroup(g.id, it); renaming = null }, onDismiss = { renaming = null })
    }
    deleting?.let { g ->
        val count = byGroup[g.id].orEmpty().size
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = C.surfaceHi,
            title = { Text("刪除群組「${g.name}」？", color = C.text) },
            text = { Text(if (count > 0) "群組內的 $count 個鬧鐘不會被刪除，會移到「未分組」。" else "這個群組沒有鬧鐘。", color = C.textSoft) },
            confirmButton = { TextButton(onClick = { vm.deleteGroup(g.id); deleting = null }) { Text("刪除群組", color = C.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消", color = C.muted) } },
        )
    }
}

private fun LazyGridScope.fullRow(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.tiles(
    list: List<AlarmEntity>,
    vm: AlarmsViewModel,
    groupEnabled: Boolean,
    drag: GroupDragController,
    onDrop: (DropTarget?) -> Unit,
    groupColor: Int? = null,
    now: Long = System.currentTimeMillis(),
) {
    items(list, key = { it.id }) { a ->
        AlarmTile(
            alarm = a,
            groupEnabled = groupEnabled,
            groupColor = groupColor,
            skipping = groupEnabled && a.enabled && a.isSkipping(now),
            selecting = vm.selecting,
            selected = a.id in vm.selection,
            open = vm.editingId == a.id,
            drag = drag,
            onClick = { if (vm.selecting) vm.toggleSelect(a.id) else vm.open(a.id) },
            onLongClick = { if (!vm.selecting) vm.startSelect(a.id) else vm.toggleSelect(a.id) },
            onDragStart = { localOffset ->
                val bounds = drag.tileBounds[a.id]
                if (bounds != null) {
                    if (a.id !in vm.selection) vm.toggleSelect(a.id)
                    // 長按的那張放最上面，其餘依選取順序疊在下方
                    val ordered = listOf(a.id) + (vm.selection - a.id).toList()
                    drag.start(ordered, bounds.topLeft + localOffset)
                }
            },
            onDragEnd = { onDrop(drag.end()) },
            onToggle = { vm.toggleEnabled(a) },
        )
    }
}

@Composable
private fun GroupHeader(
    group: AlarmGroupEntity,
    alarms: List<AlarmEntity>,
    groupMap: Map<Long, AlarmGroupEntity>,
    now: Long,
    onToggleExpanded: () -> Unit,
    onToggleEnabled: () -> Unit,
    onRename: () -> Unit,
    onColor: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val active = alarms.count { it.isActive(groupMap) }
    val tint = group.color
    val nextAt = alarms.mapNotNull { NextTrigger.forAlarm(it, now, groupMap) }.minOrNull()
    val summary = buildString {
        append("${alarms.size} 個")
        if (group.enabled) {
            append(" · $active 個啟用")
            if (nextAt != null) append(" · 下次 ").append(Fmt.dateTime(nextAt).takeLast(5))
        } else append(" · 已暫停")
    }
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
            Text(summary, color = if (group.enabled) C.muted else C.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MiniSwitch(group.enabled, onToggleEnabled, contentDescription = "群組「${group.name}」總開關")
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

@Composable
private fun HealthPill(issues: Int, onClick: () -> Unit) {
    val warn = issues > 0
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
            .background(if (warn) C.warnBg else Color(0xFF14261D))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (warn) C.warn else C.ok, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(if (warn) "$issues 項待處理" else "響鈴正常", color = if (warn) C.warnText else C.ok, fontSize = 13.sp)
    }
}

@Composable
private fun SelectionHeader(count: Int, allSelected: Boolean, onExit: () -> Unit, onToggleAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 8.dp, end = 16.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onExit) { GlyphIcon(Glyph.Close, "結束選取", tint = C.text) }
        Column(Modifier.weight(1f)) {
            Text("已選取 $count 項", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.text)
            Text("長按已選的卡片可拖曳到群組", fontSize = 12.sp, color = C.muted)
        }
        OutlinedButton(onClick = onToggleAll, shape = RoundedCornerShape(20.dp)) {
            Text(if (allSelected) "取消全選" else "全選", color = C.text)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlarmTile(
    alarm: AlarmEntity,
    groupEnabled: Boolean,
    groupColor: Int?,
    skipping: Boolean,
    selecting: Boolean,
    selected: Boolean,
    open: Boolean,
    drag: GroupDragController,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onToggle: () -> Unit,
) {
    val on = (alarm.enabled && groupEnabled) || alarm.snoozeUntil != null
    val dragging = drag.active && alarm.id in drag.ids
    // 選取模式：點一下勾選；長按已選卡片 → 收合成一疊並拖曳到群組
    val gesture = if (selecting) {
        Modifier
            .pointerInput(alarm.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset -> onDragStart(offset) },
                    onDrag = { change, amount -> change.consume(); drag.move(amount) },
                    onDragEnd = { if (drag.active) onDragEnd() },
                    onDragCancel = { drag.cancel() },
                )
            }
            .clickable { if (!drag.shouldIgnoreClick()) onClick() }
    } else {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    }
    val shape = RoundedCornerShape(18.dp)
    val bg = when {
        selected -> Color(0xFF221F1A)
        open -> C.surfaceHi
        else -> C.surface
    }
    val borderColor = when {
        selected -> C.accent
        open -> C.accent.copy(alpha = 0.6f)
        else -> C.border
    }
    Box(
        Modifier.height(TILE_HEIGHT)
            .onGloballyPositioned { drag.tileBounds[alarm.id] = it.boundsInRoot() }
            .alpha(if (dragging) 0.3f else 1f)
            .clip(shape).background(bg)
            .then(if (groupColor != null && !selected) Modifier.background(GroupColors.tile(groupColor)) else Modifier)
            .border(
                if (selected || skipping) 2.dp else 1.dp,
                when {
                    selected -> borderColor
                    skipping -> C.skip
                    groupColor != null && !open -> GroupColors.of(groupColor).copy(alpha = 0.28f)
                    else -> borderColor
                },
                shape,
            )
            .semantics { contentDescription = "${Fmt.hm(alarm.hour, alarm.minute)} 鬧鐘，${Days.summary(alarm.daysMask)}" }
            .then(gesture),
    ) {
        Column(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 13.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                Fmt.hm(alarm.hour, alarm.minute),
                fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                color = if (on) Color(0xFFF3F5F9) else C.dim,
            )
            // 星期列：永遠只有一行，空間不夠時直接裁掉，不會撐高卡片
            Row(
                Modifier.fillMaxWidth().clipToBounds(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (alarm.daysMask == 0) {
                    Text("單次", fontSize = 11.sp, color = C.muted, maxLines = 1, softWrap = false)
                } else {
                    Days.LABELS.forEachIndexed { i, l ->
                        val act = Days.has(alarm.daysMask, i)
                        Text(
                            l, fontSize = 11.sp, maxLines = 1, softWrap = false,
                            fontWeight = if (act) FontWeight.Bold else FontWeight.Normal,
                            color = if (act) (if (on) C.accent else C.muted) else C.faint,
                        )
                    }
                }
                if (alarm.imagePath != null) GlyphIcon(Glyph.Image, "有響鈴圖片", tint = C.muted, size = 12.dp)
                if (alarm.silent) GlyphIcon(Glyph.Vibrate, "純震動", tint = C.muted, size = 12.dp)
                else if (alarm.speakMemo) GlyphIcon(Glyph.Speaker, "語音念出提醒", tint = C.muted, size = 12.dp)
                else if (alarm.soundUri != null) GlyphIcon(Glyph.Music, "自訂鈴聲", tint = C.muted, size = 12.dp)
            }
            // 提醒列：標籤 + 一行提醒文字，超過以「…」省略
            Row(Modifier.fillMaxWidth().clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
                if (alarm.snoozeUntil != null) Badge("貪睡", C.accentSoft)
                if (skipping) Badge("略過下次", C.skip)
                if (alarm.isCopy) Badge("副本", C.textSoft)
                Text(
                    alarm.memo.ifBlank { "尚未設定提醒" },
                    fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    color = if (alarm.memo.isNotBlank() && on) Color(0xFFB9C0CD) else C.dim,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            if (selecting) {
                Box(
                    Modifier.padding(12.dp).size(26.dp)
                        .background(if (selected) C.accent else Color.Transparent, CircleShape)
                        .border(2.dp, if (selected) C.accent else Color(0xFF5A6275), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (selected) GlyphIcon(Glyph.Check, null, tint = C.onAccent, size = 15.dp) }
            } else {
                MiniSwitch(checked = alarm.enabled, onToggle = onToggle, contentDescription = "啟用 ${Fmt.hm(alarm.hour, alarm.minute)} 鬧鐘")
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, fontSize = 10.sp, color = color, maxLines = 1, softWrap = false,
        modifier = Modifier.padding(end = 4.dp).background(C.switchOff, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 選取模式時取代底部導覽列的操作列 */
@Composable
fun AlarmSelectionBar(vm: AlarmsViewModel, onDelete: () -> Unit) {
    val enabled = vm.selection.isNotEmpty()
    val allOn = vm.selectionAllEnabled()
    val groups by vm.groups.collectAsState()
    var chooseGroup by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().height(76.dp).background(C.bgDeep),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BarAction(Glyph.Copy, "複製", C.textSoft, enabled, Modifier.weight(1f)) { vm.bulkCopy() }
        BarAction(Glyph.Folder, "群組", C.textSoft, enabled, Modifier.weight(1f)) { chooseGroup = true }
        BarAction(Glyph.Power, if (allOn) "停用" else "啟用", C.textSoft, enabled, Modifier.weight(1f)) { vm.bulkToggle() }
        val allSkip = vm.selectionAllSkipping()
        BarAction(Glyph.SkipNext, if (allSkip) "取消略過" else "略過下次", C.skip, enabled, Modifier.weight(1f)) { vm.bulkSkip() }
        BarAction(Glyph.Trash, "刪除", C.danger, enabled, Modifier.weight(1f), onDelete)
    }

    if (chooseGroup) {
        val items = buildList {
            groups.forEach { g -> add(ChoiceItem(g.name) { chooseGroup = false; vm.moveSelection(g.id) }) }
            add(ChoiceItem("未分組", "移出群組") { chooseGroup = false; vm.moveSelection(null) })
            add(ChoiceItem("＋ 新增群組…") { chooseGroup = false; newGroup = true })
        }
        ChoiceDialog("移到群組（${vm.selection.size} 個鬧鐘）", items, onDismiss = { chooseGroup = false })
    }
    if (newGroup) {
        NameDialog("新增群組", "", confirmLabel = "建立並移入", onConfirm = { vm.moveSelectionToNewGroup(it); newGroup = false }, onDismiss = { newGroup = false })
    }
}

@Composable
private fun BarAction(glyph: Glyph, label: String, tint: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = if (enabled) tint else tint.copy(alpha = 0.4f)
    Column(
        modifier.fillMaxSize().clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlyphIcon(glyph, null, tint = t)
        Spacer(Modifier.height(4.dp))
        Text(label, color = t, fontSize = 12.sp)
    }
}
