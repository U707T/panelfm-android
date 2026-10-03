package com.u707t.panelfm.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.core.common.MimeTypes

/**
 * MT 风格图标：
 *  - 文件夹 = 实心文件夹轮廓（跟随主题前景色，浅色近黑 / 深色近白）
 *  - 文件 = 圆角色块 + 类型缩写（按类型着色），与 MT 的彩色小图标观感一致
 */
@Composable
fun FileIcon(
    name: String,
    isDirectory: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    alpha: Float = 1f,
    /** 文件夹颜色：MT 里未聚焦窗格是浅灰、聚焦窗格近黑 */
    folderColor: Color? = null,
) {
    if (isDirectory) {
        MtFolderGlyph(
            modifier = modifier.size(size),
            color = (folderColor ?: MaterialTheme.colorScheme.onSurface).copy(alpha = alpha),
            size = size,
        )
        return
    }
    val (bg, label) = colorOf(name)
    Box(
        modifier = modifier
            .size(size * 0.86f)
            .clip(RoundedCornerShape(size * 0.16f))
            .background(bg.copy(alpha = bg.alpha * alpha)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = alpha),
            fontSize = (size.value * 0.26f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** MT 式实心文件夹（带顶部小页签） */
@Composable
fun MtFolderGlyph(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = 40.dp,
) {
    Canvas(modifier = modifier) {
        val w = this.size.width
        val h = this.size.height
        val tabH = h * 0.18f
        val radius = w * 0.12f

        val body = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = androidx.compose.ui.geometry.Rect(
                        offset = Offset(w * 0.06f, h * 0.30f),
                        size = Size(w * 0.88f, h * 0.60f),
                    ),
                    cornerRadius = CornerRadius(radius, radius),
                )
            )
        }
        drawPath(body, color = color)

        val tab = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = androidx.compose.ui.geometry.Rect(
                        offset = Offset(w * 0.06f, h * 0.12f + tabH * 0.4f),
                        size = Size(w * 0.46f, h * 0.24f),
                    ),
                    cornerRadius = CornerRadius(radius * 0.8f, radius * 0.8f),
                )
            )
        }
        drawPath(tab, color = color)
    }
}

private fun colorOf(name: String): Pair<Color, String> {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (MimeTypes.kindOf(ext)) {
        MimeTypes.Kind.IMAGE -> Color(0xFF4CAF50) to "IMG"
        MimeTypes.Kind.VIDEO -> Color(0xFFE91E63) to "VID"
        MimeTypes.Kind.AUDIO -> Color(0xFF9C27B0) to "AUD"
        MimeTypes.Kind.ARCHIVE -> Color(0xFFFF9800) to "ZIP"
        MimeTypes.Kind.APK -> Color(0xFF3DDC84) to "APK"
        MimeTypes.Kind.FONT -> Color(0xFF00BCD4) to "FNT"
        MimeTypes.Kind.PDF -> Color(0xFFF44336) to "PDF"
        MimeTypes.Kind.CODE -> Color(0xFF2196F3) to ext.uppercase().take(4).ifEmpty { "TXT" }
        MimeTypes.Kind.TEXT -> Color(0xFF78909C) to ext.uppercase().take(4).ifEmpty { "TXT" }
        MimeTypes.Kind.OTHER -> Color(0xFF607D8B) to ext.uppercase().take(4).ifEmpty { "FILE" }
    }
}
