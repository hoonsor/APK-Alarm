package com.hoonsor.alarm.ringing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoonsor.alarm.data.VignetteShape
import com.hoonsor.alarm.ui.common.rememberBitmap
import com.hoonsor.alarm.ui.theme.C
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

@Composable
fun RingingScreen(
    info: RingInfo,
    vignetteShape: VignetteShape,
    vignetteStrength: Int,
    onSnooze: (Int) -> Unit,
    onStop: () -> Unit,
) {
    val bitmap = rememberBitmap(info.imagePath)
    val hasImage = bitmap != null
    val today = LocalDate.now()
    val dateText = "${today.monthValue} 月 ${today.dayOfMonth} 日 " +
        today.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.TRADITIONAL_CHINESE)
    val shadow = if (hasImage) Shadow(Color(0x73000000), Offset(0f, 2f), 12f) else null

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0D12))) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 上下漸層：時間與按鈕區塊加深
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color(0x73080A10),
                        0.34f to Color(0x14080A10),
                        0.6f to Color(0x38080A10),
                        1f to Color(0xC7080A10),
                    ),
                ),
            )
            Vignette(vignetteShape, vignetteStrength)
        } else {
            PulseRings()
        }

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    info.subtitle,
                    color = if (hasImage) Color(0xFFFFE2A3) else C.accent,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    style = TextStyle(shadow = shadow),
                )
                Text(
                    info.title,
                    color = Color.White, fontSize = if (info.title.length > 5) 64.sp else 92.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(shadow = shadow),
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Text(dateText, color = Color(0xFFDDE2EB), fontSize = 16.sp, style = TextStyle(shadow = shadow))
            }

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (info.memo.isNotBlank()) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (hasImage) Color(0x8C0E1117) else C.surface, RoundedCornerShape(24.dp))
                            .border(1.dp, if (hasImage) Color(0x24FFFFFF) else C.border, RoundedCornerShape(24.dp))
                            .padding(horizontal = 22.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("提醒", color = Color(0xFFD3D7E0), fontSize = 12.sp, letterSpacing = 2.sp)
                        Text(info.memo, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 35.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(5, 10, 15).forEach { m ->
                        OutlinedButton(
                            onClick = { onSnooze(m) },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (hasImage) Color(0x8C0E1117) else C.surface,
                                contentColor = Color.White,
                            ),
                        ) { Text(if (m == 5) "貪睡 5 分" else "$m 分", fontSize = 15.sp) }
                    }
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(68.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent),
                ) { Text("停止", fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp) }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

/** 暗角：橢圓從中心往外變暗；方形沿四邊往內壓暗。strength 0–100。 */
@Composable
private fun Vignette(shape: VignetteShape, strength: Int) {
    val s = strength.coerceIn(0, 100) / 100f
    val dark = Color(0xFF05070C)
    val full = dark.copy(alpha = s)
    val mid = dark.copy(alpha = s * 0.35f)
    val clear = dark.copy(alpha = 0f)
    Box(
        Modifier.fillMaxSize().drawBehind {
            val w = size.width
            val h = size.height
            when (shape) {
                VignetteShape.ELLIPSE -> {
                    val rx = w * 0.72f
                    val ry = h * 0.58f
                    val fy = ry / rx
                    val brush = Brush.radialGradient(
                        0f to clear, 0.35f to clear, 0.7f to mid, 1f to full,
                        center = center, radius = rx,
                    )
                    withTransform({ scale(1f, fy, pivot = center) }) {
                        val drawH = h / fy
                        drawRect(brush, topLeft = Offset(0f, center.y - drawH / 2), size = Size(w, drawH))
                    }
                }
                VignetteShape.SQUARE -> {
                    drawRect(
                        Brush.horizontalGradient(
                            0f to full, 0.14f to mid, 0.30f to clear, 0.70f to clear, 0.86f to mid, 1f to full,
                        ),
                    )
                    drawRect(
                        Brush.verticalGradient(
                            0f to full, 0.12f to mid, 0.26f to clear, 0.74f to clear, 0.88f to mid, 1f to full,
                        ),
                    )
                }
            }
        },
    )
}

/** 沒有設定圖片時的預設背景：同心圓 */
@Composable
private fun PulseRings() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2, size.height * 0.25f)
        listOf(170f to 0.10f, 130f to 0.18f, 90f to 0.30f).forEach { (r, a) ->
            drawCircle(C.accent.copy(alpha = a), radius = r * density, center = c, style = Stroke(1.dp.toPx()))
        }
    }
}
