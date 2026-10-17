package com.hoonsor.alarm.update

import android.content.Context
import android.util.Log
import com.hoonsor.alarm.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * 複製到其他 APP 時，只需要改這裡（以及 UpdateUi.kt 的佈景主題 import）。
 * 套件需要：BuildConfig（buildConfig = true）、INTERNET 與 REQUEST_INSTALL_PACKAGES 權限、
 * manifest 註冊 UpdateInstallReceiver。
 */
object UpdateConfig {
    const val OWNER = "hoonsor"
    const val REPO = "APK-Alarm"
    /** CI 產生的 APK 檔名前綴（HoonsorAlarm-vX.Y.Z.apk） */
    const val APK_PREFIX = "HoonsorAlarm-"
    const val LATEST_API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"
}

sealed interface CheckResult {
    data class Available(val release: ReleaseInfo) : CheckResult
    data object UpToDate : CheckResult
    data class Failed(val reason: String) : CheckResult
}

/**
 * 查詢 GitHub 最新正式版。所有錯誤都轉成 [CheckResult.Failed]，不會丟出例外，
 * 也不碰排程與響鈴相關的任何東西。
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val PREFS = "app_update"
    private const val KEY_LAST_DAY = "lastCheckEpochDay"
    private const val KEY_SKIPPED = "skippedTag"

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val json = httpGet(UpdateConfig.LATEST_API)
            val release = ReleaseInfo.parse(json, UpdateConfig.APK_PREFIX)
                ?: return@withContext CheckResult.Failed("最新版沒有可下載的 APK")
            if (VersionCompare.isNewer(release.tag, currentVersion)) CheckResult.Available(release) else CheckResult.UpToDate
        } catch (e: Exception) {
            Log.w(TAG, "檢查更新失敗", e)
            CheckResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 啟動時的自動檢查：每天最多成功查詢一次、略過使用者選擇「略過此版」的版本。
     * 有新版時回傳 Release，其他情況（含網路錯誤）一律安靜回傳 null。
     */
    suspend fun autoCheck(context: Context): ReleaseInfo? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toEpochDay()
        if (prefs.getLong(KEY_LAST_DAY, -1L) == today) return null
        val result = check()
        if (result is CheckResult.Failed) return null // 沒網路時下次開啟再試
        prefs.edit().putLong(KEY_LAST_DAY, today).apply()
        val release = (result as? CheckResult.Available)?.release ?: return null
        return release.takeIf { it.tag != prefs.getString(KEY_SKIPPED, null) }
    }

    fun skip(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SKIPPED, tag).apply()
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "${UpdateConfig.REPO}/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            when {
                code == 404 -> throw IllegalStateException("還沒有發布任何正式版")
                code == 403 || code == 429 -> throw IllegalStateException("GitHub 查詢次數已達上限，請稍後再試")
                code !in 200..299 -> throw IllegalStateException("GitHub 回應 $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
