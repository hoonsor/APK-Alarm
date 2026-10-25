// =====================================================================
// Hoonsor Agent SDK（提供者端）— 單檔、不依賴 AndroidX，整份複製到各 APP 使用
// 來源（唯一真實來源）：hoonsor/APK-VoiceHub  app/src/main/java/tw/hoonsor/agent/HoonsorAgent.kt
// 協定規格：hoonsor/APK-VoiceHub  docs/AGENT_PROTOCOL.md
// 不要在各 APP 裡修改這個檔案；要改請改來源並遞增 SDK_VERSION，再複製回各 APP。
// =====================================================================
package tw.hoonsor.agent

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object HoonsorAgent {
    const val API = 1
    const val SDK_VERSION = 1
    const val PROVIDER_ACTION = "tw.hoonsor.agent.PROVIDER"
    const val PERMISSION = "tw.hoonsor.permission.AGENT"
    const val EXTRA_JSON = "json"
    const val DEFAULT_TIMEOUT_MS = 5_000L

    const val INVALID_PARAMS = "INVALID_PARAMS"
    const val NOT_FOUND = "NOT_FOUND"
    const val AMBIGUOUS = "AMBIGUOUS"
    const val NEEDS_CONFIRMATION = "NEEDS_CONFIRMATION"
    const val NEEDS_UI = "NEEDS_UI"
    const val PERMISSION_DENIED = "PERMISSION"
    const val INTERNAL = "INTERNAL"

    /** summary 會被大腦直接唸出來：寫成一句台灣口語 */
    fun ok(summary: String, data: JSONObject? = null, undoAction: String? = null, undoParams: JSONObject? = null): JSONObject =
        JSONObject().put("ok", true).put("summary", summary).put("data", data ?: JSONObject())
            .put("undo", if (undoAction != null) JSONObject().put("action", undoAction).put("params", undoParams ?: JSONObject()) else JSONObject.NULL)

    /** message 也會被唸出來，要寫人話 */
    fun fail(code: String, message: String, data: JSONObject? = null): JSONObject =
        JSONObject().put("ok", false).put(
            "error",
            JSONObject().put("code", code).put("message", message).apply { if (data != null) put("data", data) },
        )

    fun needsConfirmation(message: String, confirmToken: String) =
        fail(NEEDS_CONFIRMATION, message, JSONObject().put("confirmToken", confirmToken))

    fun ambiguous(message: String, candidates: List<String>) =
        fail(AMBIGUOUS, message, JSONObject().put("candidates", JSONArray(candidates)))

    fun needsUi(message: String, intentUri: String) =
        fail(NEEDS_UI, message, JSONObject().put("intentUri", intentUri))

    /** 宣告一個動作（describe 用）。name 只用 [a-z0-9_]，格式 <物件>_<動詞> */
    fun action(
        name: String,
        title: String,
        description: String,
        risk: String,
        params: JSONObject,
        examples: List<String>,
        deprecated: Boolean = false,
    ): JSONObject {
        require(Regex("^[a-z0-9_]+$").matches(name)) { "動作名稱只能用 [a-z0-9_]：$name" }
        require(risk in setOf("read", "reversible", "destructive")) { "risk 不正確：$risk" }
        return JSONObject().put("name", name).put("title", title).put("description", description)
            .put("risk", risk).put("params", params).put("examples", JSONArray(examples))
            .apply { if (deprecated) put("deprecated", true) }
    }

    fun describe(app: String, packageName: String, version: String, description: String, actions: List<JSONObject>): JSONObject =
        JSONObject().put("api", API).put("app", app).put("package", packageName).put("version", version)
            .put("description", description).put("actions", JSONArray(actions))
}

/** 呼叫 execute 時收到的請求 */
data class AgentRequest(
    val requestId: String,
    val action: String,
    val params: JSONObject,
    val dryRun: Boolean,
    val confirmToken: String?,
    val utterance: String?,
)

/**
 * 繼承這個類別即可提供 Agent API。只負責解析與分派；動作請一律走 APP 原本的 Repository。
 * Manifest：exported、authorities ${applicationId}.agent、permission tw.hoonsor.permission.AGENT、
 * intent-filter action tw.hoonsor.agent.PROVIDER。
 */
abstract class HoonsorAgentProvider : ContentProvider() {
    private val worker = Executors.newCachedThreadPool()

    /** describe 的完整 JSON（用 [HoonsorAgent.describe] 組） */
    abstract fun describe(): JSONObject

    /** 執行動作；回傳 [HoonsorAgent.ok] 或 [HoonsorAgent.fail]。在背景執行緒呼叫，可以直接做 I/O */
    abstract fun execute(request: AgentRequest): JSONObject

    open fun health(): JSONObject = JSONObject().put("ok", true).put("issues", JSONArray())

    /** 個別動作需要更久時覆寫（例如要連網） */
    open fun timeoutMs(action: String): Long = HoonsorAgent.DEFAULT_TIMEOUT_MS

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val out = try {
            when (method) {
                "describe" -> describe()
                "health" -> health()
                "execute" -> runExecute(extras?.getString(HoonsorAgent.EXTRA_JSON))
                else -> HoonsorAgent.fail(HoonsorAgent.NOT_FOUND, "不支援的方法：$method")
            }
        } catch (e: Exception) {
            HoonsorAgent.fail(HoonsorAgent.INTERNAL, "${e.javaClass.simpleName}：${e.message}")
        }
        return Bundle().apply { putString(HoonsorAgent.EXTRA_JSON, out.toString()) }
    }

    private fun runExecute(raw: String?): JSONObject {
        val j = runCatching { JSONObject(raw ?: "") }.getOrNull()
            ?: return HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, "缺少請求內容")
        val req = AgentRequest(
            requestId = j.optString("requestId"),
            action = j.optString("action"),
            params = j.optJSONObject("params") ?: JSONObject(),
            dryRun = j.optBoolean("dryRun", false),
            confirmToken = j.optString("confirmToken").takeIf { it.isNotBlank() && it != "null" },
            utterance = j.optString("utterance").takeIf { it.isNotBlank() && it != "null" },
        )
        val result = try {
            worker.submit(Callable { execute(req) }).get(timeoutMs(req.action), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            HoonsorAgent.fail(HoonsorAgent.INTERNAL, "處理逾時")
        } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause ?: e
            if (c is org.json.JSONException || c is IllegalArgumentException) HoonsorAgent.fail(HoonsorAgent.INVALID_PARAMS, c.message ?: "參數錯誤")
            else HoonsorAgent.fail(HoonsorAgent.INTERNAL, "${c.javaClass.simpleName}：${c.message}")
        }
        return result.put("requestId", req.requestId)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
