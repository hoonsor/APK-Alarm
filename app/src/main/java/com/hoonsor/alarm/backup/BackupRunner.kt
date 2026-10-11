package com.hoonsor.alarm.backup

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.common.api.ApiException
import com.hoonsor.alarm.Graph
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

enum class CloudTarget(val key: String, val label: String) {
    NONE("none", "只存手機"),
    DROPBOX("dropbox", "Dropbox"),
    GDRIVE("gdrive", "Google 雲端硬碟"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: NONE
    }
}

/** 執行一次備份：先存手機（方案 B），再依設定上傳雲端（方案 C）。手動「立即備份」與每日排程共用。 */
object BackupRunner {
    private const val TAG = "BackupRunner"
    private const val WORK_NAME = "auto_backup"

    data class Outcome(val ok: Boolean, val message: String, val retryable: Boolean = false)

    suspend fun runOnce(context: Context): Outcome {
        val s = Graph.settings
        val temp: File
        try {
            temp = BackupManager.exportToTemp(context, s.backupIncludeMedia)
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            return record(Outcome(false, "產生備份失敗：${describe(e)}"))
        }
        val size = "%.1f KB".format(temp.length() / 1024.0)
        val parts = mutableListOf<String>()
        var ok = true
        var retryable = false
        try {
            runCatching {
                LocalBackupStore.save(context, temp)
                LocalBackupStore.prune(context, s.backupKeep)
            }.onSuccess { parts += "手機 ✓" }
                .onFailure { ok = false; parts += "手機失敗：${describe(it)}"; Log.e(TAG, "local failed", it) }

            val cloud = CloudTarget.of(s.backupCloud)
            if (cloud != CloudTarget.NONE) {
                runCatching {
                    when (cloud) {
                        CloudTarget.DROPBOX -> { DropboxClient.upload(temp); DropboxClient.prune(s.backupKeep) }
                        CloudTarget.GDRIVE -> { GoogleDriveClient.upload(temp); GoogleDriveClient.prune(s.backupKeep) }
                        CloudTarget.NONE -> Unit
                    }
                }.onSuccess { parts += "${cloud.label} ✓" }
                    .onFailure {
                        ok = false
                        retryable = it is IOException
                        parts += "${cloud.label}失敗：${describe(it)}"
                        Log.e(TAG, "cloud failed", it)
                    }
            }
        } finally {
            temp.delete()
        }
        return record(Outcome(ok, parts.joinToString("、") + if (ok) "（$size）" else "", retryable))
    }

    private fun record(o: Outcome): Outcome {
        val s = Graph.settings
        s.lastBackupAt = System.currentTimeMillis()
        s.lastBackupOk = o.ok
        s.lastBackupMessage = o.message
        return o
    }

    fun describe(e: Throwable): String {
        val cause = if (e is ExecutionException && e.cause != null) e.cause!! else e
        return when {
            cause is CloudAuthException -> cause.message ?: "授權失敗"
            cause is ApiException && cause.statusCode == 10 ->
                "Google Cloud 設定不符（OAuth 用戶端的套件名稱或 SHA-1 不對，見 README）"
            cause is ApiException -> "Google 授權錯誤（代碼 ${cause.statusCode}）"
            cause is UnknownHostException -> "沒有網路"
            else -> cause.message ?: cause.javaClass.simpleName
        }
    }

    /** 依設定排定或取消每日自動備份；App 啟動與設定變更時呼叫 */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val s = Graph.settings
        if (!s.autoBackupEnabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiredNetworkType(if (s.backupCloud == "none") NetworkType.NOT_REQUIRED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val o = BackupRunner.runOnce(applicationContext)
        return when {
            o.ok -> Result.success()
            o.retryable && runAttemptCount < 3 -> Result.retry()
            else -> Result.success() // 失敗原因已記錄在設定頁，明天照常再試
        }
    }
}
