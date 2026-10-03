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

/** 窗格几何（用于拖拽落点判定）：列表区域与行高 */
data class PaneGeometry(
    val left: Float = 0f,
    val top: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    /** 列表首行（`..` 行）的顶部 Y */
    val listTop: Float = 0f,
    val rowHeightPx: Float = 1f,
    val hasParentRow: Boolean = false,
    val itemCount: Int = 0,
) {
    fun contains(x: Float, y: Float): Boolean = x in left..(left + width) && y in top..(top + height)

    /** 命中第几行；-1 = 空白/越界 */
    fun rowIndexAt(y: Float): Int {
        val offset = y - listTop
        if (offset < 0) return -1
        val logical = (offset / rowHeightPx).toInt() - (if (hasParentRow) 1 else 0)
        return if (logical in 0 until itemCount) logical else -1
    }
}

/** 跨窗格拖拽会话（松手在对面行 = 复制；松手在对面空白 = 移动） */
data class DragState(
    val from: PaneSide,
    val sources: List<VfsUri>,
    val label: String,
    val startX: Float,
    val startY: Float,
    val x: Float,
    val y: Float,
) {
    val over: PaneSide? get() = if (x < 0) null else if (from == PaneSide.LEFT && x > 0) PaneSide.RIGHT else if (from == PaneSide.RIGHT) PaneSide.LEFT else null
}

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
    val diff: DiffResult? = null,
    val drag: DragState? = null,
    val geometry: Map<PaneSide, PaneGeometry> = emptyMap(),
    /** 拖拽落点提示：复制 / 移动 */
    val dropHint: String? = null,
) {
    fun pane(side: PaneSide): PaneState = if (side == PaneSide.LEFT) left else right
    val focusedPane: PaneState get() = pane(focused)
    val otherPane: PaneState get() = pane(focused.other)
}
