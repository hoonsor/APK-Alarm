package com.hoonsor.alarm.ui.alarms

import com.hoonsor.alarm.domain.NextTrigger
import com.hoonsor.alarm.data.isSkipping
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.data.AlarmEntity
import com.hoonsor.alarm.domain.Days
import com.hoonsor.alarm.domain.Fmt
import com.hoonsor.alarm.ringing.RingInfoFactory
import com.hoonsor.alarm.ringing.RingTester
import com.hoonsor.alarm.ui.common.DayChip
import com.hoonsor.alarm.ui.common.Glyph
import com.hoonsor.alarm.ui.common.GlyphIcon
import com.hoonsor.alarm.ui.common.MiniSwitch
import com.hoonsor.alarm.ui.common.NameDialog
import com.hoonsor.alarm.ui.common.SoundSetting
import com.hoonsor.alarm.ui.common.rememberBitmap
import com.hoonsor.alarm.tts.TtsEngine
import com.hoonsor.alarm.ui.theme.C
import kotlinx.coroutines.delay

private val QUICK_DAYS = listOf("平日" to Days.WEEKDAYS, "週末" to Days.WEEKEND)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditorSheet(
    alarm: AlarmEntity,
    vm: AlarmsViewModel,
    onDelete: (Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var showTimePicker by remember { mutableStateOf(alarm.isCopy) }
    var memo by remember(alarm.id) { mutableStateOf(alarm.memo) }
    var newGroupDialog by remember { mutableStateOf(false) }
    val groups by vm.groups.collectAsState()

    // 提醒文字輸入停頓 0.4 秒後才寫入，避免每個字都觸發重新排程
    LaunchedEffect(memo) {
        if (memo != alarm.memo) {
            delay(400)
            vm.setMemo(alarm.id, memo)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importImage(alarm.id, uri)
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (memo != alarm.memo) vm.setMemo(alarm.id, memo)
            vm.close()
        },
        sheetState = sheetState,
        containerColor = C.surfaceHi,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, bottom = 26.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Fmt.hm(alarm.hour, alarm.minute),
                    fontSize = 46.sp, fontWeight = FontWeight.SemiBold,
                    color = if (alarm.enabled) C.text else C.dim,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "修改時間") { showTimePicker = true },
                )
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(Days.summary(alarm.daysMask), color = C.textSoft, fontSize = 14.sp)
                    if (alarm.isCopy) Text("副本 · 點時間修改", color = C.muted, fontSize = 11.sp)
                }
                MiniSwitch(alarm.enabled, { vm.toggleEnabled(alarm) }, large = true, contentDescription = "啟用鬧鐘")
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("重複 · 點一下切換", color = C.muted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                    QUICK_DAYS.forEach { (label, bits) ->
                        val all = alarm.daysMask and bits == bits
                        SmallToggle(label, all) {
                            // 已全選 → 移除這幾天；否則加入這幾天。兩顆都按 = 每天
                            vm.setDays(alarm.id, if (all) alarm.daysMask and bits.inv() else alarm.daysMask or bits)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Days.LABELS.forEachIndexed { i, l ->
                        DayChip(l, Days.has(alarm.daysMask, i)) { vm.setDays(alarm.id, Days.toggle(alarm.daysMask, i)) }
                    }
                }
            }

            Section("響鈴時顯示的提醒") {
                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    placeholder = { Text("例如：帶筆電與門禁卡", color = C.dim) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = C.field, unfocusedContainerColor = C.field,
                        focusedBorderColor = C.accent, unfocusedBorderColor = C.borderHi,
                        focusedTextColor = C.text, unfocusedTextColor = C.text,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Section("響鈴方式") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupChip("鈴聲", !alarm.speakMemo && !alarm.silent) { vm.setSpeak(alarm.id, false) }
                    GroupChip("語音念出提醒", alarm.speakMemo && !alarm.silent) { vm.setSpeak(alarm.id, true) }
                    GroupChip("僅震動", alarm.silent) { vm.setSilent(alarm.id) }
                }
                if (alarm.silent) {
                    Text(
                        "響鈴時只震動、不發出任何聲音，也不會打斷正在播放的音樂；畫面照常亮起，10 分鐘沒處理會留下錯過通知。",
                        color = C.muted, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                } else if (alarm.speakMemo) {
                    val engineName = if (Graph.settings.ttsEngine == TtsEngine.ENGINE_MINIMAX) "MiniMax 雲端語音" else "手機內建語音"
                    val cached = TtsEngine.validCache(alarm) != null
                    val status = when {
                        alarm.memo.isBlank() && memo.isBlank() && !Graph.settings.ttsSpeakTime -> "提醒文字是空的，響鈴時會改用鈴聲"
                        Graph.settings.ttsEngine == TtsEngine.ENGINE_MINIMAX && !cached -> "$engineName 產生中或失敗時，會改用手機內建語音念出"
                        Graph.settings.ttsEngine == TtsEngine.ENGINE_MINIMAX -> "$engineName 已預先產生，響鈴時不需要網路"
                        else -> "$engineName 念出（引擎與聲音在右上角設定頁調整）"
                    }
                    Text("會念：「${TtsEngine.speechText(alarm.hour, alarm.minute, memo)}」", color = C.textSoft, fontSize = 14.sp, lineHeight = 20.sp)
                    Text(status, color = C.muted, fontSize = 12.sp, lineHeight = 18.sp)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { vm.setSpeakOnce(alarm.id, !alarm.speakOnceThenRing) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = alarm.speakOnceThenRing,
                            onCheckedChange = { vm.setSpeakOnce(alarm.id, it) },
                            colors = CheckboxDefaults.colors(checkedColor = C.accent, checkmarkColor = C.onAccent, uncheckedColor = C.chipBorder),
                        )
                        Column(Modifier.weight(1f)) {
                            Text("單次語音，之後播放鈴聲", color = C.text, fontSize = 15.sp)
                            Text("念一次提醒後改用鈴聲持續響；取消勾選則一直重複念", color = C.dim, fontSize = 12.sp)
                        }
                    }
                    if (alarm.speakOnceThenRing) {
                        SoundSetting(
                            value = alarm.soundUri,
                            onChange = { vm.setSound(alarm.id, it) },
                            defaultLabel = "跟隨鬧鐘預設",
                            defaultValue = Graph.settings.alarmSoundUri,
                        )
                    }
                } else {
                    SoundSetting(
                        value = alarm.soundUri,
                        onChange = { vm.setSound(alarm.id, it) },
                        defaultLabel = "跟隨鬧鐘預設",
                        defaultValue = Graph.settings.alarmSoundUri,
                    )
                }
            }

            Section("音量與震動") {
                if (alarm.silent) {
                    Text("純震動鬧鐘一定會震動，不使用音量設定", color = C.muted, fontSize = 13.sp)
                } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("震動", color = C.textSoft, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    MiniSwitch(alarm.vibrate, { vm.setVibrate(alarm.id, !alarm.vibrate) }, contentDescription = "震動")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("使用手機目前的鬧鐘音量", color = C.textSoft, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    MiniSwitch(alarm.volume == null, {
                        vm.setVolume(alarm.id, if (alarm.volume == null) 70 else null)
                    }, contentDescription = "使用手機鬧鐘音量")
                }
                if (alarm.volume != null) {
                    var vol by remember(alarm.id, alarm.volume) { mutableFloatStateOf(alarm.volume.toFloat()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("音量 ${vol.toInt()}%", color = C.muted, fontSize = 13.sp, modifier = Modifier.width(80.dp))
                        Slider(
                            value = vol, onValueChange = { vol = it },
                            onValueChangeFinished = { vm.setVolume(alarm.id, vol.toInt()) },
                            valueRange = 10f..100f, steps = 8,
                            colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text("響鈴時暫時調整手機的鬧鐘音量，結束後自動還原", color = C.dim, fontSize = 12.sp)
                }
                }
            }

            Section("群組") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val currentGroup = alarm.groupId?.takeIf { id -> groups.any { it.id == id } }
                    GroupChip("未分組", currentGroup == null) { vm.setAlarmGroup(alarm.id, null) }
                    groups.forEach { g -> GroupChip(g.name, currentGroup == g.id) { vm.setAlarmGroup(alarm.id, g.id) } }
                    GroupChip("＋ 新群組", false) { newGroupDialog = true }
                }
            }

            Section("響鈴圖片") {
                val thumb = rememberBitmap(alarm.imagePath, maxEdge = 240)
                val launchPicker = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (alarm.imagePath != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(72.dp, 54.dp).clip(RoundedCornerShape(10.dp)).background(C.field)) {
                            thumb?.let {
                                Image(it.asImageBitmap(), "響鈴圖片縮圖", contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp, 54.dp))
                            }
                        }
                        Text("已設定", color = C.textSoft, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = launchPicker, shape = RoundedCornerShape(12.dp)) { Text("更換", color = C.text) }
                        OutlinedButton(onClick = { vm.setImage(alarm.id, null) }, shape = RoundedCornerShape(12.dp)) { Text("移除", color = C.danger) }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
                            .border(1.dp, C.faint, RoundedCornerShape(12.dp)).clickable(onClick = launchPicker),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlyphIcon(Glyph.Image, null, tint = C.textSoft, size = 18.dp)
                        Text("  從相簿選擇（未設定時使用全域預設）", color = C.textSoft, fontSize = 14.sp)
                    }
                }
            }

            // 略過下次
            run {
                val now = System.currentTimeMillis()
                val groupOn = alarm.groupId?.let { id -> groups.firstOrNull { it.id == id }?.enabled } ?: true
                val skipping = alarm.isSkipping(now)
                val target = if (skipping) alarm.skipUntil
                else if (alarm.enabled && groupOn) NextTrigger.nextRegular(alarm, now) else null
                if (target != null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (skipping) C.skip.copy(alpha = 0.12f) else C.surfaceHi)
                            .border(1.dp, C.skip.copy(alpha = if (skipping) 0.8f else 0.35f), RoundedCornerShape(14.dp))
                            .clickable { if (skipping) vm.unskip(alarm.id) else vm.skipNext(alarm.id) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlyphIcon(Glyph.SkipNext, null, tint = C.skip, size = 18.dp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(if (skipping) "已略過下次" else "略過下次", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                (if (skipping) "這次不響：" else "下一次：") + skipLabel(target) + if (skipping) "，之後照常" else "",
                                color = C.muted, fontSize = 12.sp,
                            )
                        }
                        Text(if (skipping) "取消略過" else "略過", color = C.skip, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.copyOne(alarm.id) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent),
                ) {
                    GlyphIcon(Glyph.Copy, null, tint = C.onAccent, size = 18.dp)
                    Text("  複製", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { RingTester.start(context, RingInfoFactory.forAlarm(alarm.copy(memo = memo), System.currentTimeMillis(), isTest = true)) },
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    GlyphIcon(Glyph.Play, null, tint = C.text, size = 16.dp)
                    Text(" 測試", color = C.text)
                }
                OutlinedButton(
                    onClick = { onDelete(alarm.id) },
                    modifier = Modifier.width(56.dp).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { GlyphIcon(Glyph.Trash, "刪除這個鬧鐘", tint = C.danger, size = 18.dp) }
            }
        }
    }

    if (newGroupDialog) {
        NameDialog(
            "新增群組", "", confirmLabel = "建立並加入",
            onConfirm = { vm.addGroup(it, listOf(alarm.id)); newGroupDialog = false },
            onDismiss = { newGroupDialog = false },
        )
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(initialHour = alarm.hour, initialMinute = alarm.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            containerColor = C.surfaceHi,
            confirmButton = {
                TextButton(onClick = {
                    vm.setTime(alarm.id, state.hour, state.minute)
                    showTimePicker = false
                }) { Text("確定", color = C.accent) }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消", color = C.muted) } },
            text = {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = C.field,
                        selectorColor = C.accent,
                        timeSelectorSelectedContainerColor = C.accent.copy(alpha = 0.2f),
                        timeSelectorSelectedContentColor = C.accentSoft,
                        timeSelectorUnselectedContainerColor = C.field,
                        timeSelectorUnselectedContentColor = C.text,
                    ),
                )
            },
        )
    }
}

@Composable
private fun SmallToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(30.dp).clip(RoundedCornerShape(15.dp))
            .background(if (selected) C.accent.copy(alpha = 0.16f) else C.surfaceHi)
            .border(1.dp, if (selected) C.accent.copy(alpha = 0.6f) else C.chipBorder, RoundedCornerShape(15.dp))
            .clickable(onClickLabel = if (selected) "移除$label" else "加入$label", onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) C.accentSoft else C.textSoft, fontSize = 12.sp) }
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
            .background(if (selected) C.accent.copy(alpha = 0.16f) else C.surfaceHi)
            .border(1.dp, if (selected) C.accent.copy(alpha = 0.6f) else C.chipBorder, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) C.accentSoft else C.textSoft, fontSize = 13.sp, maxLines = 1) }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = C.muted, fontSize = 12.sp, letterSpacing = 1.sp)
        content()
    }
}

/** 10/14（二）07:00 */
private fun skipLabel(ms: Long): String {
    val z = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
    val wd = "一二三四五六日"[z.dayOfWeek.value - 1]
    return "${z.monthValue}/${z.dayOfMonth}（$wd）%02d:%02d".format(z.hour, z.minute)
}
