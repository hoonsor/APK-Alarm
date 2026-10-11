package com.hoonsor.alarm.ui.timer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hoonsor.alarm.ui.common.Glyph
import com.hoonsor.alarm.ui.common.GlyphIcon
import com.hoonsor.alarm.ui.theme.C

/**
 * 數字鍵盤輸入時長，操作方式與 Google 時鐘相同：
 * 依序按 2、5、0、0 → 00:25:00；數字從右邊推入，最多 6 位（時:分:秒）。
 */
@Composable
fun DurationKeypadDialog(initialMs: Long, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    var digits by remember { mutableStateOf(initialDigits(initialMs)) }
    val padded = digits.padStart(6, '0')
    val h = padded.substring(0, 2).toInt()
    val m = padded.substring(2, 4).toInt()
    val s = padded.substring(4, 6).toInt()
    val totalMs = (h * 3600L + m * 60L + s) * 1000L

    fun press(d: String) {
        val next = (digits + d).trimStart('0')
        if (next.length <= 6) digits = next
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(C.surfaceHi).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("自訂時長", color = C.muted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Segment(padded.substring(0, 2), "時", digits.length > 4)
                Segment(padded.substring(2, 4), "分", digits.length > 2)
                Segment(padded.substring(4, 6), "秒", digits.isNotEmpty())
            }
            val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("00", "0", "⌫"))
            keys.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(16.dp)).background(C.field)
                                .clickable(onClickLabel = if (k == "⌫") "刪除一位" else k) {
                                    when (k) {
                                        "⌫" -> digits = digits.dropLast(1)
                                        else -> press(k)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (k == "⌫") GlyphIcon(Glyph.Backspace, "刪除一位", tint = C.textSoft)
                            else Text(k, color = C.text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp)) {
                    Text("取消", color = C.textSoft)
                }
                Button(
                    onClick = { onConfirm(totalMs) },
                    enabled = totalMs > 0,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent),
                ) { Text("確定", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun Segment(value: String, unit: String, active: Boolean) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(horizontal = 6.dp)) {
        Text(value, color = if (active) C.text else C.dim, fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
        Text(unit, color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
    }
}

/** 既有時長轉成鍵盤字串，例如 25 分 → "2500" */
private fun initialDigits(ms: Long): String {
    val total = ms / 1000
    val h = (total / 3600).coerceAtMost(99)
    val m = (total % 3600) / 60
    val s = total % 60
    return "%02d%02d%02d".format(h, m, s).trimStart('0')
}
