package com.hoonsor.alarm.agent

import androidx.core.app.NotificationManagerCompat
import com.hoonsor.alarm.BuildConfig
import com.hoonsor.alarm.Graph
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import tw.hoonsor.agent.AgentRequest
import tw.hoonsor.agent.HoonsorAgentProvider

/**
 * 倬的鬧鐘的 Hoonsor Agent API（協定：hoonsor/APK-VoiceHub docs/AGENT_PROTOCOL.md）。
 * 語音總控用 ContentProvider.call 呼叫 describe／execute／health；只有同一把簽章的 APP 拿得到權限。
 */
class AgentProvider : HoonsorAgentProvider() {

    override fun describe(): JSONObject =
        AgentCatalog.describe(context?.packageName ?: BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME)

    /** SDK 已經在背景執行緒呼叫，這裡直接等 Repository 的 suspend 函式做完 */
    override fun execute(request: AgentRequest): JSONObject = runBlocking { AlarmAgent.execute(request) }

    override fun health(): JSONObject {
        val ctx = Graph.appContext
        val issues = JSONArray()
        if (!Graph.scheduler.canScheduleExact()) issues.put("沒有允許精準鬧鐘排程，鬧鐘可能延遲")
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) issues.put("通知被關閉，響鈴畫面可能出不來")
        return JSONObject().put("ok", issues.length() == 0).put("issues", issues)
    }
}
