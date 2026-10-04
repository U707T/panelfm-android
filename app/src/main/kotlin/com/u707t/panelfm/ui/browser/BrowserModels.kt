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
    /**
     * 加载进度（复刻 MT 的加载遮罩）：0..1 = 已知进度（连接 → 列表 → 过滤 → 完成）；
     * null 且 [loading] = true → 不确定进度（转圈）。
     */
    val loadProgress: Float? = null,
    val error: String? = null,
    val selection: Set<String> = emptySet(),
    val sort: SortSpec = SortSpec(),
    val showHidden: Boolean = false,
    val scrollIndex: Int = 0,
    /** 需要滚动到可见的项（MT：搜索/跳转后定位到目标项；由 PaneView 消费后清空） */
    val scrollToUri: String? = null,
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

    /** 处于压缩包内时，压缩包自身的文件名（用于「解压到单独的文件夹」命名）；否则 null */
    val archiveHostName: String?
        get() = if (uri.scheme != "archive") null
        else com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(uri.path)
            ?.let { encoded -> runCatching { VfsUri.parse(VfsUri.decodeHost(encoded)).name }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
}

/** 搜索结果（MT 的搜索反馈：条数上限 / 主动停止 / 扫描量） */
data class SearchOutcome(
    val items: List<FileMetadata>,
    /** 是否被停止（用户点「停止搜索」/ 达到上限 / 扫描量封顶） */
    val stopped: Boolean,
    /** 实际扫描的条目数（MT 的「搜索结果数量过多，已停止搜索」判断依据） */
    val scanned: Int,
)

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

data class RenameConflict(
    val from: VfsUri,
    val target: VfsUri,
    val keepOldName: String,
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
    val diff: DiffResult? = null,
    /** 左窗格宽度比例（可拖动分隔条） */
    val splitRatio: Float = 0.5f,
    /** 重命名冲突（MT：交换 / 删除 / 备份） */
    val renameConflict: RenameConflict? = null,
    /**
     * MT「选择当前目录」模式（`0x7f0c0025` 的 `090084`/`090435`/`090085`）：
     * 进入后底栏上方浮出全宽按钮「选择当前目录」，选中后把该路径交给 [pickDirFor] 对应的流程
     * （解压到文件夹… / 单窗格复制到…）。
     */
    val pickDirFor: PickDirPurpose? = null,
) {
    fun pane(side: PaneSide): PaneState = if (side == PaneSide.LEFT) left else right
    val focusedPane: PaneState get() = pane(focused)
    val otherPane: PaneState get() = pane(focused.other)
}

/** 「选择当前目录」模式被哪个流程唤起（决定选中后做什么） */
enum class PickDirPurpose(val label: String) {
    EXTRACT("解压到所选目录"),
    COPY_TO("复制到所选目录"),
    MOVE_TO("移动到所选目录"),
}

/**
 * MT 动作菜单的跨窗格标签：**箭头始终指向另一窗格**（源在左窗格 → `复制 ->`；源在右窗格 → `<- 复制`）。
 * 对应 MT 截图：两个箭头在左侧选中时向右，在右侧选中时向左。
 */
fun crossPaneLabel(base: String, from: PaneSide): String =
    if (from == PaneSide.LEFT) "$base ->" else "<- $base"
