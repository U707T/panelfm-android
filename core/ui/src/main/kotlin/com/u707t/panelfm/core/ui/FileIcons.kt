package com.u707t.panelfm.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.core.common.MimeTypes

/** 按类型着色的方形图标（MT 风格的文件图标，无需外部资源）。 */
private fun colorOf(name: String, isDirectory: Boolean): Pair<Color, String> {
    if (isDirectory) return Color(0xFFFFB74D) to ""
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (MimeTypes.kindOf(ext)) {
        MimeTypes.Kind.IMAGE -> Color(0xFF4CAF50) to "IMG"
        MimeTypes.Kind.VIDEO -> Color(0xFFE91E63) to "VID"
        MimeTypes.Kind.AUDIO -> Color(0xFF9C27B0) to "AUD"
        MimeTypes.Kind.ARCHIVE -> Color(0xFFFF9800) to "ZIP"
        MimeTypes.Kind.APK -> Color(0xFF3DDC84) to "APK"
        MimeTypes.Kind.FONT -> Color(0xFF00BCD4) to "FNT"
        MimeTypes.Kind.PDF -> Color(0xFFF44336) to "PDF"
        MimeTypes.Kind.CODE -> Color(0xFF2196F3) to (ext.uppercase().take(4).ifEmpty { "TXT" })
        MimeTypes.Kind.TEXT -> Color(0xFF90A4AE) to (ext.uppercase().take(4).ifEmpty { "TXT" })
        MimeTypes.Kind.OTHER -> Color(0xFF607D8B) to (ext.uppercase().take(4).ifEmpty { "DAT" })
    }
}

@Composable
fun FileIcon(
    name: String,
    isDirectory: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    if (isDirectory) {
        FolderGlyph(modifier = modifier.size(size), size = size)
        return
    }
    val (bg, label) = colorOf(name, false)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.18f))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = (size.value * 0.26f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun FolderGlyph(modifier: Modifier = Modifier, size: Dp = 36.dp, color: Color = Color(0xFFFFB74D)) {
    Canvas(modifier = modifier) {
        val w = this.size.width
        val h = this.size.height
        val tabW = w * 0.42f
        val tabH = h * 0.16f
        val body = Path().apply {
            moveTo(w * 0.06f, h * 0.24f)
            lineTo(tabW, h * 0.24f)
            lineTo(tabW + w * 0.08f, h * 0.34f)
            lineTo(w * 0.94f, h * 0.34f)
            lineTo(w * 0.94f, h * 0.82f)
            lineTo(w * 0.06f, h * 0.82f)
            close()
        }
        drawPath(body, color = color)
        drawRect(
            color = color.copy(alpha = 0.75f),
            topLeft = Offset(w * 0.06f, h * 0.30f),
            size = Size(w * 0.88f, h * 0.10f),
        )
        drawPath(
            path = body,
            color = Color.Black.copy(alpha = 0.18f),
            style = Stroke(width = 1f),
        )
        // 双列暗示：中间竖线
        drawLine(
            color = Color(0xFF232A31),
            start = Offset(w * 0.5f, h * 0.34f),
            end = Offset(w * 0.5f, h * 0.82f),
            strokeWidth = w * 0.03f,
        )
    }
}
