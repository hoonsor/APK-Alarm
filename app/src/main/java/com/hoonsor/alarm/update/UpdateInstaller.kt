package com.hoonsor.alarm.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 下載 APK 到 App 快取目錄，再用 PackageInstaller session 安裝。 */
object UpdateInstaller {
    private const val TAG = "UpdateInstaller"
    private const val DIR = "update"

    private val _installError = MutableStateFlow<String?>(null)
    /** 安裝失敗的原因（由 [UpdateInstallReceiver] 回報），UI 顯示後呼叫 [clearError] */
    val installError: StateFlow<String?> = _installError.asStateFlow()
    fun clearError() { _installError.value = null }

    /** 是否已取得「安裝不明應用程式」權限 */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 開啟本 App 的「安裝不明應用程式」設定頁 */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            // 少數廠牌沒有這個頁面，退回一般的應用程式資訊頁
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** 清掉之前下載的 APK（App 啟動或下載前呼叫） */
    fun cleanup(context: Context) {
        runCatching { File(context.cacheDir, DIR).deleteRecursively() }
    }

    /**
     * 下載 APK，[onProgress] 回報 0–1（大小未知時為 -1）。
     * 下載完會確認 APK 的套件名稱相同、版本比目前新，避免裝到錯的檔案。
     */
    suspend fun download(context: Context, release: ReleaseInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        cleanup(context)
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val part = File(dir, "download.part")
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "${UpdateConfig.REPO}/${UpdateChecker.currentVersion}")
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("下載失敗（HTTP ${conn.responseCode}）")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
            if (release.apkSize > 0 && part.length() != release.apkSize) {
                throw IllegalStateException("下載不完整，請再試一次")
            }
        } finally {
            conn.disconnect()
        }
        val apk = File(dir, "update.apk")
        if (!part.renameTo(apk)) throw IllegalStateException("無法儲存下載的檔案")
        verify(context, apk)
        apk
    }

    private fun verify(context: Context, apk: File) {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw IllegalStateException("下載的檔案不是有效的 APK")
        if (info.packageName != context.packageName) throw IllegalStateException("APK 套件名稱不符（${info.packageName}）")
        if (!VersionCompare.isNewer(info.versionName, UpdateChecker.currentVersion)) {
            throw IllegalStateException("APK 版本（${info.versionName}）沒有比目前新")
        }
    }

    /** 把 APK 寫進 PackageInstaller session 並送出；系統會跳出安裝確認畫面 */
    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        _installError.value = null
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
                // Android 12+ 必須 MUTABLE，系統才能把安裝結果放進 extras
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    internal fun reportFailure(context: Context, status: Int, message: String?) {
        val text = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> return // 使用者按了取消，不用提示
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "安裝失敗：新版的簽章與目前安裝的不同（例如目前是用電腦直接安裝的 debug 版）。" +
                    "請先在設定頁「備份與還原」分享備份檔，解除安裝後再安裝新版，然後匯入備份。"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "安裝失敗：手機儲存空間不足"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "安裝被系統阻擋，請確認已允許「安裝不明應用程式」"
            else -> "安裝失敗：${message ?: "未知錯誤（$status）"}"
        }
        Log.w(TAG, "安裝失敗 status=$status message=$message")
        _installError.value = text
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}

/** 接收 PackageInstaller 的安裝結果 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { UpdateInstaller.reportFailure(context, PackageInstaller.STATUS_FAILURE, it.message) }
                }
            }
            // 成功時系統會結束舊版程序；新版啟動後由 MY_PACKAGE_REPLACED 全量重排鬧鐘
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> UpdateInstaller.reportFailure(context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
