package com.hoonsor.alarm.ui.timer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hoonsor.alarm.ui.common.Glyph
import com.hoonsor.alarm.ui.common.GlyphIcon
import com.hoonsor.alarm.ui.theme.C

/**
 * 數字鍵盤輸入時長。時、分、秒三個欄位都可以點選，數字輸入到選取的欄位：
 * 例如點「時」按 1，自動跳到「分」再按 3、0 → 1:30:00。一個欄位輸入滿兩位會自動跳到下一個欄位。
 * 預設選取「分」，最常用的「25 分鐘」只要按 2、5。
 */
@Composable
fun DurationKeypadDialog(initialMs: Long, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    val init = (initialMs / 1000).coerceAtLeast(0)
    var fields by remember { mutableStateOf(listOf((init / 3600).coerceAtMost(99).toInt(), ((init % 3600) / 60).toInt(), (init % 60).toInt())) }
    var sel by remember { mutableIntStateOf(1) }
    var typed by remember { mutableIntStateOf(0) }
    val totalMs = (fields[0] * 3600L + fields[1] * 60L + fields[2]) * 1000L

    fun max(i: Int) = if (i == 0) 99 else 59
    fun set(i: Int, v: Int) { fields = fields.toMutableList().also { it[i] = v.coerceIn(0, max(i)) } }
    fun select(i: Int) { sel = i; typed = 0 }

    fun digit(d: Int) {
        if (typed == 0) {
            set(sel, d); typed = 1
        } else {
            set(sel, fields[sel] * 10 + d)
            if (sel < 2) select(sel + 1) else typed = 2
        }
        if (typed >= 2) typed = 0
    }

    fun backspace() {
        if (typed > 0) { set(sel, fields[sel] / 10); typed-- } else set(sel, 0)
    }

    fun add(sec: Int) {
        val t = (fields[0] * 3600 + fields[1] * 60 + fields[2] + sec).coerceIn(0, 99 * 3600 + 59 * 60 + 59)
        fields = listOf(t / 3600, (t % 3600) / 60, t % 60)
        typed = 0
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(C.surfaceHi).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("自訂時長", color = C.muted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("時", "分", "秒").forEachIndexed { i, unit ->
                    Segment(
                        value = "%02d".format(fields[i]), unit = unit, selected = sel == i,
                        modifier = Modifier.weight(1f), onClick = { select(i) },
                    )
                }
            }
            Text("點「時」「分」「秒」選擇要輸入的欄位，滿兩位自動跳下一格", color = C.dim, fontSize = 11.sp, lineHeight = 16.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("清除" to 0, "+1 時" to 3600, "+10 分" to 600, "+1 分" to 60).forEach { (label, sec) ->
                    Box(
                        Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp))
                            .border(1.dp, C.chipBorder, RoundedCornerShape(10.dp))
                            .clickable {
                                if (sec == 0) { fields = listOf(0, 0, 0); select(1) } else add(sec)
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = C.textSoft, fontSize = 12.sp, maxLines = 1) }
                }
            }
            val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("00", "0", "⌫"))
            keys.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(C.field)
                                .clickable(onClickLabel = if (k == "⌫") "刪除一位" else k) {
                                    when (k) {
                                        "⌫" -> backspace()
                                        "00" -> if (typed == 0) { digit(0); digit(0) } else digit(0)
                                        else -> digit(k.toInt())
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
private fun Segment(value: String, unit: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(if (selected) C.accent.copy(alpha = 0.14f) else C.field)
            .border(1.5.dp, if (selected) C.accent else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = "輸入$unit", onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = if (selected) C.text else C.textSoft, fontSize = 38.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(unit, color = if (selected) C.accentSoft else C.muted, fontSize = 13.sp)
    }
}
