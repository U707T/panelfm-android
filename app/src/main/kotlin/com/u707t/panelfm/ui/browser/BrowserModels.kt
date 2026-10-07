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
    /**
     * 选中项（稳定 key = 列表项的 uri 字符串，见 [com.u707t.panelfm.core.common.MtSelection]）。
     *
     * 用 key 而不是下标：排序 / 刷新 / 增删都会让下标漂移，锚点一旦按下标记，
     * 刷新之后连接选都会选错区间。
     */
    val selection: Set<String> = emptySet(),
    /**
     * **滑动锚点**（MT `0x7f11062f`「滑动选择列表中任意两个文件，将会自动选择它们中间所有的文件」）：
     * 上一次「左右滑动选中」落在哪一项。再滑动另一项时，两项之间的闭区间会被追加进选择。
     *
     * 放在 [PaneState] 里而不是控制器字段：旧实现锚点都是控制器上的**全局字段**，
     * 左右窗格共用 —— 在左窗格滑动后去右窗格滑动，会直接连出一个跨窗格的区间。
     */
    val selectionAnchor: String? = null,
    /** 「点击连选」的锚点（MT `0x7f110630`，默认关）；与滑动锚点分开，避免两种手势互相干扰 */
    val tapAnchor: String? = null,
    val sort: SortSpec = SortSpec(),
    val showHidden: Boolean = false,
    /**
     * **当前 `items` 属于哪个目录**（`VfsUris.stripped(uri).toString()`；null = 还没加载过）。
     *
     * 为什么需要单独一个字段：切目录时 `uri` 立刻变、`items` 要等加载完才换，
     * 中间这段窗口里「uri 是新的、内容是旧的」。滚动位置记忆必须按**内容真正的归属**来记，
     * 否则会把旧目录的滚动位置写到新目录头上（表现为「进新目录后莫名停在中间」）。
     */
    val loadedUri: String? = null,
    /**
     * 需要滚动到可见的项（MT：搜索/跳转后定位到目标项；由 PaneView 消费后清空）。
     * 优先级高于「滚动位置记忆」——定位是显式意图，记忆是隐式恢复。
     */
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

    /**
     * 清除过滤条件（搜索关键字 + 类型过滤）。
     *
     * 单独抽出来是因为**只清关键字会把类型过滤留下**，用户仍然看到「已过滤」、
     * 列表也不恢复 —— v1.3.5 起「清除过滤 / 留空确定」都走这里。
     */
    fun clearedFilter(): PaneState = if (filtered) copy(search = "", filterKind = null) else this
    val selectedItems: List<FileMetadata> get() = items.filter { selection.contains(it.uri.toString()) }

    /**
     * 清空选择，并把**两个锚点**一起收掉。
     *
     * 换目录、切标签页、退出多选、动作执行完都走这里：旧实现只在少数几处写 `selection = emptySet()`，
     * 锚点留在原地 —— 于是「清空选择后长按另一项」还会按旧锚点连选出一个莫名其妙的区间。
     */
    fun withSelectionCleared(): PaneState =
        copy(selection = emptySet(), selectionAnchor = null, tapAnchor = null)

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

/**
 * 进行中的「长操作」（审计 U4）：压缩 / 完整性测试 / 校验值 / 目录对比 / 远程压缩包下载。
 *
 * 同一时间至多一个（[BrowserController.launchBusy] 闸门），页面底部渲染成一条
 * **不可消失**的状态条：标题 + 进度（可能不确定）+ 详情 + 取消。
 */
data class BusyOp(
    val id: Long,
    /** 动作描述，如「正在压缩 3 项 → xxx.zip」 */
    val title: String,
    /** 0..1；null = 进度未知（转圈） */
    val progress: Float? = null,
    /** 细节行，如「已处理 12.3 MB」/「第 3/120 项」 */
    val detail: String? = null,
    /** 点过取消后置 false（状态条转为「正在取消…」直到作业真正结束） */
    val cancellable: Boolean = true,
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
    /**
     * 浏览模式三档（MT「单列 / 双列 / 自动切换」）。
     * 旧字段 [singlePane] 保留为派生值，兼容既有调用点。
     */
    val browseMode: BrowseMode = BrowseMode.DUAL,
    /** 是否宽屏（由界面按屏宽写入，供 [BrowseMode.AUTO] 判定） */
    val wideEnough: Boolean = true,
    val diff: DiffResult? = null,
    /** 进行中的长操作（压缩/校验/对比/完整性测试/远程包下载）：页面底部状态条（审计 U4） */
    val busy: BusyOp? = null,
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

    /** 实际生效的浏览模式（AUTO 按屏宽展开） */
    val effectiveBrowseMode: BrowseMode
        get() = if (browseMode == BrowseMode.AUTO) BrowseMode.autoFor(wideEnough) else browseMode

    /** 派生值：当前是否单列显示（兼容旧调用点） */
    val singlePane: Boolean get() = effectiveBrowseMode == BrowseMode.SINGLE
}

/** 「选择当前目录」模式被哪个流程唤起（决定选中后做什么） */
enum class PickDirPurpose(val label: String) {
    EXTRACT("解压到所选目录"),
    COPY_TO("复制到所选目录"),
    MOVE_TO("移动到所选目录"),
}

/**
 * 浏览模式（复刻 MT 的「单列 / 双列 / 自动切换」，文案 `0x7f1101fb/1fc/1fd/1fe`）。
 *  - [AUTO]：窄屏单列、宽屏双列（由界面按屏宽判定，见 `DualPaneScreen`）
 *  - [SINGLE] / [DUAL]：强制单列 / 强制双列
 */
enum class BrowseMode(val label: String) {
    AUTO("自动切换"),
    SINGLE("单列"),
    DUAL("双列"),
    ;

    companion object {
        /** 自动切换：≥ 600dp 认为宽屏（MT 的平板/横屏阈值） */
        fun autoFor(wideEnough: Boolean): BrowseMode = if (wideEnough) DUAL else SINGLE
    }
}

/**
 * MT 动作菜单的跨窗格标签：**箭头始终指向另一窗格**（源在左窗格 → `复制 ->`；源在右窗格 → `<- 复制`）。
 * 对应 MT 截图：两个箭头在左侧选中时向右，在右侧选中时向左。
 */
fun crossPaneLabel(base: String, from: PaneSide): String =
    if (from == PaneSide.LEFT) "$base ->" else "<- $base"

/**
 * 「长按动作菜单」的目标项（见 v1.9.1 的回归说明）。
 *
 * MT 语义：长按 = 弹**这一项**的二级菜单（不改选择）。于是：
 *  - 按下的项在当前选择集里 → 动作作用于**整个选择集**（与顶栏动作条同一套语义）；
 *  - 按下的项不在选择集里（或根本没有选择）→ 动作只作用于**这一项**。
 *
 * ⚠️ 必须显式把「目标项」传给下游，不能让它们退化成 `BrowserController.targetSources()`：
 * 后者在**没有选择**时会返回**整个目录**的内容 —— v1.9.0 把「长按不再设选择」改掉之后，
 * 「长按 A → 删除 / 复制 / 移动 / 压缩 / 加入剪贴板」一度变成对整个目录操作（v1.9.1 修复）。
 */
internal fun menuTargets(selection: List<FileMetadata>, pressed: FileMetadata): List<FileMetadata> =
    if (selection.any { it.uri == pressed.uri }) selection else listOf(pressed)

/**
 * 「显式目标优先」的取源（F7/F8 的口径固化）。
 *
 * 长按菜单等入口传入的显式目标**必须端到端生效**：[override] 非 null（即使是空列表）一律以它为准，
 * 绝不回退；只有 null 才用 [fallback]（选择集 / 当前目录）。
 * v1.9.1 的教训：`overrideSources` 参数加了、函数体忘了用 → 静默对整个目录操作。
 */
internal fun explicitTargets(override: List<VfsUri>?, fallback: () -> List<VfsUri>): List<VfsUri> =
    override ?: fallback()

/** 压缩入口的目标解析结果（见 [planCompressTargets]）。 */
internal data class CompressPlan(val sources: List<VfsUri>, val name: String)

/**
 * 压缩入口的取源与命名（纯函数 → 可直接回归 F7）：
 *  - 显式目标优先（同 [explicitTargets]）——「长按这一项 → 压缩」必须只压这一项；
 *  - 命名：用户填的名字优先；否则按源自动命名（单文件 = 主名、多项 = archive），并补格式后缀。
 * 返回 null = 没有任何可压缩的项（调用方给出提示，而不是静默改压整个目录）。
 */
internal fun planCompressTargets(
    overrideSources: List<VfsUri>?,
    fallbackSources: List<VfsUri>,
    fileName: String?,
    formatExt: String,
): CompressPlan? {
    val sources = overrideSources ?: fallbackSources
    if (sources.isEmpty()) return null
    val base = fileName?.trim()?.takeIf { it.isNotEmpty() }
        ?: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.zipNameFor(sources).removeSuffix(".zip")
    val name = if (base.endsWith(".$formatExt")) base else "$base.$formatExt"
    return CompressPlan(sources, name)
}

/**
 * 「解压到文件夹…」输入框的路径解析（F10）。
 *
 *  - 留空 / 与当前目录相同 → null（保持原「选择当前目录」模式：默认值不改行为）；
 *  - 否则返回规范化的绝对路径（不以 / 开头自动补 /，末尾多余 / 去掉）。
 *
 * 解析结果由调用方转换 VfsUri（相对锚点 = 当前存储 / 压缩包宿主存储）。
 */
internal fun typedExtractPath(customPath: String?, currentPath: String): String? {
    val raw = customPath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val norm = "/" + raw.trim('/')
    val current = currentPath.trimEnd('/').ifEmpty { "/" }
    return if (norm == current) null else norm
}

/** 状态提示里像"出错"的措辞：这类消息显示更久（审计 U9）。 */
private val STATUS_ERROR_HINTS = listOf("失败", "错误", "异常", "无法", "⚠️")

/** 一条状态提示该显示多久（纯函数，便于单测）：错误类 6s、普通 2.6s。 */
internal fun statusDurationMs(message: String): Long =
    if (STATUS_ERROR_HINTS.any { message.contains(it) }) 6_000L else 2_600L
