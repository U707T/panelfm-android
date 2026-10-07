package com.u707t.panelfm.ui.preview

// ================================================================================================
// MediaScreen 拆分（2026-10-08 重审 §2）：播放器的矢量绘制与轻控件
//  - IconButton：底栏图标按钮（固定触控尺寸 + 禁用/关闭态降透明度）
//  - 8 个 DrawScope 图标：播放 / 暂停 / 上一下一集 / ±10s·15s / 锁 / 喇叭 / 随机 / 播放列表
//  - LevelPanel + LevelIcons：音量 / 亮度中央浮层（与 IRIS 逐项同规格）
//  - clock：秒表格式（m:ss / h:mm:ss）；PlayerSlider 在 MediaChrome.kt
// ================================================================================================

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtVectorIcon
import kotlin.math.cos
import kotlin.math.sin

/** 图标按钮：固定触控尺寸 + 矢量绘制内容（禁用整体降透明度） */
@Composable
internal fun IconButton(
    label: String,
    enabled: Boolean = true,
    large: Boolean = false,
    /** 开关类按钮的「关闭态」：降不透明度（IRIS 同款视觉） */
    dim: Boolean = false,
    onClick: () -> Unit,
    draw: DrawScope.() -> Unit,
) {
    Box(
        Modifier
            .size(if (large) 54.dp else 42.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(if (large) 11.dp else 8.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .alpha(if (!enabled) 0.35f else if (dim) 0.55f else 1f),
        ) { draw() }
    }
}

/** 播放 ▲ */
internal fun DrawScope.drawPlay() {
    val w = size.width
    val h = size.height
    val p = Path().apply {
        moveTo(w * 0.28f, h * 0.16f)
        lineTo(w * 0.86f, h * 0.5f)
        lineTo(w * 0.28f, h * 0.84f)
        close()
    }
    drawPath(p, Color.White)
}

/** 暂停 ‖ */
internal fun DrawScope.drawPause() {
    val w = size.width
    val h = size.height
    val barW = w * 0.22f
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.22f, h * 0.16f),
        size = Size(barW, h * 0.68f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.25f),
    )
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.56f, h * 0.16f),
        size = Size(barW, h * 0.68f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.25f),
    )
}

/** 上一集 |◀ / 下一集 ▶| */
internal fun DrawScope.drawSkip(next: Boolean) {
    val w = size.width
    val h = size.height
    val tri = Path().apply {
        if (next) {
            moveTo(w * 0.20f, h * 0.18f)
            lineTo(w * 0.72f, h * 0.5f)
            lineTo(w * 0.20f, h * 0.82f)
        } else {
            moveTo(w * 0.80f, h * 0.18f)
            lineTo(w * 0.28f, h * 0.5f)
            lineTo(w * 0.80f, h * 0.82f)
        }
        close()
    }
    drawPath(tri, Color.White)
    val barX = if (next) w * 0.76f else w * 0.16f
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(barX, h * 0.20f),
        size = Size(w * 0.09f, h * 0.60f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.03f),
    )
}

/** ↺10 / 15↻：圆弧箭头 + 中间数字 */
internal fun DrawScope.drawReplay(forward: Boolean, seconds: String) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
    val inset = w * 0.16f
    val arcSize = Size(w - inset * 2, h - inset * 2)
    val rectTopLeft = Offset(inset, inset)
    val startAngle = if (forward) -50f else 230f
    val sweep = if (forward) 290f else -290f
    drawArc(
        color = Color.White,
        startAngle = startAngle,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = rectTopLeft,
        size = arcSize,
        style = stroke,
    )
    // 箭头（在弧的终点，沿切线方向）
    val cx = inset + arcSize.width / 2f
    val cy = inset + arcSize.height / 2f
    val r = arcSize.width / 2f
    val endAngle = Math.toRadians((startAngle + sweep).toDouble())
    val ex = cx + r * cos(endAngle).toFloat()
    val ey = cy + r * sin(endAngle).toFloat()
    val tangent = endAngle + (if (sweep > 0) Math.PI / 2 else -Math.PI / 2)
    val tx = cos(tangent).toFloat()
    val ty = sin(tangent).toFloat()
    val arrow = Path().apply {
        moveTo(ex + tx * w * 0.14f, ey + ty * h * 0.14f)
        lineTo(ex - ty * w * 0.11f, ey + tx * w * 0.11f)
        lineTo(ex + ty * w * 0.11f, ey - tx * w * 0.11f)
        close()
    }
    drawPath(arrow, Color.White)
    // 数字（居中）
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = w * 0.40f
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
    }
    drawContext.canvas.nativeCanvas.drawText(
        seconds,
        cx,
        cy - (paint.descent() + paint.ascent()) / 2f,
        paint,
    )
}

/** 锁 / 开锁 */
internal fun DrawScope.drawLock(locked: Boolean) {
    val w = size.width
    val h = size.height
    val body = androidx.compose.ui.geometry.RoundRect(
        left = w * 0.24f, top = h * 0.44f, right = w * 0.76f, bottom = h * 0.88f,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f),
    )
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(body.left, body.top),
        size = Size(body.right - body.left, body.bottom - body.top),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f),
    )
    drawArc(
        color = Color.White,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(w * 0.34f, h * 0.16f),
        size = Size(w * 0.32f, h * 0.44f),
        style = Stroke(width = w * 0.10f, cap = StrokeCap.Round),
    )
    // 锁孔
    drawCircle(
        color = if (locked) Color(0xFF212121) else Color.Transparent,
        radius = w * 0.05f,
        center = Offset(w * 0.5f, h * 0.63f),
    )
}

/** 喇叭 / 静音 */
internal fun DrawScope.drawSpeaker(muted: Boolean) {
    val w = size.width
    val h = size.height
    val body = Path().apply {
        moveTo(w * 0.12f, h * 0.36f)
        lineTo(w * 0.30f, h * 0.36f)
        lineTo(w * 0.52f, h * 0.18f)
        lineTo(w * 0.52f, h * 0.82f)
        lineTo(w * 0.30f, h * 0.64f)
        lineTo(w * 0.12f, h * 0.64f)
        close()
    }
    drawPath(body, Color.White)
    if (muted) {
        drawLine(
            color = Color.White,
            start = Offset(w * 0.62f, h * 0.34f),
            end = Offset(w * 0.86f, h * 0.66f),
            strokeWidth = w * 0.09f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White,
            start = Offset(w * 0.86f, h * 0.34f),
            end = Offset(w * 0.62f, h * 0.66f),
            strokeWidth = w * 0.09f,
            cap = StrokeCap.Round,
        )
    } else {
        drawArc(
            color = Color.White,
            startAngle = -50f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(w * 0.42f, h * 0.30f),
            size = Size(w * 0.28f, h * 0.40f),
            style = Stroke(width = w * 0.08f, cap = StrokeCap.Round),
        )
        drawArc(
            color = Color.White,
            startAngle = -50f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(w * 0.40f, h * 0.16f),
            size = Size(w * 0.46f, h * 0.68f),
            style = Stroke(width = w * 0.08f, cap = StrokeCap.Round),
        )
    }
}

/**
 * 音量 / 亮度浮层 —— 与 IRIS **逐项同规格**（`gesture_overlay.dart` 的中央悬浮胶囊）：
 * `24dp 图标 + 12dp 间隔 + 100×4dp 圆角横条`，内边距 12/12/18/12、圆角 8dp、底色 black54。
 *
 * 数值只靠**图标形态**表达（IRIS 的手势浮层同样不写百分比文字）：
 *  - 音量：0 = 静音 / <50% = 小声 / ≥50% = 大声（volume_mute / down / up）；
 *  - 亮度：0 = 暗 / <100% = 中 / 满 = 亮（brightness_low / medium / high）。
 */
@Composable
internal fun LevelPanel(
    ratio: Float,
    kind: LevelKind,
    modifier: Modifier = Modifier,
) {
    val r = ratio.coerceIn(0f, 1f)
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.54f))
            .padding(start = 12.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MtVectorIcon(
            icon = when (kind) {
                LevelKind.VOLUME -> when {
                    r <= 0f -> LevelIcons.VOLUME_MUTE
                    r < 0.5f -> LevelIcons.VOLUME_DOWN
                    else -> LevelIcons.VOLUME_UP
                }
                LevelKind.BRIGHTNESS -> when {
                    r <= 0f -> LevelIcons.BRIGHTNESS_LOW
                    r < 1f -> LevelIcons.BRIGHTNESS_MEDIUM
                    else -> LevelIcons.BRIGHTNESS_HIGH
                }
            },
            size = 24.dp,
            tint = Color.White,
        )
        Spacer(Modifier.width(12.dp))
        // 轨道 #9E9E9E = Flutter Colors.grey（IRIS 原值），进度纯白
        Box(
            Modifier
                .width(100.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF9E9E9E)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(r)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White),
            )
        }
    }
}

internal enum class LevelKind { VOLUME, BRIGHTNESS }

/**
 * 浮层图标 = **Material Icons (Round)** —— 与 IRIS 用的 `Icons.*_rounded` 同一套字形
 * （IRIS 内置的 MaterialIcons 字体里就是这些轮廓）。路径数据取自 Material Icons
 * 官方 24dp SVG（`viewBox 0 0 24 24`）。
 */
private object LevelIcons {
    /** volume_mute_rounded：只有喇叭（音量 = 0） */
    val VOLUME_MUTE = MtIcon(
        paths = listOf("M7 10v4c0 .55.45 1 1 1h3l3.29 3.29c.63.63 1.71.18 1.71-.71V6.41c0-.89-1.08-1.34-1.71-.71L11 9H8c-.55 0-1 .45-1 1z"),
        viewport = 24f,
    )

    /** volume_down_rounded：喇叭 + 一道声波（小声） */
    val VOLUME_DOWN = MtIcon(
        paths = listOf("M18.5 12c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM5 10v4c0 .55.45 1 1 1h3l3.29 3.29c.63.63 1.71.18 1.71-.71V6.41c0-.89-1.08-1.34-1.71-.71L9 9H6c-.55 0-1 .45-1 1z"),
        viewport = 24f,
    )

    /** volume_up_rounded：喇叭 + 两道声波（大声） */
    val VOLUME_UP = MtIcon(
        paths = listOf("M3 10v4c0 .55.45 1 1 1h3l3.29 3.29c.63.63 1.71.18 1.71-.71V6.41c0-.89-1.08-1.34-1.71-.71L7 9H4c-.55 0-1 .45-1 1zm13.5 2c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 4.45v.2c0 .38.25.71.6.85C17.18 6.53 19 9.06 19 12s-1.82 5.47-4.4 6.5c-.36.14-.6.47-.6.85v.2c0 .63.63 1.07 1.21.85C18.6 19.11 21 15.84 21 12s-2.4-7.11-5.79-8.4c-.58-.23-1.21.22-1.21.85z"),
        viewport = 24f,
    )

    /**
     * brightness_low_rounded：空心太阳（亮度 = 0）。
     * 本项目亮度下限是 0.05（不滑到全黑），这一档实际不会出现 ——
     * 保留它只为与 IRIS 的三档映射逐字一致。
     */
    val BRIGHTNESS_LOW = MtIcon(
        paths = listOf("M20 15.31l1.9-1.9c.78-.78.78-2.05 0-2.83L20 8.69V6c0-1.1-.9-2-2-2h-2.69l-1.9-1.9c-.78-.78-2.05-.78-2.83 0L8.69 4H6c-1.1 0-2 .9-2 2v2.69l-1.9 1.9c-.78.78-.78 2.05 0 2.83l1.9 1.9V18c0 1.1.9 2 2 2h2.69l1.9 1.9c.78.78 2.05.78 2.83 0l1.9-1.9H18c1.1 0 2-.9 2-2v-2.69zM12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6z"),
        viewport = 24f,
    )

    /** brightness_medium_rounded：半芯太阳（亮度 < 100%） */
    val BRIGHTNESS_MEDIUM = MtIcon(
        paths = listOf("M20 15.31l1.9-1.9c.78-.78.78-2.05 0-2.83L20 8.69V6c0-1.1-.9-2-2-2h-2.69l-1.9-1.9c-.78-.78-2.05-.78-2.83 0L8.69 4H6c-1.1 0-2 .9-2 2v2.69l-1.9 1.9c-.78.78-.78 2.05 0 2.83l1.9 1.9V18c0 1.1.9 2 2 2h2.69l1.9 1.9c.78.78 2.05.78 2.83 0l1.9-1.9H18c1.1 0 2-.9 2-2v-2.69zm-8 1.59V7.1c0-.61.55-1.11 1.15-.99C15.91 6.65 18 9.08 18 12s-2.09 5.35-4.85 5.89c-.6.12-1.15-.38-1.15-.99z"),
        viewport = 24f,
    )

    /** brightness_high_rounded：满芯太阳（亮度 = 100%） */
    val BRIGHTNESS_HIGH = MtIcon(
        paths = listOf("M20 8.69V6c0-1.1-.9-2-2-2h-2.69l-1.9-1.9c-.78-.78-2.05-.78-2.83 0L8.69 4H6c-1.1 0-2 .9-2 2v2.69l-1.9 1.9c-.78.78-.78 2.05 0 2.83l1.9 1.9V18c0 1.1.9 2 2 2h2.69l1.9 1.9c.78.78 2.05.78 2.83 0l1.9-1.9H18c1.1 0 2-.9 2-2v-2.69l1.9-1.9c.78-.78.78-2.05 0-2.83L20 8.69zM12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6zm0-10c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4z"),
        viewport = 24f,
    )
}

/**
 * 随机播放：两条 **S 形交叉箭头**（照 IRIS 的 `shuffle_rounded` 观感）。
 *
 * 旧版是「两条横线 + 中间一个 X + 两个箭头」拼出来的，接近乱线 —— 重画为
 * 左右各一条平滑曲线、在中间交叉后接到末端「＞」箭头，远看就是一个标准的随机图标。
 */
internal fun DrawScope.drawShuffle() {
    val w = size.width
    val h = size.height
    val st = w * 0.085f
    val stroke = Stroke(width = st, cap = StrokeCap.Round)
    // 左上 → 右下的 S 曲线
    drawPath(
        Path().apply {
            moveTo(w * 0.08f, h * 0.30f)
            cubicTo(w * 0.36f, h * 0.30f, w * 0.44f, h * 0.70f, w * 0.70f, h * 0.70f)
            lineTo(w * 0.80f, h * 0.70f)
        },
        Color.White,
        style = stroke,
    )
    // 左下 → 右上的 S 曲线
    drawPath(
        Path().apply {
            moveTo(w * 0.08f, h * 0.70f)
            cubicTo(w * 0.36f, h * 0.70f, w * 0.44f, h * 0.30f, w * 0.70f, h * 0.30f)
            lineTo(w * 0.80f, h * 0.30f)
        },
        Color.White,
        style = stroke,
    )
    // 末端「＞」箭头
    fun head(y: Float) {
        drawLine(Color.White, Offset(w * 0.92f, y), Offset(w * 0.76f, y - h * 0.13f), strokeWidth = st, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(w * 0.92f, y), Offset(w * 0.76f, y + h * 0.13f), strokeWidth = st, cap = StrokeCap.Round)
    }
    head(h * 0.70f)
    head(h * 0.30f)
}

/** 播放列表：三条横线 + 右下播放三角（照 playlist_play 的通用观感） */
internal fun DrawScope.drawPlaylist() {
    val w = size.width
    val h = size.height
    val lh = h * 0.08f
    fun line(y: Float, x2: Float) {
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.06f, y),
            size = Size(w * (x2 - 0.06f), lh),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(lh / 2),
        )
    }
    line(h * 0.22f, 0.94f)
    line(h * 0.46f, 0.94f)
    line(h * 0.70f, 0.50f)
    val tri = Path().apply {
        moveTo(w * 0.55f, h * 0.56f)
        lineTo(w * 0.94f, h * 0.76f)
        lineTo(w * 0.55f, h * 0.96f)
        close()
    }
    drawPath(tri, Color.White)
}

/** 秒表格式：m:ss / h:mm:ss */
internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    // Locale.ROOT：数字格式不能跟随系统 locale（如阿拉伯语会把 0 显示成 ٠）
    return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, s)
    else "%d:%02d".format(java.util.Locale.ROOT, m, s)
}
