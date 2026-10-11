package com.hoonsor.alarm.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 設計稿使用的線條圖示（24×24、2px 線寬）。
 * 自己用 path 建立，不依賴 material-icons 套件。
 */
enum class Glyph(private val d: String, private val filled: Boolean = false) {
    Plus("M12 5v14M5 12h14"),
    Close("M18 6 6 18M6 6l12 12"),
    Check("M20 6 9 17l-5-5"),
    Mic("M12 2a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V5a3 3 0 0 1 3-3zM19 10v1a7 7 0 0 1-14 0v-1M12 18v4"),
    Settings("M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"),
    Image("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM9 7a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM21 15l-5-5L5 21"),
    Copy("M11 9h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2zM5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"),
    Power("M7 7h10a5 5 0 0 1 0 10H7a5 5 0 0 1 0-10zM15 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z"),
    Trash("M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"),
    Alarm("M12 5a8 8 0 1 0 0 16a8 8 0 1 0 0-16zM12 9v4l2 2M5 3 2 6M22 6l-3-3"),
    Hourglass("M5 22h14M5 2h14M17 22v-4.17a2 2 0 0 0-.59-1.42L12 12l-4.41 4.41A2 2 0 0 0 7 17.83V22M7 2v4.17a2 2 0 0 0 .59 1.42L12 12l4.41-4.41A2 2 0 0 0 17 6.17V2"),
    Stopwatch("M12 6a8 8 0 1 0 0 16a8 8 0 1 0 0-16zM12 14v-4M10 2h4M19 7l-1.5 1.5"),
    Play("M7 4l13 8-13 8z", filled = true),
    Pause("M6 4h4v16H6zM14 4h4v16h-4z", filled = true),
    Reset("M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5"),
    Flag("M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1zM4 22v-7"),
    Back("M15 18l-6-6 6-6"),
    Warn("M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0zM12 9v4M12 17h.01"),
    CheckCircle("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20zM9 12l2 2 4-4"),
    Info("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20zM12 16v-4M12 8h.01"),
    Bell("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.94 1.94 0 0 0 3.4 0"),
    ChevronDown("M6 9l6 6 6-6"),
    ChevronRight("M9 6l6 6-6 6"),
    More("M12 3.25a1.75 1.75 0 1 0 0 3.5a1.75 1.75 0 1 0 0-3.5zM12 10.25a1.75 1.75 0 1 0 0 3.5a1.75 1.75 0 1 0 0-3.5zM12 17.25a1.75 1.75 0 1 0 0 3.5a1.75 1.75 0 1 0 0-3.5z", filled = true),
    Folder("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"),
    Music("M9 18V5l12-2v13M6 15a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM18 13a3 3 0 1 0 0 6a3 3 0 1 0 0-6z"),
    Backspace("M21 4H8l-7 8 7 8h13a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zM18 9l-6 6M12 9l6 6"),
    Stop("M6 6h12v12H6z", filled = true),
    Sparkle("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"),
    Speaker("M11 5 6 9H2v6h4l5 4zM15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14");

    val vector: ImageVector by lazy {
        val nodes = addPathNodes(d)
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            if (filled) {
                addPath(pathData = nodes, fill = SolidColor(Color.Black))
            } else {
                addPath(
                    pathData = nodes,
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }
}

@Composable
fun GlyphIcon(glyph: Glyph, contentDescription: String?, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Icon(glyph.vector, contentDescription, modifier.size(size), tint = tint)
}
