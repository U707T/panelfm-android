package com.u707t.panelfm.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.geometry.Rect
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
 * MT 风格图标（对照 MT 2.14.5 实测）：
 *  - **文件夹** = 实心圆角方块（深灰近黑 #3C3C3C）+ 白色文件夹剪影（MT 的观感）
 *  - **文件** = 实心圆角方块（按类型着色）+ 白色类型缩写（MT 的彩色小图标）
 *  - 尺寸：行内 32dp（MT `0x7f0c00e4` 的 0901B6 = 32dp）
 */
@Composable
fun FileIcon(
    name: String,
    isDirectory: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = MtSpec.RowIcon,
    alpha: Float = 1f,
    /** 文件夹底色（默认跟随明暗主题：浅色 #3C3C3C / 深色 #E0E0E0 反相） */
    folderColor: Color? = null,
) {
    val dark = isSystemInDarkTheme()
    val bg = when {
        folderColor != null -> folderColor
        isDirectory -> if (dark) Color(0xFF3A3A3A) else Color(0xFF3C3C3C)
        else -> colorOf(name)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
            .background(bg.copy(alpha = bg.alpha * alpha)),
        contentAlignment = Alignment.Center,
    ) {
        if (isDirectory) {
            // 白色文件夹剪影（MT 的文件夹图标就是方块 + 白色文件夹轮廓）
            FolderGlyph(
                modifier = Modifier.size(size * 0.62f),
                color = Color.White.copy(alpha = 0.94f * alpha),
            )
        } else {
            Text(
                text = labelOf(name),
                color = Color.White.copy(alpha = alpha),
                fontSize = (size.value * 0.30f).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** 白色文件夹剪影（实心方块内的轮廓） */
@Composable
fun FolderGlyph(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    Canvas(modifier = modifier) {
        val w = this.size.width
        val h = this.size.height
        val radius = w * 0.16f
        // 页签
        drawPath(
            Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(offset = Offset(w * 0.02f, h * 0.16f), size = Size(w * 0.42f, h * 0.22f)),
                        cornerRadius = CornerRadius(radius * 0.7f, radius * 0.7f),
                    )
                )
            },
            color = color,
        )
        // 主体
        drawPath(
            Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(offset = Offset(w * 0.02f, h * 0.30f), size = Size(w * 0.96f, h * 0.58f)),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                )
            },
            color = color,
        )
    }
}

/** MT 式实心文件夹（保留旧签名，供侧栏/对话框复用） */
@Composable
fun MtFolderGlyph(
    modifier: Modifier = Modifier,
    color: Color = MtSpec.FolderGlyphLight,
    size: Dp = MtSpec.RowIcon,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        FolderGlyph(
            modifier = Modifier.size(size * 0.62f),
            color = Color.White.copy(alpha = 0.94f),
        )
    }
}

/** 文件类型 → 图标底色（MT 的彩色小图标观感） */
private fun colorOf(name: String): Color {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (MimeTypes.kindOf(ext)) {
        MimeTypes.Kind.IMAGE -> MtSpec.IconImage
        MimeTypes.Kind.VIDEO -> MtSpec.IconVideo
        MimeTypes.Kind.AUDIO -> MtSpec.IconAudio
        MimeTypes.Kind.ARCHIVE -> MtSpec.IconArchive
        MimeTypes.Kind.APK -> MtSpec.IconApk
        MimeTypes.Kind.FONT -> MtSpec.IconFont
        MimeTypes.Kind.PDF -> MtSpec.IconPdf
        MimeTypes.Kind.CODE -> MtSpec.IconCode
        MimeTypes.Kind.TEXT -> MtSpec.IconText
        MimeTypes.Kind.OTHER -> MtSpec.IconOther
    }
}

/** 文件类型缩写（MT 用后缀缩写，最多 4 字符） */
private fun labelOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return "FILE"
    return ext.uppercase().take(4)
}
