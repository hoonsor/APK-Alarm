package com.hoonsor.alarm.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoonsor.alarm.image.ImageStore
import com.hoonsor.alarm.ui.theme.C

@Composable
fun rememberBitmap(path: String?, maxEdge: Int = 1440): Bitmap? {
    val bmp by produceState<Bitmap?>(initialValue = null, path, maxEdge) {
        value = ImageStore.load(path, maxEdge)
    }
    return bmp
}

/** 設計稿裡的小開關（比 Material Switch 更精簡） */
@Composable
fun MiniSwitch(checked: Boolean, onToggle: () -> Unit, large: Boolean = false, contentDescription: String) {
    val w: Dp = if (large) 52.dp else 38.dp
    val h: Dp = if (large) 30.dp else 22.dp
    val knob: Dp = if (large) 24.dp else 16.dp
    Box(
        Modifier
            .size(if (large) 64.dp else 52.dp, if (large) 48.dp else 44.dp)
            .clickable(role = Role.Switch, onClickLabel = contentDescription, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(w, h).background(if (checked) C.accent else C.switchOff, RoundedCornerShape(h / 2)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .offset(x = if (checked) w - knob - 3.dp else 3.dp)
                    .size(knob)
                    .background(if (checked) C.onAccent else C.nav, CircleShape),
            )
        }
    }
}

/** 圓形星期按鈕 */
@Composable
fun DayChip(label: String, selected: Boolean, size: Dp = 44.dp, onClick: (() -> Unit)?) {
    val mod = Modifier
        .size(size)
        .background(if (selected) C.accent else C.bg.copy(alpha = 0f), CircleShape)
        .border(1.dp, if (selected) C.accent else C.chipBorder, CircleShape)
    Box(
        if (onClick != null) mod.clickable(role = Role.Checkbox, onClick = onClick) else mod,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) C.onAccent else C.textSoft,
            fontSize = 15.sp,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null,
        )
    }
}
