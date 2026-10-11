package com.hoonsor.alarm.backup

import android.util.Base64
import com.hoonsor.alarm.Graph
import com.hoonsor.alarm.data.KeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

class CloudAuthException(message: String) : Exception(message)

/**
 * Dropbox：OAuth 2 PKCE（不需要 App secret）＋「App folder」權限，只能存取 Dropbox/應用程式/<你的 App 名稱>。
 * 授權採「顯示代碼」流程：瀏覽器登入後 Dropbox 顯示一組代碼，貼回 App 即完成，不需要設定 redirect URI。
 */
object DropboxClient {
    private var cachedToken: String? = null
    private var cachedUntil = 0L

    fun isLinked(): Boolean = Graph.settings.dropboxAppKey.isNotBlank() && KeyVault.decrypt(Graph.settings.dropboxRefreshCipher) != null

    /** 第一步：產生登入網址（同時記住 PKCE verifier） */
    fun authorizeUrl(appKey: String): String {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val v = b64url(bytes)
        // 存在設定裡：切到瀏覽器登入時 App 可能被系統關掉，回來仍能完成
        Graph.settings.dropboxPkceVerifier = v
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.US_ASCII)))
        return "https://www.dropbox.com/oauth2/authorize?client_id=${enc(appKey)}&response_type=code" +
            "&code_challenge=$challenge&code_challenge_method=S256&token_access_type=offline"
    }

    /** 第二步：用 Dropbox 顯示的代碼換 refresh token，加密保存 */
    suspend fun finishAuth(appKey: String, code: String) = withContext(Dispatchers.IO) {
        val v = Graph.settings.dropboxPkceVerifier ?: throw CloudAuthException("請先按「開啟 Dropbox 登入」再貼上代碼")
        val json = postForm(
            "https://api.dropboxapi.com/oauth2/token",
            mapOf("code" to code.trim(), "grant_type" to "authorization_code", "code_verifier" to v, "client_id" to appKey.trim()),
        )
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: throw CloudAuthException("Dropbox 沒有回傳 refresh token")
        Graph.settings.dropboxAppKey = appKey.trim()
        Graph.settings.dropboxRefreshCipher = KeyVault.encrypt(refresh) ?: throw CloudAuthException("無法加密保存登入資訊")
        cachedToken = json.optString("access_token").takeIf { it.isNotBlank() }
        cachedUntil = System.currentTimeMillis() + json.optLong("expires_in", 0) * 1000 - 60_000
        Graph.settings.dropboxPkceVerifier = null
    }

    fun unlink() {
        Graph.settings.dropboxRefreshCipher = null
        cachedToken = null
        cachedUntil = 0
    }

    private fun accessToken(): String {
        cachedToken?.let { if (System.currentTimeMillis() < cachedUntil) return it }
        val s = Graph.settings
        val refresh = KeyVault.decrypt(s.dropboxRefreshCipher) ?: throw CloudAuthException("尚未連結 Dropbox")
        val json = postForm(
            "https://api.dropboxapi.com/oauth2/token",
            mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to s.dropboxAppKey),
        )
        val token = json.optString("access_token").takeIf { it.isNotBlank() } ?: throw CloudAuthException("Dropbox 授權已失效，請重新連結")
        cachedToken = token
        cachedUntil = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000 - 60_000
        return token
    }

    suspend fun upload(file: File) = withContext(Dispatchers.IO) {
        val conn = open("https://content.dropboxapi.com/2/files/upload").apply {
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Dropbox-API-Arg", JSONObject().put("path", "/" + file.name).put("mode", "overwrite").toString())
            setFixedLengthStreamingMode(file.length())
        }
        try {
            conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            check(conn, "上傳")
        } finally {
            conn.disconnect()
        }
    }

    suspend fun list(): List<BackupEntry> = withContext(Dispatchers.IO) {
        val json = rpc("https://api.dropboxapi.com/2/files/list_folder", JSONObject().put("path", "").toString())
        val arr = json.optJSONArray("entries") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = e.optString("name")
            if (e.optString(".tag") != "file" || !BackupManager.isBackupName(name)) null
            else BackupEntry(name, e.optLong("size"), 0L, e.optString("path_lower"))
        }.sortedByDescending { it.name }
    }

    suspend fun download(path: String, dest: File) = withContext(Dispatchers.IO) {
        val conn = open("https://content.dropboxapi.com/2/files/download").apply {
            setRequestProperty("Dropbox-API-Arg", JSONObject().put("path", path).toString())
            doOutput = false
        }
        try {
            check(conn, "下載")
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun prune(keep: Int) {
        list().drop(keep).forEach { e ->
            runCatching { rpc("https://api.dropboxapi.com/2/files/delete_v2", JSONObject().put("path", e.ref).toString()) }
        }
    }

    // ---------- HTTP ----------

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15_000
        readTimeout = 60_000
        doOutput = true
        setRequestProperty("Authorization", "Bearer ${accessToken()}")
    }

    private fun rpc(url: String, body: String): JSONObject {
        val conn = open(url).apply { setRequestProperty("Content-Type", "application/json") }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            check(conn, "連線")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() }.ifBlank { "{}" })
        } finally {
            conn.disconnect()
        }
    }

    private fun check(conn: HttpURLConnection, what: String) {
        val code = conn.responseCode
        if (code in 200..299) return
        val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code == 401) {
            cachedToken = null
            throw CloudAuthException("Dropbox 授權已失效，請重新連結")
        }
        if (code == 409 && err.contains("not_found")) throw IOException("Dropbox 找不到檔案")
        if (code == 400 && err.contains("scope")) throw CloudAuthException("Dropbox App 權限不足：請在開發者後台勾選 files.content.write 與 files.content.read 後重新連結")
        throw IOException("Dropbox $what 失敗（HTTP $code）")
    }

    private fun postForm(url: String, params: Map<String, String>): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            val body = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val desc = runCatching { JSONObject(text).optString("error_description") }.getOrNull().orEmpty()
                throw CloudAuthException(
                    when {
                        text.contains("invalid_grant") -> "代碼錯誤或已過期，請重新開啟 Dropbox 登入"
                        text.contains("invalid_client") -> "App key 錯誤"
                        else -> "Dropbox 登入失敗（HTTP $code）$desc"
                    },
                )
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
