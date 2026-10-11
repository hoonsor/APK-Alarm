package com.hoonsor.alarm.ui.common

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import com.hoonsor.alarm.sound.SoundPreview
import com.hoonsor.alarm.sound.SoundStore
import com.hoonsor.alarm.ui.theme.C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 系統鬧鐘鈴聲（跟著系統設定變動），用來在「個別鬧鐘」明確指定不用預設的自訂音檔 */
val SYSTEM_ALARM_SOUND: String = Settings.System.DEFAULT_ALARM_ALERT_URI.toString()

/**
 * 鈴聲設定列：顯示目前鈴聲、試聽、更換。
 * value = null 表示「跟隨預設」（defaultLabel 不為 null 時）或「系統鬧鐘鈴聲」。
 */
@Composable
fun SoundSetting(
    value: String?,
    onChange: (String?) -> Unit,
    defaultLabel: String? = null,
    defaultValue: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showChooser by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { SoundPreview.stop() } }

    fun accept(newValue: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { SoundStore.isPlayable(context, newValue) }
            if (ok) onChange(newValue)
            else Toast.makeText(context, "App 無法讀取這個鈴聲，請改用「匯入音檔」", Toast.LENGTH_LONG).show()
        }
    }

    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val uri = r.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            if (uri != null) accept(uri.toString())
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val path = SoundStore.import(context, uri)
                importing = false
                if (path != null) onChange(path)
                else Toast.makeText(context, "無法匯入這個音檔（格式不支援或超過 50 MB）", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 實際會播的鈴聲（跟隨預設時顯示預設的名稱）
    val effective = value ?: defaultValue
    val name = when {
        importing -> "匯入中…"
        value == null && defaultLabel != null -> "$defaultLabel（${SoundStore.displayName(context, defaultValue)}）"
        value == SYSTEM_ALARM_SOUND -> "系統鬧鐘鈴聲"
        else -> SoundStore.displayName(context, value)
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlyphIcon(Glyph.Music, null, tint = C.muted, size = 18.dp)
        Text(name, color = C.textSoft, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = {
            if (playing) {
                SoundPreview.stop(); playing = false
            } else {
                SoundPreview.play(context, effective) { playing = false }
                playing = SoundPreview.playingValue != null
            }
        }) {
            GlyphIcon(if (playing) Glyph.Stop else Glyph.Play, if (playing) "停止試聽" else "試聽", tint = C.text, size = 14.dp)
        }
        OutlinedButton(onClick = { showChooser = true }) { Text("更換", color = C.text) }
    }

    if (showChooser) {
        val items = buildList {
            if (defaultLabel != null) {
                add(ChoiceItem(defaultLabel, SoundStore.displayName(context, defaultValue), selected = value == null) {
                    showChooser = false; onChange(null)
                })
            }
            val systemSelected = if (defaultLabel != null) value == SYSTEM_ALARM_SOUND else value == null || value == SYSTEM_ALARM_SOUND
            add(ChoiceItem("系統鬧鐘鈴聲", "跟著手機的鬧鐘鈴聲設定", selected = systemSelected) {
                showChooser = false
                onChange(if (defaultLabel != null) SYSTEM_ALARM_SOUND else null)
            })
            add(ChoiceItem("從手機鈴聲挑選…", "使用系統內建的鈴聲清單") {
                showChooser = false
                SoundPreview.stop(); playing = false
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "選擇鈴聲")
                runCatching { ringtonePicker.launch(intent) }
                    .onFailure { Toast.makeText(context, "這支手機沒有鈴聲選擇器，請改用「匯入音檔」", Toast.LENGTH_LONG).show() }
            })
            val custom = value?.startsWith("/") == true
            add(ChoiceItem("匯入音檔（mp3、m4a…）…", if (custom) "目前：${SoundStore.displayName(context, value)}" else "會複製一份到 App 內，原檔刪除也不影響", selected = custom) {
                showChooser = false
                SoundPreview.stop(); playing = false
                filePicker.launch(arrayOf("audio/*"))
            })
        }
        ChoiceDialog("選擇鈴聲", items, onDismiss = { showChooser = false })
    }
}

