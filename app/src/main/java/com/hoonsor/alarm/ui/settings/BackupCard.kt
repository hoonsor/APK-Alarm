package com.hoonsor.alarm.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.backup.BackupEntry
import com.hoonsor.alarm.backup.BackupManager
import com.hoonsor.alarm.backup.BackupRunner
import com.hoonsor.alarm.backup.CloudTarget
import com.hoonsor.alarm.backup.DropboxClient
import com.hoonsor.alarm.backup.GoogleDriveClient
import com.hoonsor.alarm.backup.ImportMode
import com.hoonsor.alarm.backup.ImportResult
import com.hoonsor.alarm.backup.LocalBackupStore
import com.hoonsor.alarm.domain.Fmt
import com.hoonsor.alarm.ui.common.ChoiceDialog
import com.hoonsor.alarm.ui.common.ChoiceItem
import com.hoonsor.alarm.ui.common.MiniSwitch
import com.hoonsor.alarm.ui.common.textFieldColors
import com.hoonsor.alarm.ui.theme.C
import kotlinx.coroutines.launch
import java.io.File

/** 等待使用者選「合併／取代」的匯入來源 */
private class PendingImport(val label: String, val run: suspend (ImportMode) -> ImportResult)

/** 設定頁：備份與還原（手動匯出匯入、Android 系統備份、每日自動備份、Dropbox／Google 雲端硬碟） */
@Composable
fun BackupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = Graph.settings

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var manualMedia by remember { mutableStateOf(true) }
    var autoOn by remember { mutableStateOf(s.autoBackupEnabled) }
    var cloud by remember { mutableStateOf(CloudTarget.of(s.backupCloud)) }
    var keep by remember { mutableStateOf(s.backupKeep) }
    var autoMedia by remember { mutableStateOf(s.backupIncludeMedia) }
    var lastInfo by remember { mutableStateOf(lastBackupText()) }
    var dropboxLinked by remember { mutableStateOf(DropboxClient.isLinked()) }
    var gdriveLinked by remember { mutableStateOf(s.gdriveLinked) }
    var appKey by remember { mutableStateOf(s.dropboxAppKey) }
    var dropboxCode by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    var restorePick by remember { mutableStateOf(false) }
    var restoreList by remember { mutableStateOf<Pair<String, List<Pair<BackupEntry, PendingImport>>>?>(null) }

    fun work(start: String, block: suspend () -> String) {
        busy = true
        message = true to start
        scope.launch {
            message = try {
                true to block()
            } catch (e: Exception) {
                false to BackupRunner.describe(e)
            }
            busy = false
            lastInfo = lastBackupText()
        }
    }

    fun applyAuto() {
        s.autoBackupEnabled = autoOn
        s.backupCloud = cloud.key
        s.backupKeep = keep
        s.backupIncludeMedia = autoMedia
        BackupRunner.schedule(context)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.MIME)) { uri ->
        if (uri != null) work("匯出中…") {
            BackupManager.exportTo(context, uri, manualMedia)
            "已匯出備份" + if (manualMedia) "（含圖片與鈴聲）" else ""
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) pending = PendingImport("選擇的備份檔") { mode -> BackupManager.importFrom(context, uri, mode) }
    }
    val googleConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        try {
            GoogleDriveClient.finishAuth(context, r.data)
            gdriveLinked = true
            message = true to "已連結 Google 雲端硬碟"
        } catch (e: Exception) {
            message = false to "Google 連結失敗：${BackupRunner.describe(e)}"
        }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface)
            .border(1.dp, C.border, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("備份與還原", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("備份鬧鐘、群組、計時器與設定。API key 與雲端登入不會備份，換手機後需重新填寫。", color = C.muted, fontSize = 12.sp, lineHeight = 18.sp)

        // ---------- 手動 ----------
        SectionTitle("手動匯出／匯入")
        CheckRow("包含響鈴圖片與自訂鈴聲", "檔案會變大；不勾選時圖片與自訂鈴聲改用預設", manualMedia) { manualMedia = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { exportLauncher.launch(BackupManager.suggestedName()) }) { Text("匯出備份檔…", color = C.accentSoft) }
            OutlinedButton(enabled = !busy, onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                Text("從檔案匯入…", color = C.text)
            }
        }
        Text("存檔時可以直接選「雲端硬碟」或「Dropbox」，備份檔就會存到雲端。", color = C.dim, fontSize = 12.sp, lineHeight = 18.sp)

        HorizontalDivider(color = C.border)

        // ---------- 自動 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("每天自動備份", color = C.textSoft, fontSize = 15.sp)
                Text("存到手機「${LocalBackupStore.locationLabel()}」，可再同步到雲端", color = C.dim, fontSize = 12.sp)
            }
            MiniSwitch(autoOn, { autoOn = !autoOn; applyAuto() }, contentDescription = "每天自動備份")
        }
        if (autoOn) {
            Text("同時上傳到", color = C.muted, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudTarget.entries.forEach { t ->
                    Pill(t.label, cloud == t) {
                        cloud = t; applyAuto()
                        if (t == CloudTarget.DROPBOX && !dropboxLinked) message = false to "請先在下方連結 Dropbox"
                        if (t == CloudTarget.GDRIVE && !gdriveLinked) message = false to "請先在下方連結 Google 雲端硬碟"
                    }
                }
            }
            Text("各保留最近幾份", color = C.muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 7, 14, 30).forEach { n -> Pill("$n 份", keep == n) { keep = n; applyAuto() } }
            }
            CheckRow("自動備份包含圖片與鈴聲", "會佔用較多空間與流量", autoMedia) { autoMedia = it; applyAuto() }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                applyAuto()
                work("備份中…") {
                    val o = BackupRunner.runOnce(context)
                    if (!o.ok) throw IllegalStateException(o.message)
                    "備份完成：${o.message}"
                }
            }) { Text("立即備份", color = C.accentSoft) }
            OutlinedButton(enabled = !busy, onClick = { restorePick = true }) { Text("從備份還原…", color = C.text) }
            if (busy) CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
        lastInfo?.let { (ok, t) -> Text(t, color = if (ok) C.muted else C.warnText, fontSize = 12.sp, lineHeight = 18.sp) }

        HorizontalDivider(color = C.border)

        // ---------- Dropbox ----------
        SectionTitle("Dropbox")
        if (dropboxLinked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已連結（備份存在 Dropbox/應用程式/你的 App 名稱）", color = C.ok, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    DropboxClient.unlink(); dropboxLinked = false
                    if (cloud == CloudTarget.DROPBOX) { cloud = CloudTarget.NONE; applyAuto() }
                }) { Text("解除連結", color = C.danger) }
            }
        } else {
            OutlinedTextField(
                value = appKey, onValueChange = { appKey = it.trim() }, singleLine = true,
                label = { Text("Dropbox App key") },
                shape = RoundedCornerShape(12.dp), colors = textFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = appKey.isNotBlank(), onClick = {
                    s.dropboxAppKey = appKey
                    val url = DropboxClient.authorizeUrl(appKey)
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { message = false to "無法開啟瀏覽器" }
                }) { Text("1. 開啟 Dropbox 登入", color = C.accentSoft) }
            }
            OutlinedTextField(
                value = dropboxCode, onValueChange = { dropboxCode = it.trim() }, singleLine = true,
                label = { Text("2. 貼上 Dropbox 顯示的代碼") },
                shape = RoundedCornerShape(12.dp), colors = textFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(enabled = !busy && appKey.isNotBlank() && dropboxCode.isNotBlank(), onClick = {
                work("連結中…") {
                    DropboxClient.finishAuth(appKey, dropboxCode)
                    dropboxLinked = true
                    dropboxCode = ""
                    "已連結 Dropbox"
                }
            }) { Text("3. 完成連結", color = C.text) }
            Text("App key 需要先在 Dropbox 開發者後台建立（約 5 分鐘），步驟見專案 README。", color = C.dim, fontSize = 12.sp, lineHeight = 18.sp)
        }

        HorizontalDivider(color = C.border)

        // ---------- Google 雲端硬碟 ----------
        SectionTitle("Google 雲端硬碟")
        if (gdriveLinked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已連結（備份存在雲端硬碟「HoonsorAlarm 備份」資料夾）", color = C.ok, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    GoogleDriveClient.unlink(); gdriveLinked = false
                    if (cloud == CloudTarget.GDRIVE) { cloud = CloudTarget.NONE; applyAuto() }
                }) { Text("解除連結", color = C.danger) }
            }
        } else {
            OutlinedButton(enabled = !busy, onClick = {
                val activity = context.findActivity()
                if (activity == null) {
                    message = false to "無法開啟 Google 授權畫面"
                } else work("連結中…") {
                    when (val r = GoogleDriveClient.beginAuth(activity)) {
                        is GoogleDriveClient.AuthStart.Granted -> { gdriveLinked = true; "已連結 Google 雲端硬碟" }
                        is GoogleDriveClient.AuthStart.NeedsConsent -> {
                            googleConsent.launch(IntentSenderRequest.Builder(r.intentSender).build())
                            "請在 Google 畫面中允許存取"
                        }
                    }
                }
            }) { Text("連結 Google 雲端硬碟", color = C.accentSoft) }
            Text("需要先在 Google Cloud Console 建立 Android OAuth 用戶端並啟用 Drive API（約 15 分鐘），步驟見專案 README。", color = C.dim, fontSize = 12.sp, lineHeight = 18.sp)
        }

        HorizontalDivider(color = C.border)
        SectionTitle("Android 系統備份")
        Text(
            "已啟用：手機開啟「Google 備份」時，Android 每天會在充電、連 Wi-Fi 時把鬧鐘資料備份到你的 Google 帳號；" +
                "重新安裝 App 或換新手機登入同一個帳號時自動還原（不包含 API key）。",
            color = C.dim, fontSize = 12.sp, lineHeight = 18.sp,
        )

        message?.let { (ok, msg) -> Text(msg, color = if (ok) C.ok else C.warnText, fontSize = 13.sp, lineHeight = 19.sp) }
    }

    // ---------- 還原：選來源 ----------
    if (restorePick) {
        ChoiceDialog(
            title = "從哪裡還原",
            items = listOf(
                ChoiceItem("手機自動備份", LocalBackupStore.locationLabel()) {
                    restorePick = false
                    work("讀取中…") {
                        val list = LocalBackupStore.list(context).map { e ->
                            e to PendingImport(e.name) { mode -> BackupManager.importFrom(context, Uri.parse(e.ref), mode) }
                        }
                        if (list.isNotEmpty()) restoreList = "手機自動備份" to list
                        if (list.isEmpty()) "手機裡沒有找到自動備份" else "找到 ${list.size} 份備份"
                    }
                },
                ChoiceItem("Dropbox", if (dropboxLinked) null else "尚未連結") {
                    restorePick = false
                    if (dropboxLinked) work("讀取中…") {
                        val list = DropboxClient.list().map { e ->
                            e to PendingImport(e.name) { mode -> downloadThenImport(context, mode) { DropboxClient.download(e.ref, it) } }
                        }
                        if (list.isNotEmpty()) restoreList = "Dropbox" to list
                        if (list.isEmpty()) "Dropbox 裡沒有備份" else "找到 ${list.size} 份備份"
                    } else { message = false to "請先連結 Dropbox" }
                },
                ChoiceItem("Google 雲端硬碟", if (gdriveLinked) null else "尚未連結") {
                    restorePick = false
                    if (gdriveLinked) work("讀取中…") {
                        val list = GoogleDriveClient.list().map { e ->
                            e to PendingImport(e.name) { mode -> downloadThenImport(context, mode) { GoogleDriveClient.download(e.ref, it) } }
                        }
                        if (list.isNotEmpty()) restoreList = "Google 雲端硬碟" to list
                        if (list.isEmpty()) "雲端硬碟裡沒有備份" else "找到 ${list.size} 份備份"
                    } else { message = false to "請先連結 Google 雲端硬碟" }
                },
                ChoiceItem("選擇其他檔案…") { restorePick = false; importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
            ),
            onDismiss = { restorePick = false },
        )
    }
    restoreList?.let { (source, list) ->
        ChoiceDialog(
            title = "$source 的備份",
            items = list.map { (e, p) ->
                ChoiceItem(prettyStamp(e.name.removePrefix("HoonsorAlarm-backup-").removeSuffix(".zip")), "%.1f KB".format(e.size / 1024.0)) {
                    restoreList = null; pending = p
                }
            },
            onDismiss = { restoreList = null },
        )
    }

    // ---------- 匯入：選模式 ----------
    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = C.surfaceHi,
            title = { Text("匯入「${p.label}」", color = C.text) },
            text = {
                Text(
                    "合併：保留目前的鬧鐘與計時器，加入備份中的內容（同名群組會合併）。\n\n" +
                        "取代：先刪除目前所有鬧鐘、計時器與群組，再完整還原備份，設定也一併還原。換新手機時用這個。",
                    color = C.textSoft, lineHeight = 22.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    work("匯入中…") { resultText(p.run(ImportMode.REPLACE)) }
                }) { Text("取代", color = C.danger) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pending = null
                    work("匯入中…") { resultText(p.run(ImportMode.MERGE)) }
                }) { Text("合併", color = C.accentSoft) }
            },
        )
    }
}

private suspend fun downloadThenImport(context: Context, mode: ImportMode, download: suspend (File) -> Unit): ImportResult {
    val f = File(context.cacheDir, "cloud_restore.zip")
    try {
        download(f)
        return BackupManager.importFile(context, f, mode)
    } finally {
        f.delete()
    }
}

private fun resultText(r: ImportResult): String = buildString {
    append("已匯入 ${r.alarms} 個鬧鐘、${r.timers} 個計時器")
    if (r.groups > 0) append("、${r.groups} 個新群組")
    if (r.media > 0) append("、${r.media} 個圖片／鈴聲")
    if (r.settingsApplied) append("，設定已還原")
    append("。計時器以暫停狀態還原。")
}

private fun lastBackupText(): Pair<Boolean, String>? {
    val s = Graph.settings
    if (s.lastBackupAt <= 0) return null
    return s.lastBackupOk to "上次備份 ${Fmt.dateTime(s.lastBackupAt)}：${s.lastBackupMessage}"
}

/** 20261011-2230 → 2026/10/11 22:30 */
private fun prettyStamp(stamp: String): String =
    Regex("(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})").matchEntire(stamp)?.destructured?.let { (y, m, d, h, mi) -> "$y/$m/$d $h:$mi" } ?: stamp

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = C.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun CheckRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked, onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = C.accent, checkmarkColor = C.onAccent, uncheckedColor = C.chipBorder),
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, fontSize = 14.sp)
            Text(detail, color = C.dim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (selected) C.accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) C.accent.copy(alpha = 0.6f) else C.chipBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) C.accentSoft else C.textSoft, fontSize = 13.sp, maxLines = 1) }
}
