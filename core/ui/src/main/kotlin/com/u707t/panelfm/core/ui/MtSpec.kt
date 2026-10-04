package com.u707t.panelfm.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MT 管理器 2.14.5 的**实测规格**（从 APK 资源中提取，见 `/workspace/mt-analysis`）。
 *
 * 用途：让 UI 复刻有据可依 —— 所有数值都对应 MT 的资源 ID 与取值，
 * 改 UI 时优先引用这里的常量，不要各写各的魔法数字。
 *
 * 数据来源：
 *  - 行布局 `0x7f0c00e4`（主列表项）：图标 32dp / 名称 15sp / 副标题 10sp / 间距 8dp
 *  - 行布局 `0x7f0c00e2`（LinearLayout 版）：padding 8dp + 文本左 10dp + 名称上下 1dp
 *  - 颜色：`0x7f060043`（主文字 #ee000000 / 夜间 #eed0d0d0）、`0x7f060047`（次文字 #99000000）
 *  - 顶栏：`attr 0x7f040110`（标题栏背景）= 浅色主题 **#ff151515**、深色主题 #ff303030
 *  - 强调色：`0x7f0400ed` = #ff1976d2（浅）/ #ff42a5f5（深）
 *  - 分割线：`0x7f06003a` = #ffbbbbbb（浅）/ #ff505050（深）
 */
object MtSpec {

    // ------------------------------------------------------------------ 列表行（0x7f0c00e4 / 0x7f0c00e2）

    /** 行内图标尺寸（0x7f0c00e4 的 0901B6 = 32dp） */
    val RowIcon = 32.dp

    /** 图标与文字之间的间距（layout_marginStart/Left = 8dp） */
    val RowIconGap = 8.dp

    /** 行内边距（0x7f0c00e2 的 padding = 8dp） */
    val RowPadding = 8.dp

    /** 文件名文字大小（15sp） */
    val RowNameSize = 15.sp

    /** 副标题（时间 / 大小）文字大小 = 10sp */
    val RowSubSize = 10.sp

    /** 名称行上下 padding（0x7f0c00e2 的 1dp） */
    val RowNamePadding = 1.dp

    /** 行最小高度：图标 32 + 上下 padding 8*2 = 48dp（与 MT 的 48dp dimen 一致） */
    val RowHeight = 48.dp

    // ------------------------------------------------------------------ 顶栏（0x7f0c0033 的 09046B）

    /** 顶栏高度（0x7f070002 = 56dp；横屏 48dp） */
    val TopBarHeight = 56.dp

    /** 顶栏标题文字（18sp，居中） */
    val TopBarTitleSize = 18.sp

    /** 顶栏副标题文字（13sp，居中） */
    val TopBarSubSize = 13.sp

    // ------------------------------------------------------------------ 底栏（0x7f0c0033 的 09007F）

    /** 底栏高度（0x7f070031 = 64dp） */
    val BottomBarHeight = 64.dp

    /** 底栏图标尺寸（24dp 图标 + 点击区整高） */
    val BottomBarIcon = 24.dp

    // ------------------------------------------------------------------ FAB（0x7f0c0033 的 090166/09016A）

    /** 窗格 FAB 尺寸（fabCustomSize = 50dp，图标 20dp，边距 12dp / 74dp） */
    val FabSize = 50.dp
    val FabIcon = 20.dp
    val FabMargin = 12.dp
    val FabStackStep = 74.dp

    // ------------------------------------------------------------------ 颜色（0x7f0600xx）

    /** 顶栏背景：浅色主题 #ff151515（**MT 的顶栏始终是深色**） */
    val TopBarLight = Color(0xFF151515)

    /** 顶栏背景：深色主题 #ff303030 */
    val TopBarDark = Color(0xFF303030)

    /** 顶栏文字（深底上的亮色） */
    val TopBarText = Color(0xFFF2F2F2)
    val TopBarSubText = Color(0xFFB9B9B9)

    /** 列表主文字 #ee000000（浅）/ #eed0d0d0（深） */
    val RowNameLight = Color(0xEE000000)
    val RowNameDark = Color(0xEED0D0D0)

    /** 列表副标题 #99000000（浅）/ #99c4c4c4（深） */
    val RowSubLight = Color(0x99000000)
    val RowSubDark = Color(0x99C4C4C4)

    /** 分割线 #ffbbbbbb（浅）/ #ff505050（深） */
    val DividerLight = Color(0xFFBBBBBB)
    val DividerDark = Color(0xFF505050)

    /** 强调蓝 #ff1976d2（浅）/ #ff42a5f5（深） */
    val AccentLight = Color(0xFF1976D2)
    val AccentDark = Color(0xFF42A5F5)

    /** 文件夹图标色（MT 是深灰近黑；夜间转浅） */
    val FolderGlyphLight = Color(0xFF3C3C3C)
    val FolderGlyphDark = Color(0xFFE0E0E0)

    // ------------------------------------------------------------------ 文件类型图标色

    /** MT 的文件类型图标主色（图片绿 / 视频红 / 音频紫 / 压缩包橙 / APK 绿 / 字体青 / PDF 红 / 代码蓝 / 文本灰） */
    val IconImage = Color(0xFF43A047)
    val IconVideo = Color(0xFFE53935)
    val IconAudio = Color(0xFF8E24AA)
    val IconArchive = Color(0xFFFB8C00)
    val IconApk = Color(0xFF00C853)
    val IconFont = Color(0xFF00ACC1)
    val IconPdf = Color(0xFFD32F2F)
    val IconCode = Color(0xFF1E88E5)
    val IconText = Color(0xFF546E7A)
    val IconOther = Color(0xFF607D8B)
}
