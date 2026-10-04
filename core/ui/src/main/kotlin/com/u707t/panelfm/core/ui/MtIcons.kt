package com.u707t.panelfm.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * MT 底栏 / 工具栏**矢量图标**（路径数据从 MT 2.14.5 的 vector drawable 中提取）。
 *
 * 为什么不用文字符号（← → ＋）：MT 用的是 24dp 线性图标，文字符号的字宽/基线
 * 在不同字体下会漂移，观感和点击热区都不对。
 */
enum class MtIcon(val pathData: String) {
    /** 后退 */
    BACK("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"),

    /** 前进 */
    FORWARD("M12,4l-1.41,1.41L16.17,11H4v2h12.17l-5.58,5.59L12,20l8,-8z"),

    /** 上级目录 */
    UP("M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8z"),

    /** 新建 */
    PLUS("M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"),

    /** 同步（两个循环箭头） */
    SYNC("M12,4V1L8,5l4,4V6c3.31,0 6,2.69 6,6 0,1.01 -0.25,1.97 -0.7,2.8l1.46,1.46C19.54,15.03 20,13.57 20,12c0,-4.42 -3.58,-8 -8,-8zM12,18c-3.31,0 -6,-2.69 -6,-6 0,-1.01 0.25,-1.97 0.7,-2.8L5.24,7.74C4.46,8.97 4,10.43 4,12c0,4.42 3.58,8 8,8v3l4,-4 -4,-4v3z"),

    /** 交换（双向箭头） */
    SWAP("M6.99,11L3,15l3.99,4v-3H14v-2H6.99V11zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z"),

    /** 关闭（MT FAB ✕，0x7f080110） */
    CLOSE("M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"),

    /** 剪贴板（MT FAB，0x7f080124） */
    CLIPBOARD("M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,18H5V4h2v3h10V4h2v14z"),

    /** 更多（⋮） */
    MORE("M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z"),

    /** 侧边栏（☰） */
    MENU("M3,18h18v-2H3v2zM3,13h18v-2H3v2zM3,6v2h18V6H3z"),

    /** 刷新 */
    REFRESH("M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -8,8s3.58,8 8,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z"),

    /** 搜索 */
    SEARCH("M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"),

    /** 排序 */
    SORT("M3,18h6v-2H3v2zM3,6v2h18V6H3zM3,13h12v-2H3v2z"),

    /** 删除（垃圾桶） */
    DELETE("M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"),

    /** 重命名（编辑笔） */
    RENAME("M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z"),

    /** 压缩 */
    COMPRESS("M20,6h-8l-2,-2H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2zM14,10h2v2h-2v-2zM14,14h2v2h-2v-2zM10,10h2v2h-2v-2zM10,14h2v2h-2v-2z"),

    /** 属性/信息 */
    INFO("M11,7h2v2h-2V7zM11,11h2v6h-2v-6zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z"),

    /** 分享 */
    SHARE("M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92s2.92,-1.31 2.92,-2.92 -1.31,-2.92 -2.92,-2.92z"),
}

/**
 * 绘制 MT 矢量图标（24dp viewport，路径按 24×24 坐标书写）。
 * 用 Compose 内置 [PathParser] 解析 pathData，无需额外的 drawable 资源。
 */
@Composable
fun MtVectorIcon(
    icon: MtIcon,
    modifier: Modifier = Modifier,
    size: Dp = MtSpec.BottomBarIcon,
    tint: Color = Color.Unspecified,
) {
    val color = if (tint == Color.Unspecified) LocalContentColor.current else tint
    val path = remember(icon) { PathParser().parsePathString(icon.pathData).toPath() }
    Canvas(modifier.size(size)) {
        val sx = this.size.width / 24f
        val sy = this.size.height / 24f
        withTransform({ scale(sx, sy, pivot = Offset.Zero) }) {
            drawPath(path, color = color)
        }
    }
}
