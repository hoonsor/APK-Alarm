package com.hoonsor.alarm.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoonsor.alarm.ui.theme.C

@Composable
fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = C.field, unfocusedContainerColor = C.field,
    focusedBorderColor = C.accent, unfocusedBorderColor = C.borderHi,
    focusedTextColor = C.text, unfocusedTextColor = C.text,
    focusedLabelColor = C.accent, unfocusedLabelColor = C.muted,
    cursorColor = C.accent,
)

/** 輸入名稱（新增群組、重新命名） */
@Composable
fun NameDialog(title: String, initial: String, confirmLabel: String = "確定", onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.surfaceHi,
        title = { Text(title, color = C.text) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(30) }, singleLine = true,
                placeholder = { Text("例如：上班日、週末、吃藥", color = C.dim) },
                shape = RoundedCornerShape(12.dp), colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text(confirmLabel, color = if (text.isNotBlank()) C.accent else C.dim)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = C.muted) } },
    )
}

data class ChoiceItem(val label: String, val detail: String? = null, val selected: Boolean = false, val onClick: () -> Unit)

/** 簡單的選項清單對話框 */
@Composable
fun ChoiceDialog(title: String, items: List<ChoiceItem>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.surfaceHi,
        title = { Text(title, color = C.text) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (item.selected) C.accent.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable(onClick = item.onClick)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label, color = if (item.selected) C.accentSoft else C.text, fontSize = 15.sp)
                            item.detail?.let { Text(it, color = C.muted, fontSize = 12.sp) }
                        }
                        if (item.selected) GlyphIcon(Glyph.Check, "目前選擇", tint = C.accent, size = 18.dp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉", color = C.muted) } },
    )
}
