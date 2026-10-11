package com.hoonsor.alarm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 設計稿色票（深色介面、琥珀色主色） */
object C {
    val bg = Color(0xFF0E1117)
    val bgDeep = Color(0xFF0B0E13)
    val surface = Color(0xFF161A23)
    val surfaceHi = Color(0xFF1B2030)
    val field = Color(0xFF10131A)
    val border = Color(0xFF232836)
    val borderHi = Color(0xFF343B4D)
    val chipBorder = Color(0xFF3A4152)
    val text = Color(0xFFECEFF4)
    val textSoft = Color(0xFFC9CFDB)
    val muted = Color(0xFF9AA3B5)
    val nav = Color(0xFF8A93A6)
    val dim = Color(0xFF6F778A)
    val faint = Color(0xFF525A6C)
    val accent = Color(0xFFF2B544)
    val accentSoft = Color(0xFFF7C96E)
    val onAccent = Color(0xFF1A1405)
    val warn = Color(0xFFFF8A5B)
    val warnText = Color(0xFFFFB48F)
    val warnBg = Color(0xFF2B1E16)
    val warnBorder = Color(0xFF5A3726)
    val danger = Color(0xFFFF9F7A)
    val ok = Color(0xFF5BD69A)
    val fast = Color(0xFF6FE0C8)
    val switchOff = Color(0xFF2E3444)
}

@Composable
fun AlarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent,
            onPrimary = C.onAccent,
            secondary = C.accentSoft,
            background = C.bg,
            onBackground = C.text,
            surface = C.surfaceHi,
            onSurface = C.text,
            surfaceVariant = C.surface,
            onSurfaceVariant = C.muted,
            surfaceContainerHigh = C.surfaceHi,
            surfaceContainerHighest = C.surfaceHi,
            outline = C.borderHi,
            error = C.danger,
        ),
        content = content,
    )
}
