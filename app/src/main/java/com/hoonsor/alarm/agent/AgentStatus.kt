package com.hoonsor.alarm.agent

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import tw.hoonsor.agent.HoonsorAgent

/** 健康檢查頁與 API 測試台共用：透過真正的 ContentProvider 呼叫自己，確認語音總控看到的結果 */
object AgentStatus {
    private val VOICEHUB = listOf("tw.hoonsor.voicehub", "tw.hoonsor.voicehub.lab")

    data class Report(
        val providerOk: Boolean,
        val actionCount: Int,
        val healthIssues: List<String>,
        /** 已安裝的語音總控套件名；null＝沒裝 */
        val voiceHub: String?,
        /** 語音總控與本 APP 是否同一把簽章（不同就拿不到 signature 權限） */
        val sameSignature: Boolean,
        val error: String?,
    )

    fun uri(context: Context): Uri = Uri.parse("content://${context.packageName}.agent")

    /** 呼叫 describe／health／execute，回傳 JSON；在背景執行緒呼叫 */
    fun call(context: Context, method: String, request: JSONObject? = null): JSONObject {
        val extras = request?.let { Bundle().apply { putString(HoonsorAgent.EXTRA_JSON, it.toString()) } }
        val out = context.contentResolver.call(uri(context), method, null, extras)
            ?: return HoonsorAgent.fail(HoonsorAgent.INTERNAL, "provider 沒有回應")
        return JSONObject(out.getString(HoonsorAgent.EXTRA_JSON) ?: "{}")
    }

    fun check(context: Context): Report {
        val pm = context.packageManager
        val hub = VOICEHUB.firstOrNull { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }
        val same = hub != null && pm.checkSignatures(context.packageName, hub) == PackageManager.SIGNATURE_MATCH
        return try {
            val describe = call(context, "describe")
            val health = call(context, "health")
            val issues = health.optJSONArray("issues")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            Report(true, describe.optJSONArray("actions")?.length() ?: 0, issues, hub, same, null)
        } catch (e: Exception) {
            Report(false, 0, emptyList(), hub, same, "${e.javaClass.simpleName}：${e.message}")
        }
    }
}
