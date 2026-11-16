package com.u707t.panelfm.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.ui.DividerPx
import com.u707t.panelfm.core.ui.MtIcon
import com.u707t.panelfm.core.ui.MtVectorIcon
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.launch

// ================================================================================================
// 书签底部抽屉（MT 原版 `0x7f1107ca`「从底部工具栏上滑即可打开书签」的外壳）。
//
// 与 MT 一致的几件事：
//  - 从底栏上滑拉出，**默认停在半屏**（MT 截图实测 ≈53%），拖标题行可拉到全屏、下拉收起；
//  - 顶部是「最近 / 书签」标签页 + 右侧 ⋮，底部是**一条 1px 分割线 + 居中 ⌄**（点它 = 收起）；
//  - 面板是**直角**贴底的一块（MT 不用圆角底部卡片），背后文件列表压暗，点遮罩 / 返回键关闭。
//
// 内容（列表 / 菜单 / 拖动排序）在 BookmarkPanel.kt，独立书签页复用同一份。
// ================================================================================================

/** 遮罩最大不透明度（MT 截图：背后文件列表明显压暗，但仍能看出内容） */
private const val ScrimMaxAlpha = 0.42f

/** 底部 ⌄ 收起按钮那一行的高度 */
private val FooterHeight = 42.dp

@Composable
fun MtBookmarkDrawer(
    container: AppContainer,
    controller: BrowserController,
    tab: BookmarkTab,
    onTabChange: (BookmarkTab) -> Unit,
    pathOf: (VfsUri) -> String,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val half = BookmarkDrawerAnchor.HALF.fraction
    val full = BookmarkDrawerAnchor.FULL.fraction
    val height = remember { Animatable(0f) }
    // 抽屉是浮层：返回键先收抽屉（组合顺序在 DualPaneScreen 之后 → 它的 BackHandler 先拿到事件）
    BackHandler { scope.launch { close(height, onClose) } }
    LaunchedEffect(Unit) {
        height.animateTo(half, tween(220))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val totalPx = with(LocalDensity.current) { maxHeight.toPx() }.coerceAtLeast(1f)
        // 拖动回调是 rememberDraggableState（无 key，只建一次）：屏幕高度（旋转 / 分屏）必须走
        // rememberUpdatedState，否则一直用第一次组合时的 px，拖动灵敏度会随分辨率错位。
        val totalPxNow = rememberUpdatedState(totalPx)
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        // ---------------- 遮罩：点一下收起
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Color.Black.copy(alpha = ScrimMaxAlpha * (height.value / half).coerceIn(0f, 1f))
                )
                // 无涟漪点击区（整屏压暗层）：点击 / 无障碍「点击」都走同一个收起动画
                .clickableNoRipple { scope.launch { close(height, onClose) } }
                .semantics { contentDescription = "收起书签" },
        )

        // ---------------- 面板本体（直角贴底）
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(maxHeight * height.value)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            DividerPx()
            BookmarkPanel(
                container = container,
                controller = controller,
                tab = tab,
                onTabChange = onTabChange,
                onOpened = { scope.launch { close(height, onClose) } },
                pathOf = pathOf,
                modifier = Modifier.weight(1f),
                // 拖标题行 = 拉高 / 压低抽屉；松手吸附到最近的一档
                headerModifier = Modifier.draggable(
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            height.snapTo((height.value - delta / totalPxNow.value).coerceIn(0f, full))
                        }
                    },
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        val target = when {
                            // 快速上滑 = 直接全屏；快速下滑 = 直接收起（MT 的甩动手感）
                            velocity < -900f -> BookmarkDrawerAnchor.FULL
                            velocity > 900f -> BookmarkDrawerAnchor.CLOSE
                            else -> bookmarkDrawerAnchor(height.value)
                        }
                        scope.launch {
                            height.animateTo(target.fraction, tween(220))
                            if (target == BookmarkDrawerAnchor.CLOSE) onClose()
                        }
                    },
                ),
            )
            // ---------------- 底部：1px 分割线 + 居中 ⌄（MT 截图里的收起按钮）
            DividerPx()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(FooterHeight + navBottom),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(FooterHeight)
                        .clickableNoRipple { scope.launch { close(height, onClose) } }
                        .semantics {
                            role = Role.Button
                            contentDescription = "收起书签"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    MtVectorIcon(
                        icon = MtIcon.UNFOLD_DOWN,
                        size = 22.dp,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 收起动画（0.2s 落到 0 再通知宿主移除浮层，避免「啪」地消失） */
private suspend fun close(height: Animatable<Float, AnimationVector1D>, onClose: () -> Unit) {
    height.animateTo(0f, tween(200))
    onClose()
}
