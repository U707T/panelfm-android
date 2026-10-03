package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.model.ConflictInfo
import com.u707t.panelfm.core.model.SortSpec
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri

enum class PaneSide { LEFT, RIGHT;
    val other: PaneSide get() = if (this == LEFT) RIGHT else LEFT
}

/** 一个窗格标签页 = 独立的浏览位置 + 前进/后退历史栈 */
data class PaneTab(
    val uri: VfsUri,
    val connectionId: Long? = null,
    val label: String = "内部存储",
    val back: List<VfsUri> = emptyList(),
    val forward: List<VfsUri> = emptyList(),
)

data class PaneState(
    val tabs: List<PaneTab> = listOf(PaneTab(VfsUri.of("local", "emulated", "/"), label = "内部存储")),
    val activeTab: Int = 0,
    val items: List<FileMetadata> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val selection: Set<String> = emptySet(),
    val sort: SortSpec = SortSpec(),
    val showHidden: Boolean = false,
    val scrollIndex: Int = 0,
    val space: com.u707t.panelfm.core.vfs.SpaceInfo? = null,
    /** 目录内搜索关键字（MT 的「搜索」） */
    val search: String = "",
    /** 类型过滤（MT 的「过滤」）：null = 全部 */
    val filterKind: String? = null,
) {
    val tab: PaneTab get() = tabs.getOrElse(activeTab) { tabs.first() }
    val uri: VfsUri get() = tab.uri
    val hasSelection: Boolean get() = selection.isNotEmpty()
    val dirCount: Int get() = items.count { it.isDirectory }
    val fileCount: Int get() = items.size - dirCount
    val filtered: Boolean get() = search.isNotBlank() || filterKind != null
    val selectedItems: List<FileMetadata> get() = items.filter { selection.contains(it.uri.toString()) }
}

/** 移动前的二次确认（需求：移动必须确认；跨协议要写明中转语义） */
data class PendingMove(
    val sources: List<VfsUri>,
    val destDir: VfsUri,
    val count: Int,
    val bytes: Long,
    val crossVfs: Boolean,
    val fromLabel: String,
    val toLabel: String,
)

data class BrowserUiState(
    val left: PaneState = PaneState(),
    val right: PaneState = PaneState(),
    val focused: PaneSide = PaneSide.LEFT,
    /** 跨窗格操作前：两侧路径栏同时高亮 */
    val highlight: Boolean = false,
    val status: String? = null,
    val pendingMove: PendingMove? = null,
    val conflict: ConflictInfo? = null,
    val property: FileMetadata? = null,
    val tasks: List<TransferTaskSnapshot> = emptyList(),
    val singlePane: Boolean = false,
) {
    fun pane(side: PaneSide): PaneState = if (side == PaneSide.LEFT) left else right
    val focusedPane: PaneState get() = pane(focused)
    val otherPane: PaneState get() = pane(focused.other)
}
