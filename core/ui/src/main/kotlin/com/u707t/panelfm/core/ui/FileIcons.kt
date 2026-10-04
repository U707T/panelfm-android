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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u707t.panelfm.core.common.MimeTypes

/**
 * 列表行图标（复刻 MT 2.14.5 的实测观感）。
 *
 * ## MT 的真实形态（对照用户截图）
 *
 * MT 的行内图标是 **深/彩色圆角方块 + 白色剪影**：
 *  - **文件夹** = 近黑圆角方块（`#FF3C3C3C`）+ **白色**文件夹剪影；
 *  - **文件** = **按类型着色**的方块（图片绿 / 视频红 / 音频紫 / 压缩包橙 / APK 绿 /
 *    字体青 / PDF 红 / 代码蓝 / 文本灰）+ **白色**「折角文件」剪影。
 *
 * （中途有一版误判成「浅底 + 深剪影」，与 MT 截图明显不符，已按截图改回。）
 *
 * ## 尺寸（`0x7f0c00e4` / `0x7f0c00e2`）
 *
 *  - 图标 **32dp**，左 margin **8dp**（`0x7f0c00e4`）/ 容器 padding 8dp（`0x7f0c00e2`）
 *  - 名称 15sp（最多 2 行）、副标题 10sp（单行，色 `0x7f060047` = `#99000000`）
 *  - 名称与图标间距 8dp（`0x7f0c00e4`）/ 10dp（`0x7f0c00e2`）
 */
@Composable
fun FileIcon(
    name: String,
    isDirectory: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = MtSpec.RowIcon,
    alpha: Float = 1f,
    /** 文件夹底色（默认跟随明暗主题） */
    folderColor: Color? = null,
) {
    // MT 实测（截图）：**深底 + 白剪影**
    //  · 文件夹 = 近黑圆角方块 + 白色文件夹剪影
    //  · 文件   = 按类型着色的方块 + 白色「折角文件」剪影
    val bg = when {
        folderColor != null -> folderColor
        isDirectory -> MtSpec.FolderTileLight
        else -> colorOf(name)
    }
    // 剪影统一白色（MT 的观感：深/彩色底 + 白图形）
    val glyph = Color.White

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f))
            .background(bg.copy(alpha = bg.alpha * alpha)),
        contentAlignment = Alignment.Center,
    ) {
        if (isDirectory) {
            FolderGlyph(
                modifier = Modifier.size(size * 0.58f),
                color = glyph.copy(alpha = glyph.alpha * alpha),
            )
        } else {
            // 文件：深/彩色底 + 白色「折角文件」剪影
            FileGlyph(
                modifier = Modifier.size(size * 0.56f),
                color = glyph.copy(alpha = glyph.alpha * alpha),
            )
        }
    }
}

/** 白色/浅色文件夹剪影（MT 的实心文件夹轮廓：页签 + 主体） */
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
                    androidx.compose.ui.geometry.RoundRect(
                        rect = androidx.compose.ui.geometry.Rect(
                            offset = Offset(w * 0.02f, h * 0.16f),
                            size = Size(w * 0.42f, h * 0.22f),
                        ),
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
                    androidx.compose.ui.geometry.RoundRect(
                        rect = androidx.compose.ui.geometry.Rect(
                            offset = Offset(w * 0.02f, h * 0.30f),
                            size = Size(w * 0.96f, h * 0.58f),
                        ),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                )
            },
            color = color,
        )
    }
}

/**
 * 折角文件剪影（MT 的文件图标形状）：
 * 一个圆角矩形，右上角切掉一个三角（折角）。
 */
@Composable
fun FileGlyph(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    Canvas(modifier = modifier) {
        val w = this.size.width
        val h = this.size.height
        val fold = w * 0.34f
        val radius = w * 0.14f
        val path = Path().apply {
            // 从左上开始，顺时针；右上角走「折角」
            moveTo(radius, 0f)
            lineTo(w - fold, 0f)
            lineTo(w, fold)
            lineTo(w, h - radius)
            quadraticTo(w, h, w - radius, h)
            lineTo(radius, h)
            quadraticTo(0f, h, 0f, h - radius)
            lineTo(0f, radius)
            quadraticTo(0f, 0f, radius, 0f)
            close()
        }
        drawPath(path, color = color)
        // 折角的内凹（用背景色画一个小三角，形成「折页」观感）
        val inner = Path().apply {
            moveTo(w - fold, 0f)
            lineTo(w - fold, fold - w * 0.06f)
            lineTo(w - w * 0.06f, fold)
            close()
        }
        drawPath(inner, color = Color.Transparent)
    }
}

/**
 * MT 式实心文件夹（保留旧签名，供侧栏 / 对话框复用）：
 * 这里仍用**深色方块 + 白剪影**（侧栏/工具行的圆形徽标底色本身就是深色，需要反相）。
 */
@Composable
fun MtFolderGlyph(
    modifier: Modifier = Modifier,
    color: Color = MtSpec.FolderGlyphLight,
    size: Dp = MtSpec.RowIcon,
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        FolderGlyph(modifier = Modifier.size(size * 0.72f), color = color)
    }
}

/** 按扩展名取类型色（给「打开方式」网格、文件类型徽标复用） */
fun colorOf(name: String): Color {
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
        else -> MtSpec.IconOther
    }
}

/** 扩展名缩写（文件类型徽标用，最多 4 字符） */
fun labelOf(name: String): String {
    val ext = name.substringAfterLast('.', "").uppercase()
    return when {
        ext.isEmpty() -> "?"
        ext.length <= 4 -> ext
        else -> ext.take(4)
    }
}
