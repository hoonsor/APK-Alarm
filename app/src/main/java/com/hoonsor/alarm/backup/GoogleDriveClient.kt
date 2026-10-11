package com.hoonsor.alarm.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.hoonsor.alarm.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Google 雲端硬碟：Google Play 服務的 AuthorizationClient＋drive.file 權限
 * （只能看到本 App 自己建立的檔案，看不到你雲端硬碟裡的其他東西）。
 * 備份放在雲端硬碟根目錄的「HoonsorAlarm 備份」資料夾，網頁版雲端硬碟也看得到。
 *
 * 需要先在 Google Cloud Console 建立 Android OAuth 用戶端（套件名稱＋簽章 SHA-1），步驟見 README。
 */
object GoogleDriveClient {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val FOLDER_NAME = "HoonsorAlarm 備份"
    private const val API = "https://www.googleapis.com/drive/v3"

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    sealed interface AuthStart {
        data object Granted : AuthStart
        data class NeedsConsent(val intentSender: IntentSender) : AuthStart
    }

    /** 設定頁按「連結」：已授權就直接完成，否則回傳同意畫面 */
    suspend fun beginAuth(activity: Activity): AuthStart = withContext(Dispatchers.IO) {
        val r = Tasks.await(Identity.getAuthorizationClient(activity).authorize(request()), 30, TimeUnit.SECONDS)
        if (r.hasResolution()) AuthStart.NeedsConsent(r.pendingIntent!!.intentSender)
        else {
            Graph.settings.gdriveLinked = true
            AuthStart.Granted
        }
    }

    /** 同意畫面回來後呼叫 */
    fun finishAuth(context: Context, data: Intent?) {
        val r = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        if (r.accessToken.isNullOrBlank()) throw CloudAuthException("沒有取得 Google 授權")
        Graph.settings.gdriveLinked = true
    }

    fun unlink() {
        Graph.settings.gdriveLinked = false
        Graph.settings.gdriveFolderId = null
    }

    /** 背景取得權杖；使用者撤銷授權或權杖需要重新同意時丟出 CloudAuthException */
    private fun token(): String {
        val r = Tasks.await(Identity.getAuthorizationClient(Graph.appContext).authorize(request()), 30, TimeUnit.SECONDS)
        if (r.hasResolution()) throw CloudAuthException("Google 雲端硬碟需要重新授權：請到設定頁按「連結 Google 雲端硬碟」")
        return r.accessToken ?: throw CloudAuthException("沒有取得 Google 授權")
    }

    private fun folderId(token: String): String {
        Graph.settings.gdriveFolderId?.let { id ->
            // 確認資料夾還在（可能被使用者刪掉或丟到垃圾桶）
            val ok = runCatching {
                val j = getJson("$API/files/$id?fields=id,trashed", token)
                !j.optBoolean("trashed")
            }.getOrDefault(false)
            if (ok) return id
        }
        val q = "name = '${FOLDER_NAME.replace("'", "\\'")}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val found = getJson("$API/files?q=${enc(q)}&spaces=drive&fields=files(id)", token).optJSONArray("files")
        val id = found?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() } ?: run {
            val conn = open("$API/files?fields=id", "POST", token).apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            try {
                conn.outputStream.use {
                    it.write(JSONObject().put("name", FOLDER_NAME).put("mimeType", "application/vnd.google-apps.folder").toString().toByteArray())
                }
                check(conn, "建立資料夾")
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getString("id")
            } finally {
                conn.disconnect()
            }
        }
        Graph.settings.gdriveFolderId = id
        return id
    }

    suspend fun upload(file: File) = withContext(Dispatchers.IO) {
        val token = token()
        val parent = folderId(token)
        val boundary = "hoonsor_" + UUID.randomUUID().toString().replace("-", "")
        val meta = JSONObject().put("name", file.name).put("parents", org.json.JSONArray().put(parent)).toString()
        val head = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: ${BackupManager.MIME}\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val conn = open("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", "POST", token).apply {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            setFixedLengthStreamingMode(head.size + file.length() + tail.size)
        }
        try {
            conn.outputStream.use { out ->
                out.write(head)
                file.inputStream().use { it.copyTo(out) }
                out.write(tail)
            }
            check(conn, "上傳")
        } finally {
            conn.disconnect()
        }
    }

    suspend fun list(): List<BackupEntry> = withContext(Dispatchers.IO) {
        val token = token()
        val parent = folderId(token)
        val q = "'$parent' in parents and trashed = false"
        val arr = getJson("$API/files?q=${enc(q)}&orderBy=createdTime%20desc&pageSize=100&fields=files(id,name,size,createdTime)", token)
            .optJSONArray("files") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val f = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = f.optString("name")
            if (!BackupManager.isBackupName(name)) null
            else BackupEntry(
                name, f.optString("size").toLongOrNull() ?: 0L,
                runCatching { Instant.parse(f.optString("createdTime")).toEpochMilli() }.getOrDefault(0L),
                f.optString("id"),
            )
        }.sortedByDescending { it.name }
    }

    suspend fun download(id: String, dest: File) = withContext(Dispatchers.IO) {
        val conn = open("$API/files/$id?alt=media", "GET", token())
        try {
            check(conn, "下載")
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun prune(keep: Int) {
        val old = list().drop(keep)
        if (old.isEmpty()) return
        withContext(Dispatchers.IO) {
            val token = token()
            old.forEach { e ->
                runCatching {
                    val conn = open("$API/files/${e.ref}", "DELETE", token)
                    try { check(conn, "刪除舊備份") } finally { conn.disconnect() }
                }
            }
        }
    }

    // ---------- HTTP ----------

    private fun open(url: String, method: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun getJson(url: String, token: String): JSONObject {
        val conn = open(url, "GET", token)
        try {
            check(conn, "連線")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun check(conn: HttpURLConnection, what: String) {
        val code = conn.responseCode
        if (code in 200..299) return
        val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code == 401) throw CloudAuthException("Google 雲端硬碟授權已失效，請重新連結")
        if (code == 403 && err.contains("accessNotConfigured")) {
            throw CloudAuthException("Google Cloud 專案尚未啟用 Google Drive API（見 README 設定步驟）")
        }
        throw IOException("Google 雲端硬碟$what 失敗（HTTP $code）")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
