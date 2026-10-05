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

    /**
     * 顶栏高度（`0x7f070002` = 56dp；横屏 48dp）。
     *
     * MT 实测（截图）：顶栏是**一块**（`09046B` 里的自定义 View `09038A` 自己画
     * ☰ + 路径 + 统计 + ⋮），不是「工具行 + 标题行」两层。
     */
    val TopBarHeight = 56.dp

    /** 顶栏标题文字（路径，居中，18sp） */
    val TopBarTitleSize = 18.sp

    /** 顶栏副标题文字（统计，居中，13sp） */
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

    /** FAB 底色（`0x7f060042` = `#FFFF0000`，MT 所有 FAB 的 backgroundTint） */
    val FabRed = Color(0xFFFF0000)

    // ------------------------------------------------------------------ 顶栏动作条 / 底栏 / 菜单

    /** 顶栏动作条图标（`0x7f0c0034` 的 ImageView = 22dp） */
    val ActionIcon = 22.dp

    /** ⋮ 菜单左侧图标列宽（MT 菜单项：左图标 + 文字） */
    val MenuIcon = 22.dp

    /** 底栏按钮点击区宽度（MT 底栏 5 个按钮均分，最小 56dp） */
    val BottomButtonWidth = 56.dp

    /** 对话框内容区 paddingTop（`@7F070025` = 18dp） */
    val DialogPaddingTop = 18.dp

    // ------------------------------------------------------------------ 窗格阴影（附录 E.2）

    /** 活动窗格边缘阴影宽度（shadow_left/right = 5dp） */
    val PaneShadow = 5.dp

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

    /** FAB 容器/内容色（MT：浅色主题 #EFEFEF 底 + #3C3C3C 图标） */
    val FabContainer = Color(0xFFEFEFEF)
    val FabContent = Color(0xFF3C3C3C)

    /** FAB 容器/内容色（夜间） */
    val FabContainerDark = Color(0xFF3A3A3A)
    val FabContentDark = Color(0xFFE0E0E0)

    /** 列表行选中底色（MT 的强调蓝浅底） */
    val RowSelectedLight = Color(0x241976D2)
    val RowSelectedDark = Color(0x2E42A5F5)

    /** 文件夹剪影色（画在浅色方块内 → 深色；夜间反相） */
    val FolderGlyphLight = Color(0xFF3C3C3C)
    val FolderGlyphDark = Color(0xFFE0E0E0)

    /** 分段标题色（侧拉栏「本地 / 网络 / 工具」；MT 截图实测是中灰，不是纯黑） */
    val SectionTitleLight = Color(0xFF666666)
    val SectionTitleDark = Color(0xFFBBBBBB)

    // ------------------------------------------------------------------ 列表行图标方块（对照 MT 截图）

    /**
     * 行内图标方块底色。
     *
     * **MT 实测（截图）：深底 + 白剪影** —— 文件夹是近黑圆角方块（`#FF3C3C3C`）
     * 里画白色文件夹剪影；文件是**按类型着色**的方块 + 白色类型缩写。
     * （早前有一版误判成「浅底 + 深剪影」，与 MT 截图不符，已改回。）
     */
    val FolderTileLight = Color(0xFF3C3C3C)
    val FolderTileDark = Color(0xFF3C3C3C)

    /** 文件行图标方块底色（按类型着色，见 [FileIcons]） */
    val FileTileLight = Color(0xFF546E7A)
    val FileTileDark = Color(0xFF546E7A)

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

/**
 * MT 的手势参数（文档附录 G.5「手势参数与阈值（可量化复刻）」）。
 *
 * MT 的真实阈值写在被混淆的 dex 里，无法直接读出；文档给的是**与 MT 观感一致的推荐值**，
 * 这里集中定义，避免各界面各写一套。
 *
 * | 参数 | 值 | 依据 |
 * |---|---|---|
 * | 长按触发时间 | 400ms（系统默认 500ms，MT 更灵敏） | 长按是主入口，过慢会与滑动冲突 |
 * | 长按位移容差 | 12dp | 与 FAB margin 一致 |
 * | 左右滑动选择判定 | ≥ 24dp 且 \|dx\| > 2\|dy\| | `0x7f1106f3`「左右滑动文件可直接选择」 |
 * | 右滑出菜单阈值 | ≥ 48dp | `0x7f110697`「右滑列表项可进行更多操作」 |
 * | 上滑书签热区 | dy < -32dp 且 \|dy\| > \|dx\| | `0x7f1100e0` |
 * | 「再按一次」窗口 | 2000ms | `0x7f11055c/588/587/6fa` |
 * | 扫选边缘滚动的热区 / 最高速 | 48dp / 14dp 每帧 | 观感值（MT 无对应资源，见 MtGesture 内注释） |
 */
object MtGesture {
    /** 长按触发时间（ms）：MT 比系统默认（500ms）更灵敏 */
    const val LongPressMs = 400L

    /** 长按位移容差（dp）：超过即认为用户在滑动，不是长按 */
    const val LongPressSlopDp = 12f

    /** 左右滑动进入多选的判定：水平位移 ≥ 24dp 且 |dx| > 2|dy| */
    const val SwipeSelectDp = 24f

    /** 右滑出菜单的判定：≥ 48dp（仅在已进入多选态时启用，见文档 F.5 冲突消解顺序第 5 条） */
    const val SwipeMenuDp = 48f

    /** 底栏上滑书签：累计位移 ≥ 32dp 且纵向占优 */
    const val SwipeBookmarkDp = 32f

    /** 「再按一次 X」的确认窗口（ms） */
    const val PressAgainMs = 2000L

    // ---- 扫选（左右滑动直接选择）的「跟手」参数 ----------------------------------
    //
    // 这四个 MT 没有可读的参数来源（APK 里没有对应资源），是按观感定的一档保守值；
    // 集中放这里，实机试了觉得手感不对只改这一处。

    /** 扫选时列表边缘的自动滚动热区（dp）：手指进到这个范围内就开始滚列表 */
    const val SweepEdgeDp = 48f

    /** 扫选时边缘自动滚动的最大速度（dp/帧，约 60fps → 840dp/s） */
    const val SweepMaxStepDp = 14f

    /** 跟手预览：行位移相对手指位移的阻尼（0.25 = 只跟一小段，避免整行滑出屏幕） */
    const val SweepPreviewDamp = 0.25f

    /** 跟手预览的最大位移（dp） */
    const val SweepPreviewDp = 16f

    /** 横向滑动是否构成「进入多选」（MT 0x7f1106f3 的判定）。 */
    fun isSwipeSelect(dxDp: Float, dyDp: Float): Boolean =
        kotlin.math.abs(dxDp) >= SwipeSelectDp && kotlin.math.abs(dxDp) > kotlin.math.abs(dyDp) * 2f

    /** 右滑是否构成「滑出更多操作」（MT 0x7f110697；仅在多选态下生效）。 */
    fun isSwipeMenu(dxDp: Float, dyDp: Float): Boolean =
        dxDp >= SwipeMenuDp && dxDp > kotlin.math.abs(dyDp) * 2f

    /** 底栏上滑是否构成「打开书签」（MT 0x7f1107ca）。 */
    fun isSwipeBookmark(dyDp: Float, dxDp: Float): Boolean =
        dyDp <= -SwipeBookmarkDp && kotlin.math.abs(dyDp) > kotlin.math.abs(dxDp)
}
