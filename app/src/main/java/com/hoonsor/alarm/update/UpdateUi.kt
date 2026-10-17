package com.hoonsor.alarm.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hoonsor.alarm.ui.theme.C
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 主畫面掛這個：啟動時（每天最多一次）在背景檢查更新，有新版才跳出對話框。
 * 任何錯誤都安靜略過，不影響 App 其他功能。
 */
@Composable
fun AutoUpdateCheck() {
    val context = LocalContext.current
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    LaunchedEffect(Unit) {
        UpdateInstaller.cleanup(context)
        release = runCatching { UpdateChecker.autoCheck(context) }.getOrNull()
    }
    release?.let { r ->
        UpdateDialog(
            release = r,
            onDismiss = { release = null },
            onSkip = { UpdateChecker.skip(context, r.tag); release = null },
        )
    }
}

/** 設定頁的「App 更新」卡片：顯示目前版本與「檢查更新」按鈕 */
@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface)
            .border(1.dp, C.border, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("App 更新", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("目前版本 v${UpdateChecker.currentVersion}", color = C.muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    message = null
                    scope.launch {
                        when (val r = UpdateChecker.check()) {
                            is CheckResult.Available -> release = r.release
                            CheckResult.UpToDate -> message = true to "已經是最新版"
                            is CheckResult.Failed -> message = false to "無法檢查更新：${r.reason}"
                        }
                        checking = false
                    }
                },
                shape = RoundedCornerShape(12.dp),
            ) { Text("檢查更新", color = C.accentSoft) }
            if (checking) CircularProgressIndicator(color = C.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        }
        Text(
            "App 每天開啟時會自動檢查一次。新版從 GitHub 下載後由系統安裝，鬧鐘資料與設定都會保留，更新完成會自動重新排程。",
            color = C.dim, fontSize = 12.sp, lineHeight = 18.sp,
        )
        message?.let { (ok, msg) -> Text(msg, color = if (ok) C.ok else C.warnText, fontSize = 13.sp, lineHeight = 19.sp) }
    }

    release?.let { r -> UpdateDialog(release = r, onDismiss = { release = null }) }
}

private enum class Phase { CONFIRM, NEED_PERMISSION, DOWNLOADING, INSTALLING, ERROR }

/** 顯示新版資訊；使用者確認後依序：檢查安裝權限 → 下載 → 交給系統安裝 */
@Composable
fun UpdateDialog(release: ReleaseInfo, onDismiss: () -> Unit, onSkip: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.CONFIRM) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val installError by UpdateInstaller.installError.collectAsState()

    fun start() {
        if (!UpdateInstaller.canInstall(context)) {
            phase = Phase.NEED_PERMISSION
            return
        }
        phase = Phase.DOWNLOADING
        progress = 0f
        job = scope.launch {
            try {
                val apk = UpdateInstaller.download(context, release) { p -> progress = p }
                phase = Phase.INSTALLING
                UpdateInstaller.install(context, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                phase = Phase.ERROR
            }
        }
    }

    // 從「安裝不明應用程式」設定頁回來時，若已允許就直接開始下載
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (phase == Phase.NEED_PERMISSION && UpdateInstaller.canInstall(context)) start()
    }
    LaunchedEffect(installError) {
        val e = installError ?: return@LaunchedEffect
        if (phase == Phase.INSTALLING) {
            error = e
            phase = Phase.ERROR
            UpdateInstaller.clearError()
        }
    }

    fun close() {
        job?.cancel()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (phase != Phase.DOWNLOADING) close() },
        containerColor = C.surfaceHi,
        title = { Text("有新版本 v${release.version}", color = C.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (phase) {
                    Phase.CONFIRM -> {
                        Text("目前版本 v${UpdateChecker.currentVersion}", color = C.muted, fontSize = 13.sp)
                        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                            Text(release.notes.ifBlank { "（這個版本沒有附更新說明）" }, color = C.textSoft, fontSize = 14.sp, lineHeight = 21.sp)
                        }
                    }
                    Phase.NEED_PERMISSION -> Text(
                        "第一次更新需要允許本 App「安裝不明應用程式」。\n\n按「前往設定」後打開開關，再按返回鍵回到這裡，就會自動開始下載。",
                        color = C.textSoft, lineHeight = 22.sp,
                    )
                    Phase.DOWNLOADING -> {
                        Text(if (progress >= 0f) "下載中… ${(progress * 100).toInt()}%" else "下載中…", color = C.textSoft)
                        if (progress >= 0f) {
                            LinearProgressIndicator(progress = { progress }, color = C.accent, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(color = C.accent, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Phase.INSTALLING -> Text(
                        "已交給系統安裝，請在系統畫面按「更新」或「安裝」。安裝完成後 App 會重新啟動，鬧鐘會自動重新排程。",
                        color = C.textSoft, lineHeight = 22.sp,
                    )
                    Phase.ERROR -> {
                        Text(error, color = C.warnText, lineHeight = 22.sp)
                        Text("也可以改用瀏覽器到 GitHub 下載 APK 手動安裝。", color = C.dim, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            when (phase) {
                Phase.CONFIRM -> TextButton(onClick = ::start) { Text("下載並更新", color = C.accentSoft) }
                Phase.NEED_PERMISSION -> TextButton(onClick = { UpdateInstaller.openInstallPermissionSettings(context) }) {
                    Text("前往設定", color = C.accentSoft)
                }
                Phase.ERROR -> TextButton(onClick = {
                    val url = release.pageUrl.ifBlank { UpdateConfig.RELEASES_PAGE }
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }) { Text("用瀏覽器下載", color = C.accentSoft) }
                Phase.DOWNLOADING, Phase.INSTALLING -> Unit
            }
        },
        dismissButton = {
            Row {
                if (phase == Phase.CONFIRM && onSkip != null) {
                    TextButton(onClick = onSkip) { Text("略過此版", color = C.muted) }
                }
                TextButton(onClick = ::close) {
                    Text(
                        when (phase) {
                            Phase.CONFIRM -> "稍後"
                            Phase.DOWNLOADING -> "取消"
                            else -> "關閉"
                        },
                        color = C.muted,
                    )
                }
            }
        },
    )
}
