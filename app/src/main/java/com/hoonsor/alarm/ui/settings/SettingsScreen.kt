package com.hoonsor.alarm.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.data.VignetteShape
import com.hoonsor.alarm.image.ImageStore
import com.hoonsor.alarm.ringing.RingInfo
import com.hoonsor.alarm.ringing.RingingScreen
import com.hoonsor.alarm.scheduler.FireType
import com.hoonsor.alarm.ui.common.Glyph
import com.hoonsor.alarm.ui.common.GlyphIcon
import com.hoonsor.alarm.ui.common.SoundSetting
import com.hoonsor.alarm.ui.common.rememberBitmap
import com.hoonsor.alarm.ui.theme.C
import com.hoonsor.alarm.update.UpdateCard
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val s = Graph.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shape by remember { mutableStateOf(s.vignetteShape) }
    var strength by remember { mutableFloatStateOf(s.vignetteStrength.toFloat()) }
    var ramp by remember { mutableFloatStateOf(s.rampSeconds.toFloat()) }
    var defaultImage by remember { mutableStateOf(s.defaultImagePath) }
    var preview by remember { mutableStateOf(false) }
    var alarmSound by remember { mutableStateOf(s.alarmSoundUri) }
    var timerSound by remember { mutableStateOf(s.timerSoundUri) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = ImageStore.import(context, uri) ?: return@launch
            val old = s.defaultImagePath
            s.defaultImagePath = path
            defaultImage = path
            Graph.repo.purgeUnusedImages(listOf(old))
        }
    }

    if (preview) {
        Box(Modifier.fillMaxSize()) {
            RingingScreen(
                info = RingInfo(FireType.ALARM, -1, 0, "07:15", "預覽 · 每週一、三、五", "晨跑 30 分鐘，記得帶耳機", defaultImage),
                vignetteShape = shape,
                vignetteStrength = strength.toInt(),
                onSnooze = { preview = false },
                onStop = { preview = false },
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { GlyphIcon(Glyph.Back, "返回", tint = C.text) }
            Text("設定", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.text)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card("響鈴畫面") {
                Text("全域預設圖片（鬧鐘未個別設定時使用）", color = C.muted, fontSize = 13.sp)
                val thumb = rememberBitmap(defaultImage, 320)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(96.dp, 72.dp).clip(RoundedCornerShape(12.dp)).background(C.field), contentAlignment = Alignment.Center) {
                        if (thumb != null) {
                            Image(thumb.asImageBitmap(), "預設響鈴圖片", contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp, 72.dp))
                        } else {
                            Text("未設定", color = C.dim, fontSize = 12.sp)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            shape = RoundedCornerShape(12.dp),
                        ) { Text(if (defaultImage == null) "選擇圖片" else "更換", color = C.text) }
                        if (defaultImage != null) {
                            OutlinedButton(onClick = {
                                val old = s.defaultImagePath
                                s.defaultImagePath = null
                                defaultImage = null
                                scope.launch { Graph.repo.purgeUnusedImages(listOf(old)) }
                            }, shape = RoundedCornerShape(12.dp)) { Text("移除", color = C.danger) }
                        }
                    }
                }

                Text("暗角形狀", color = C.muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(VignetteShape.ELLIPSE to "橢圓", VignetteShape.SQUARE to "方形").forEach { (v, label) ->
                        val sel = shape == v
                        Box(
                            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
                                .background(if (sel) C.accent.copy(alpha = 0.16f) else Color.Transparent)
                                .border(1.dp, if (sel) C.accent.copy(alpha = 0.6f) else C.chipBorder, RoundedCornerShape(20.dp))
                                .clickable { shape = v; s.vignetteShape = v }
                                .padding(horizontal = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (sel) C.accentSoft else C.textSoft, fontSize = 14.sp) }
                    }
                }

                Text("暗角強度 ${strength.toInt()}%", color = C.muted, fontSize = 13.sp)
                Slider(
                    value = strength, onValueChange = { strength = it },
                    onValueChangeFinished = { s.vignetteStrength = strength.toInt() },
                    valueRange = 0f..100f, steps = 19,
                    colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent),
                )
                OutlinedButton(onClick = { preview = true }, shape = RoundedCornerShape(12.dp)) {
                    Text("預覽響鈴畫面", color = C.accentSoft)
                }
            }

            Card("聲音") {
                Text("音量漸強時間：${if (ramp.toInt() == 0) "關閉（直接最大聲）" else "${ramp.toInt()} 秒"}", color = C.muted, fontSize = 13.sp)
                Slider(
                    value = ramp, onValueChange = { ramp = it },
                    onValueChangeFinished = { s.rampSeconds = ramp.toInt() },
                    valueRange = 0f..60f, steps = 11,
                    colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent),
                )
                Text("鬧鐘預設鈴聲（個別鬧鐘可在編輯面板另外指定）", color = C.muted, fontSize = 13.sp)
                SoundSetting(value = alarmSound, onChange = { v ->
                    val old = s.alarmSoundUri
                    s.alarmSoundUri = v; alarmSound = v
                    scope.launch { Graph.repo.purgeUnusedSounds(listOf(old)) }
                })
                Text("計時器鈴聲", color = C.muted, fontSize = 13.sp)
                SoundSetting(value = timerSound, onChange = { v ->
                    val old = s.timerSoundUri
                    s.timerSoundUri = v; timerSound = v
                    scope.launch { Graph.repo.purgeUnusedSounds(listOf(old)) }
                })
                Text("指定的鈴聲無法播放時（例如檔案損毀），會自動改用系統鬧鐘鈴聲，確保一定會響。", color = C.dim, fontSize = 12.sp, lineHeight = 18.sp)
            }

            TtsCard()

            AiCard()

            BackupCard()

            AgentBenchCard()

            UpdateCard()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface)
            .border(1.dp, C.border, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}
