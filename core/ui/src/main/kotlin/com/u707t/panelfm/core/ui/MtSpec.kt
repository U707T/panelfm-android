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
 *  - 行布局 `0x7f0c00e4` / `0x7f0c00e2` + **MT 2.14.5 同机截图逐像素复测**（2026-10-05 重校）：
 *    行距 ≈40dp / 图标 ≈28dp / 名称 ≈13sp / 副标题 10sp / 图标后间距 ≈6dp
 *  - 颜色：`0x7f060043`（主文字 #ee000000 / 夜间 #eed0d0d0）、`0x7f060047`（次文字 #99000000）
 *  - 顶栏：`attr 0x7f040110`（标题栏背景）= 浅色主题 **#ff151515**、深色主题 #ff303030
 *  - 强调色：`0x7f0400ed` = #ff1976d2（浅）/ #ff42a5f5（深）
 *  - 分割线：`0x7f06003a` = #ffbbbbbb（浅）/ #ff505050（深）
 */
object MtSpec {

    // ------------------------------------------------------------------ 列表行（0x7f0c00e4 / 0x7f0c00e2）
    //
    // v1.3.2 尺寸重校：对着 **MT 2.14.5 同机截图** 逐像素复测（1280px 宽屏、3.268px/dp）：
    //  - MT 行距 130px ≈ 39.8dp（不是旧表里的 48dp）；图标 90px ≈ 27.5dp（不是 32dp）；
    //  - 名称墨高 33px ≈ 13sp（15sp 渲染到 38px，明显偏大）；副标题 24px，10sp 正确。
    // 结论：把行高 / 图标 / 名称号整体收一档，即本段常量。

    /** 行内图标尺寸（MT 2.14.5 实测渲染 ≈27.5dp；取整 28dp） */
    val RowIcon = 28.dp

    /** 图标与文字之间的间距（MT 实测渲染 ≈5.5dp；取整 6dp） */
    val RowIconGap = 6.dp

    /** 行内边距（MT 实测渲染左缘 ≈5dp；取 6dp，与 gap 对称） */
    val RowPadding = 6.dp

    /** 文件名文字大小（MT 实测墨高 33px ≈ 13sp） */
    val RowNameSize = 13.sp

    /** 副标题（时间 / 大小）文字大小 = 10sp（MT 实测墨高 24px，10sp 正确） */
    val RowSubSize = 10.sp

    /** 名称行上下 padding（0x7f0c00e2 的 1dp） */
    val RowNamePadding = 1.dp

    /** 行高：MT 实测行距 130px ≈ 39.8dp（图标 27.5 + 上下 6×2 ≈ 40） */
    val RowHeight = 40.dp

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

    /** 顶栏副标题文字（统计；MT 实测墨高 33px ≈ 12sp） */
    val TopBarSubSize = 12.sp

    // ------------------------------------------------------------------ 二级页面顶栏（统一规格）

    /**
     * 二级页面（设置 / 任务 / 书签 / 工具 / 预览…）顶栏高度。
     *
     * 统一到 56dp：这些页面此前各写各的内边距（h8v6 vs h4v2），
     * 高度随内容浮动，切页面时标题会「跳」一下。
     */
    val ScreenBarHeight = 56.dp

    /** 二级页面标题字号：统一 20sp（此前 14 / 16 / 20sp 三种混用）。 */
    val ScreenTitleSize = 20.sp

    // ------------------------------------------------------------------ 圆角（统一取值）

    /** 小控件圆角（输入框、菜单、小面板） */
    val CornerSmall = 8.dp

    /** 常规容器圆角（对话框、卡片、悬浮面板） */
    val CornerMedium = 12.dp

    /** 大容器圆角（全屏浮层、底部面板） */
    val CornerLarge = 16.dp

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

    /**
     * 右窗格活动阴影向左微调。
     *
     * 阴影画在右列左缘、分隔条（10dp 触摸区）的右侧，实机看起来压进了右列文件名。
     * 向左 4dp，贴回中缝，不改左列。
     */
    val RightPaneShadowNudge = 4.dp

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
    //
    // v1.3.2 复测：MT 2.14.5 同机截图里文件夹方块底色 ≈ #2B2B2B（此前记录 #3C3C3C 偏浅）。

    /**
     * 行内图标方块底色。
     *
     * **MT 实测（2.14.5 截图逐像素）**：深底 + 白剪影 —— 文件夹是近黑圆角方块
     * （≈ `#FF2B2B2B`）里画白色文件夹剪影；文件是**按类型着色**的方块 + 白色类型图形。
     */
    val FolderTileLight = Color(0xFF2B2B2B)
    val FolderTileDark = Color(0xFF2B2B2B)

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
 * | 上滑书签热区 | dy < -32dp 且 \|dy\| > \|dx\| | `0x7f1100e0` |
 * | 「再按一次」窗口 | 2000ms | `0x7f11055c/588/587/6fa` |
 * | 滑动选中的「行动效」位移 | 12dp | 观感值（MT 无对应资源，见 MtGesture 内注释） |
 */
object MtGesture {
    /** 长按触发时间（ms）：MT 比系统默认（500ms）更灵敏 */
    const val LongPressMs = 400L

    /** 长按位移容差（dp）：超过即认为用户在滑动，不是长按 */
    const val LongPressSlopDp = 12f

    /** 左右滑动选中的判定：水平位移 ≥ 24dp 且 |dx| > 2|dy|（MT 0x7f1106f3） */
    const val SwipeSelectDp = 24f

    /** 底栏上滑书签：累计位移 ≥ 32dp 且纵向占优 */
    const val SwipeBookmarkDp = 32f

    /** 「再按一次 X」的确认窗口（ms） */
    const val PressAgainMs = 2000L

    // ---- 滑动选中的「行动效」------------------------------------------------------
    //
    // MT 没有可读的参数来源（APK 里没有对应资源），是按观感定的一档保守值；
    // 集中放这里，实机试了觉得手感不对只改这一处。

    /** 滑动选中时，被选中的那一行朝滑动方向轻推的距离（dp），随后弹回 */
    const val SwipeAnimDp = 12f

    /** 横向滑动是否构成「进入多选」（MT 0x7f1106f3 的判定）。 */
    fun isSwipeSelect(dxDp: Float, dyDp: Float): Boolean =
        kotlin.math.abs(dxDp) >= SwipeSelectDp && kotlin.math.abs(dxDp) > kotlin.math.abs(dyDp) * 2f

    /** 底栏上滑是否构成「打开书签」（MT 0x7f1107ca）。 */
    fun isSwipeBookmark(dyDp: Float, dxDp: Float): Boolean =
        dyDp <= -SwipeBookmarkDp && kotlin.math.abs(dyDp) > kotlin.math.abs(dxDp)

    // ---- 播放器手势（隐藏控件后：左右滑进度 / 右侧竖滑音量 / 左侧竖滑亮度）---------------
    //
    // v1.3.6 统一：旧实现三者「进入阈值」与「灵敏度」各不相同 ——
    //  · 进入：只用系统 touchSlop 判定，且 `abs(dx) > abs(dy)` 在斜滑时会突然在
    //    进度 / 音量之间跳；一旦锁定方向又不再复核，手改了方向也不跟随；
    //  · 灵敏度：进度按「一屏宽 = 全长」，音量 / 亮度按「一屏高 = 满量程」，
    //    同一段手指位移在三者上的效果差得很远（割裂感的来源）。
    //
    // 现在照 IRIS `use_gesture.dart` 的口径统一为**与屏幕尺寸无关的固定物理量**，
    // 并加一条死区 + 主轴优势比，保证「什么方向就是什么功能」。

    /** 播放器手势的进入死区（dp，IRIS 用 8）：位移超过它才开始判定方向 */
    const val PlayerDeadZoneDp = 8f

    /**
     * 主轴优势比：某一轴的位移必须至少是另一轴的 [PlayerAxisBias] 倍才锁定方向。
     *
     * IRIS 用「绝对值大者胜」，斜滑（dx≈dy）时会来回横跳；加优势比后，
     * 45° 附近的斜滑**两个方向都不触发**（保持未定态），等用户意图清晰再进入 ——
     * 这是消除割裂感的关键一条。
     */
    const val PlayerAxisBias = 1.4f

    /** 进度灵敏度：每滑动多少 px 代表 1 秒（IRIS = 3） */
    const val PlayerSeekPxPerSecond = 3f

    /** 音量 / 亮度灵敏度：滑动多少 px 走完满量程（IRIS = 200） */
    const val PlayerLevelPxFullScale = 200f

    /**
     * 判定播放器手势方向：横滑返回 true（进度），竖滑返回 false（音量 / 亮度）。
     * 未达死区或主轴优势不足时返回 null（保持未定态）。
     */
    fun playerAxis(dxDp: Float, dyDp: Float): Boolean? {
        val ax = kotlin.math.abs(dxDp)
        val ay = kotlin.math.abs(dyDp)
        if (ax < PlayerDeadZoneDp && ay < PlayerDeadZoneDp) return null
        return when {
            ax > ay * PlayerAxisBias -> true
            ay > ax * PlayerAxisBias -> false
            else -> null
        }
    }

    /** 进度增量：按固定灵敏度换算（横向位移 px → 秒）。 */
    fun seekDeltaSeconds(dxPx: Float): Long = (dxPx / PlayerSeekPxPerSecond).toLong()

    /** 音量 / 亮度增量（0..1）：按固定灵敏度换算（纵向位移 px → 比例，向上为正）。 */
    fun levelDelta(dyPx: Float): Float = -dyPx / PlayerLevelPxFullScale
}
