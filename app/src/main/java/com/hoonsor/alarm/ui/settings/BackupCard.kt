package com.hoonsor.alarm.ui.settings

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.hoonsor.alarm.backup.BackupManager
import com.hoonsor.alarm.backup.ImportMode
import com.hoonsor.alarm.backup.ImportResult
import com.hoonsor.alarm.ui.common.Glyph
import com.hoonsor.alarm.ui.common.GlyphIcon
import com.hoonsor.alarm.ui.theme.C
import kotlinx.coroutines.launch

/** 設定頁：備份與還原（分享備份檔、儲存到檔案、從檔案匯入） */
@Composable
fun BackupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var includeMedia by remember { mutableStateOf(true) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    fun work(start: String, block: suspend () -> String?) {
        busy = true
        message = true to start
        scope.launch {
            message = try {
                block()?.let { true to it }
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
            busy = false
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.MIME)) { uri ->
        if (uri != null) work("儲存中…") {
            BackupManager.exportTo(context, uri, includeMedia)
            "已儲存備份檔" + if (includeMedia) "（含圖片與鈴聲）" else ""
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) pendingImport = uri
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface)
            .border(1.dp, C.border, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("備份與還原", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "把鬧鐘、群組、計時器與設定打包成一個備份檔。MiniMax API key 不會放進備份，換手機後需要重新填寫。",
            color = C.muted, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { includeMedia = !includeMedia }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = includeMedia, onCheckedChange = { includeMedia = it },
                colors = CheckboxDefaults.colors(checkedColor = C.accent, checkmarkColor = C.onAccent, uncheckedColor = C.chipBorder),
            )
            Column(Modifier.weight(1f)) {
                Text("包含響鈴圖片與自訂鈴聲", color = C.text, fontSize = 14.sp)
                Text("檔案會變大；不勾選時，還原後圖片與自訂鈴聲改用預設", color = C.dim, fontSize = 12.sp)
            }
        }

        Button(
            enabled = !busy,
            onClick = {
                work("產生備份中…") {
                    val file = BackupManager.exportToTemp(context, includeMedia)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = BackupManager.MIME
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, file.name)
                        clipData = ClipData.newRawUri(file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(send, "分享備份檔").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                    null
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent),
        ) {
            GlyphIcon(Glyph.Share, null, tint = C.onAccent, size = 18.dp)
            Text("  分享備份檔…", fontWeight = FontWeight.Bold)
        }
        Text(
            "會跳出分享選單，可以傳到 LINE、「新增至 Dropbox」、「上傳到雲端硬碟」、Email 等任何 App。",
            color = C.dim, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !busy, onClick = { saveLauncher.launch(BackupManager.suggestedName()) }) {
                Text("儲存到檔案…", color = C.text)
            }
            OutlinedButton(enabled = !busy, onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                Text("從檔案匯入…", color = C.accentSoft)
            }
            if (busy) CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
        Text(
            "「儲存到檔案」可直接存到手機資料夾（檔案總管）、雲端硬碟或 Dropbox；匯入時也可以從這些地方選檔。",
            color = C.dim, fontSize = 12.sp, lineHeight = 18.sp,
        )

        HorizontalDivider(color = C.border)
        Text(
            "另外，手機開啟「Google 備份」時，Android 會自動把鬧鐘資料備份到你的 Google 帳號，重新安裝或換手機登入同一帳號時自動還原。",
            color = C.dim, fontSize = 12.sp, lineHeight = 18.sp,
        )

        message?.let { (ok, msg) -> Text(msg, color = if (ok) C.ok else C.warnText, fontSize = 13.sp, lineHeight = 19.sp) }
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            containerColor = C.surfaceHi,
            title = { Text("匯入備份", color = C.text) },
            text = {
                Text(
                    "合併：保留目前的鬧鐘與計時器，加入備份中的內容（同名群組會合併）。\n\n" +
                        "取代：先刪除目前所有鬧鐘、計時器與群組，再完整還原備份，設定也一併還原。換新手機時用這個。",
                    color = C.textSoft, lineHeight = 22.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    work("匯入中…") { resultText(BackupManager.importFrom(context, uri, ImportMode.REPLACE)) }
                }) { Text("取代", color = C.danger) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingImport = null
                    work("匯入中…") { resultText(BackupManager.importFrom(context, uri, ImportMode.MERGE)) }
                }) { Text("合併", color = C.accentSoft) }
            },
        )
    }
}

private fun resultText(r: ImportResult): String = buildString {
    append("已匯入 ${r.alarms} 個鬧鐘、${r.timers} 個計時器")
    if (r.groups > 0) append("、${r.groups} 個新群組")
    if (r.media > 0) append("、${r.media} 個圖片／鈴聲")
    if (r.settingsApplied) append("，設定已還原")
    append("。計時器以暫停狀態還原。")
}
