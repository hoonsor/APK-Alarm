package com.hoonsor.alarm.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoonsor.alarm.agent.AgentCatalog
import com.hoonsor.alarm.agent.AgentStatus
import com.hoonsor.alarm.ui.theme.C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * 設定頁：Agent API 測試台。透過真正的 ContentProvider 呼叫自己（和語音總控走同一條路），
 * 不經過 AI，用來確認每個動作的參數與回傳。預設試跑（dryRun），不會改到資料。
 */
@Composable
fun AgentBenchCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = remember { AgentCatalog.actions() }
    var action by remember { mutableStateOf(actions.first().getString("name")) }
    val spec = actions.first { it.getString("name") == action }
    var params by remember(action) { mutableStateOf(skeleton(spec)) }
    var dryRun by remember { mutableStateOf(true) }
    var confirmToken by remember(action) { mutableStateOf("") }
    var output by remember(action) { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    fun run(method: String, request: JSONObject? = null) {
        running = true
        scope.launch {
            output = withContext(Dispatchers.IO) {
                runCatching { AgentStatus.call(context, method, request) }
                    .map { r ->
                        r.optJSONObject("error")?.optJSONObject("data")?.optString("confirmToken")?.takeIf { it.isNotEmpty() }?.let { confirmToken = it }
                        r.toString(2)
                    }
                    .getOrElse { "${it.javaClass.simpleName}：${it.message}" }
            }
            running = false
        }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface)
            .border(1.dp, C.border, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("API 測試台（語音總控）", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "直接呼叫本 APP 開放給語音總控的動作，不經過 AI。預設只試跑、不改資料；要刪除時會先回傳確認碼，再按一次「執行」才會真的刪。",
            color = C.muted, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { a ->
                val name = a.getString("name")
                val on = name == action
                Text(
                    a.getString("title"),
                    color = if (on) C.onAccent else C.textSoft, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (on) C.accent else C.field)
                        .border(1.dp, if (on) C.accent else C.chipBorder, RoundedCornerShape(16.dp))
                        .clickable { action = name }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
        Text("$action（${spec.getString("risk")}）：${spec.getString("description")}", color = C.dim, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = params, onValueChange = { params = it },
            label = { Text("params（JSON）") }, minLines = 3,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = C.text),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().clickable { dryRun = !dryRun }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = dryRun, onCheckedChange = { dryRun = it },
                colors = CheckboxDefaults.colors(checkedColor = C.accent, checkmarkColor = C.onAccent, uncheckedColor = C.chipBorder),
            )
            Text("試跑（dryRun）：只檢查參數，不真的執行", color = C.text, fontSize = 14.sp)
        }
        if (confirmToken.isNotEmpty()) Text("已取得確認碼，下次執行會帶上：$confirmToken", color = C.warnText, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !running, onClick = {
                val p = runCatching { JSONObject(params.ifBlank { "{}" }) }.getOrElse {
                    output = "params 不是合法 JSON：${it.message}"
                    return@OutlinedButton
                }
                val req = JSONObject().put("api", 1).put("requestId", UUID.randomUUID().toString())
                    .put("action", action).put("params", p).put("dryRun", dryRun)
                    .put("confirmToken", confirmToken.ifEmpty { null } ?: JSONObject.NULL)
                    .put("utterance", "（測試台）")
                confirmToken = ""
                run("execute", req)
            }) { Text("執行", color = C.accentSoft) }
            OutlinedButton(enabled = !running, onClick = { run("describe") }) { Text("describe", color = C.text) }
            OutlinedButton(enabled = !running, onClick = { run("health") }) { Text("health", color = C.text) }
        }
        if (output.isNotBlank()) {
            SelectionContainer {
                Text(
                    output, color = C.textSoft, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.field).padding(12.dp),
                )
            }
        }
    }
}

/** 依參數規格產生必填欄位的範例 */
private fun skeleton(spec: JSONObject): String {
    val schema = spec.getJSONObject("params")
    val props = schema.optJSONObject("properties") ?: return "{}"
    val req = schema.optJSONArray("required")
    val out = JSONObject()
    for (i in 0 until (req?.length() ?: 0)) {
        val k = req!!.optString(i)
        val p = props.optJSONObject(k) ?: continue
        out.put(
            k,
            when (p.optString("type")) {
                "integer", "number" -> p.optInt("minimum", 0)
                "boolean" -> true
                "array" -> org.json.JSONArray()
                else -> p.optJSONArray("enum")?.optString(0) ?: ""
            },
        )
    }
    return out.toString(2)
}
