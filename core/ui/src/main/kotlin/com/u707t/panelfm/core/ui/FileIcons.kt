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
            // 文件：按类型给出**不同图形**（不再所有类型共用同一个「折角文件」剪影 ——
            // 只靠底色区分的话，列表里一眼看不出是图片还是视频）
            TypeGlyph(
                kind = glyphKindOf(name),
                modifier = Modifier.size(size * 0.58f),
                color = glyph.copy(alpha = glyph.alpha * alpha),
                background = bg,
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

/** 行内图标要用的「图形类型」—— 与 [MimeTypes.Kind] 一一对应，多一个 FOLDER。 */
enum class GlyphKind { FOLDER, IMAGE, VIDEO, AUDIO, ARCHIVE, APK, FONT, PDF, CODE, TEXT, OTHER }

/** 由文件名推断图形类型 */
fun glyphKindOf(name: String): GlyphKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (MimeTypes.kindOf(ext)) {
        MimeTypes.Kind.IMAGE -> GlyphKind.IMAGE
        MimeTypes.Kind.VIDEO -> GlyphKind.VIDEO
        MimeTypes.Kind.AUDIO -> GlyphKind.AUDIO
        MimeTypes.Kind.ARCHIVE -> GlyphKind.ARCHIVE
        MimeTypes.Kind.APK -> GlyphKind.APK
        MimeTypes.Kind.FONT -> GlyphKind.FONT
        MimeTypes.Kind.PDF -> GlyphKind.PDF
        MimeTypes.Kind.CODE -> GlyphKind.CODE
        MimeTypes.Kind.TEXT -> GlyphKind.TEXT
        else -> GlyphKind.OTHER
    }
}

/**
 * 按类型绘制的白色剪影（全部用 Canvas 画，不引入图标依赖）。
 *
 * @param background 底色。仅 [GlyphKind.APK] 需要它来「挖空」眼睛（负空间）。
 */
@Composable
fun TypeGlyph(
    kind: GlyphKind,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    background: Color = Color.Transparent,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)

        when (kind) {
            GlyphKind.FOLDER -> Unit // 文件夹走 FolderGlyph，不会到这里

            // 图片：外框 + 太阳 + 山
            GlyphKind.IMAGE -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.04f, h * 0.12f),
                    size = Size(w * 0.92f, h * 0.76f),
                    cornerRadius = CornerRadius(w * 0.14f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
                drawCircle(color, radius = w * 0.09f, center = Offset(w * 0.30f, h * 0.36f))
                drawPath(
                    Path().apply {
                        moveTo(w * 0.16f, h * 0.78f)
                        lineTo(w * 0.42f, h * 0.48f)
                        lineTo(w * 0.60f, h * 0.66f)
                        lineTo(w * 0.72f, h * 0.54f)
                        lineTo(w * 0.88f, h * 0.78f)
                        close()
                    },
                    color = color,
                )
            }

            // 视频：圆角播放键（三角）
            GlyphKind.VIDEO -> {
                drawPath(
                    Path().apply {
                        moveTo(w * 0.24f, h * 0.10f)
                        lineTo(w * 0.86f, h * 0.50f)
                        lineTo(w * 0.24f, h * 0.90f)
                        close()
                    },
                    color = color,
                )
            }

            // 音频：均衡器四柱（比音符更小的尺寸下也清楚）
            GlyphKind.AUDIO -> {
                val heights = listOf(0.34f, 0.62f, 0.44f, 0.72f)
                heights.forEachIndexed { i, hh ->
                    val x = w * (0.18f + i * 0.22f)
                    drawLine(
                        color,
                        Offset(x, h * (0.5f - hh / 2f)),
                        Offset(x, h * (0.5f + hh / 2f)),
                        strokeWidth = stroke * 1.15f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }

            // 压缩包：箱体 + 中缝拉链
            GlyphKind.ARCHIVE -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.12f, h * 0.14f),
                    size = Size(w * 0.76f, h * 0.72f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
                drawLine(color, Offset(w * 0.50f, h * 0.14f), Offset(w * 0.50f, h * 0.86f), strokeWidth = stroke * 0.9f)
                for (i in 0 until 3) {
                    val y = h * (0.30f + i * 0.20f)
                    drawLine(color, Offset(w * 0.42f, y), Offset(w * 0.58f, y), strokeWidth = stroke * 0.9f)
                }
            }

            // APK：安卓机器人头（天线 + 眼睛负空间）
            GlyphKind.APK -> {
                val dome = Path().apply {
                    moveTo(w * 0.16f, h * 0.74f)
                    quadraticTo(w * 0.16f, h * 0.26f, w * 0.50f, h * 0.26f)
                    quadraticTo(w * 0.84f, h * 0.26f, w * 0.84f, h * 0.74f)
                    close()
                }
                drawPath(dome, color = color)
                drawLine(color, Offset(w * 0.60f, h * 0.20f), Offset(w * 0.72f, h * 0.06f), strokeWidth = stroke * 0.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, Offset(w * 0.40f, h * 0.20f), Offset(w * 0.28f, h * 0.06f), strokeWidth = stroke * 0.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                if (background.alpha > 0.05f) {
                    drawCircle(background, radius = w * 0.06f, center = Offset(w * 0.36f, h * 0.48f))
                    drawCircle(background, radius = w * 0.06f, center = Offset(w * 0.64f, h * 0.48f))
                }
            }

            // 字体：字母 A（两腿 + 横杠）
            GlyphKind.FONT -> {
                line(w * 0.18f, h * 0.86f, w * 0.50f, h * 0.14f)
                line(w * 0.82f, h * 0.86f, w * 0.50f, h * 0.14f)
                line(w * 0.31f, h * 0.62f, w * 0.69f, h * 0.62f)
            }

            // PDF：折角页 + 三条内容线（与 TEXT 的区别是折角）
            GlyphKind.PDF -> {
                PageOutline(w, h, color, stroke, fold = true)
                line(w * 0.30f, h * 0.52f, w * 0.70f, h * 0.52f)
                line(w * 0.30f, h * 0.68f, w * 0.70f, h * 0.68f)
            }

            // 代码：< >
            GlyphKind.CODE -> {
                line(w * 0.38f, h * 0.24f, w * 0.16f, h * 0.50f)
                line(w * 0.16f, h * 0.50f, w * 0.38f, h * 0.76f)
                line(w * 0.62f, h * 0.24f, w * 0.84f, h * 0.50f)
                line(w * 0.84f, h * 0.50f, w * 0.62f, h * 0.76f)
            }

            // 文本：三条左对齐横线（无折角）
            GlyphKind.TEXT -> {
                line(w * 0.20f, h * 0.28f, w * 0.80f, h * 0.28f)
                line(w * 0.20f, h * 0.50f, w * 0.80f, h * 0.50f)
                line(w * 0.20f, h * 0.72f, w * 0.58f, h * 0.72f)
            }

            // 未知：折角页（沿用旧观感）
            GlyphKind.OTHER -> PageOutline(w, h, color, stroke, fold = true)
        }
    }
}

/** 圆角「页」轮廓；[fold] = true 时右上角留出折角缺口 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.PageOutline(
    w: Float,
    h: Float,
    color: Color,
    stroke: Float,
    fold: Boolean,
) {
    val r = w * 0.14f
    val foldSize = w * 0.26f
    val path = Path().apply {
        if (fold) {
            moveTo(r, h * 0.06f)
            lineTo(w - foldSize, h * 0.06f)
            lineTo(w - 0.06f * w, h * 0.06f + foldSize)
            lineTo(w - 0.06f * w, h * 0.94f - r)
            quadraticTo(w - 0.06f * w, h * 0.94f, w - 0.06f * w - r, h * 0.94f)
            lineTo(r, h * 0.94f)
            quadraticTo(w * 0.06f, h * 0.94f, w * 0.06f, h * 0.94f - r)
            lineTo(w * 0.06f, h * 0.06f + r)
            quadraticTo(w * 0.06f, h * 0.06f, r, h * 0.06f)
            close()
        } else {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    rect = androidx.compose.ui.geometry.Rect(Offset(w * 0.12f, h * 0.06f), Size(w * 0.76f, h * 0.88f)),
                    cornerRadius = CornerRadius(r, r),
                )
            )
        }
    }
    drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
}
