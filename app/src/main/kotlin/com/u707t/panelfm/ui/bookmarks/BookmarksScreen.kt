package com.u707t.panelfm.ui.bookmarks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.ui.MtScreenTopBar
import com.u707t.panelfm.core.ui.safeAreaPadding
import com.u707t.panelfm.core.vfs.local.LocalVolumes
import com.u707t.panelfm.ui.browser.BookmarkPanel
import com.u707t.panelfm.ui.browser.BookmarkTab
import com.u707t.panelfm.ui.browser.pathDisplayOf

/**
 * 书签页（从侧边栏 / 主页「书签」进入的独立页）。
 *
 * MT 的完整书签面板本来是**从底栏上滑拉出的底部抽屉**（`0x7f1107ca`），抽屉里是
 * 「最近」（`0x7f1106a6`）与「书签」（`0x7f1100da`）两个标签页 —— 抽屉本身是
 * `ui.browser.BookmarkDrawer`（和双列页同处一层，所以放在 `ui.browser` 里，避免两个
 * feature 包互相 import）。这个独立页只是同一份内容（`ui.browser.BookmarkPanel`）的
 * 全屏外壳，两个入口共用一套标签页 / 列表 / 长按排序 / 行菜单，不各写一份。
 */
@Composable
fun BookmarksScreen(container: AppContainer, onBack: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val volumes = remember { LocalVolumes.volumes(context) }
    var tab by remember { mutableStateOf(BookmarkTab.BOOKMARK) }

    Column(Modifier.fillMaxSize().safeAreaPadding()) {
        MtScreenTopBar(
            title = "书签",
            onBack = onBack,
        )
        BookmarkPanel(
            container = container,
            controller = container.browser,
            tab = tab,
            onTabChange = { tab = it },
            onOpened = onOpen,
            pathOf = { pathDisplayOf(it, volumes) },
        )
    }
}
