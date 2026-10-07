package com.u707t.panelfm.ui.browser

// ================================================================================================
// BrowserController 拆分（extension）：选择 / 多选 / 搜索与过滤 / 书签 / 排序
// 组织方式：extension 函数（同 package 免 import）；共享状态在 BrowserController.kt（见其头部说明）。
// ================================================================================================

import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.MtSelection
import com.u707t.panelfm.core.model.ConflictDecision
import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.model.SortBy
import com.u707t.panelfm.core.model.SortSpec
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.transfer.FileOperationPlanner
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.transfer.TransferRequest
import com.u707t.panelfm.core.transfer.TransferTask
import com.u707t.panelfm.core.transfer.TransferTaskSnapshot
import com.u707t.panelfm.core.transfer.forBrowserBar
import com.u707t.panelfm.core.transfer.isActive
import com.u707t.panelfm.core.transfer.isFinished
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.ListOptions
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsUris
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

// ------------------------------------------------------------------ 选择 / 多选
//
// 语义全部收敛在 core.common.MtSelection（纯逻辑 + 单测），这里只负责「把当前列表的 key
// 喂进去 / 把结果写回状态」。四类入口：
//   1. 滑动  swipeSelect（MT 0x7f1106f3「左右滑动文件可直接选择」/ 0x7f11062f「滑动选择两个文件连选」）
//   2. 长按  **不动选择**（只弹该项二级菜单，见 PaneView.handleRowLongPress）
//   3. 点击  tapSelect / toggleSelection（MT 0x7f110630；点击会清掉滑动锚点）
//   4. 底栏  全选 / 反选 / 类选（MT 0x7f11062b/632/633）

/** 当前列表的全部 key（键的闭区间运算都基于它） */
private fun BrowserController.keysOf(pane: PaneState): List<String> = pane.items.map { it.uri.toString() }

/** 多选态单击 = 切换单项；同时清掉滑动锚点（点击是「加/减选」，不该让它变成之后的连选端点） */
fun BrowserController.toggleSelection(side: PaneSide, uri: VfsUri) {
    updatePane(side) { pane ->
        pane.copy(
            selection = MtSelection.toggle(pane.selection, uri.toString()),
            selectionAnchor = null,
        )
    }
}

fun BrowserController.selectAll(side: PaneSide) = updatePane(side) { pane ->
    pane.copy(selection = MtSelection.all(keysOf(pane)), selectionAnchor = null)
}

/** MT 的「反选」 */
fun BrowserController.invertSelection(side: PaneSide) = updatePane(side) { pane ->
    pane.copy(selection = MtSelection.invert(pane.selection, keysOf(pane)), selectionAnchor = null)
}

/** MT 的「类选」：与当前选中项同类型（同扩展名分类）的全部选中 */
fun BrowserController.selectSameType(side: PaneSide) = updatePane(side) { pane ->
    val sample = pane.selectedItems.firstOrNull() ?: return@updatePane pane
    val kind = if (sample.isDirectory) "dir" else com.u707t.panelfm.core.common.MimeTypes.kindOf(sample.extension).name
    val same = pane.items.filter {
        if (kind == "dir") it.isDirectory
        else !it.isDirectory && com.u707t.panelfm.core.common.MimeTypes.kindOf(it.extension).name == kind
    }.map { it.uri.toString() }.toSet()
    pane.copy(selection = pane.selection + same, selectionAnchor = null)
}

// ---- 滑动选中（MT `0x7f1106f3` 左右滑动文件可直接选择 / `0x7f11062f` 滑动选择两个文件连选）

/**
 * 左右滑动一项 = 选中它；**再滑动另一项** = 两项之间的闭区间全部追加进选择。
 *
 * 语义在 [MtSelection.swipe]（纯逻辑 + 单测）：
 *  - 没有滑动锚点（或锚点就是这一项）→ 只选它，锚点 = 它；
 *  - 有锚点且是另一项 → 闭区间追加，锚点挪到这一项（第三次滑动可继续延伸）。
 *
 * 滑动是**离散动作**（用户实机确认的 MT 行为）：没有「按住一路刷」的跟手扫选。
 */
fun BrowserController.swipeSelect(side: PaneSide, item: FileMetadata) {
    focus(side)
    updatePane(side) { pane ->
        if (pane.items.none { it.uri == item.uri }) return@updatePane pane
        val key = item.uri.toString()
        val (selection, anchor) = MtSelection.swipe(pane.selection, keysOf(pane), key, pane.selectionAnchor)
        if (selection == pane.selection && anchor == pane.selectionAnchor) pane
        else pane.copy(selection = selection, selectionAnchor = anchor)
    }
}

fun BrowserController.clearSelection(side: PaneSide) {
    updatePane(side) { it.withSelectionCleared() }
}

fun BrowserController.setSearch(side: PaneSide, query: String) {
    updatePane(side) { it.copy(search = query) }
    load(side)
}

/**
 * MT 的过滤语法：
 *  - 普通文本：包含匹配
 *  - 以 `!` 开头：否定匹配（不含该文本）
 *  - 以 `/` 开头：正则匹配
 *  - 以 `!/` 开头：正则否定匹配
 */
fun BrowserController.matchesSearch(name: String, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return when {
        q.startsWith("!/") -> runCatching { !Regex(q.removePrefix("!/")).containsMatchIn(name) }.getOrDefault(true)
        q.startsWith("/") -> runCatching { Regex(q.removePrefix("/")).containsMatchIn(name) }.getOrDefault(true)
        q.startsWith("!") -> !name.contains(q.removePrefix("!"), ignoreCase = true)
        else -> name.contains(q, ignoreCase = true)
    }
}

/** MT 的「过滤」：按类型筛选当前目录（客户端过滤，立即生效） */
fun BrowserController.setFilter(side: PaneSide, kind: String?) {
    updatePane(side) { it.copy(filterKind = kind) }
}

// ------------------------------------------------------------------ 书签 / 首页

fun BrowserController.bookmarks(): List<com.u707t.panelfm.core.data.Bookmark> =
    runCatching { container.bookmarkDao.all() }.getOrDefault(emptyList())

fun BrowserController.addBookmark(side: PaneSide) {
    val pane = pane(side)
    val name = pane.uri.name.ifEmpty { pane.tab.label }
    runCatching {
        container.bookmarkDao.add(
            com.u707t.panelfm.core.data.Bookmark(
                id = 0,
                connectionId = pane.tab.connectionId,
                uri = pane.uri,
                name = name,
                createdAt = System.currentTimeMillis(),
            )
        )
    }
    showStatus("已添加书签：${pane.uri.displayPath}")
}

fun BrowserController.removeBookmark(id: Long) {
    runCatching { container.bookmarkDao.delete(id) }
    showStatus("已删除书签")
}

/** 书签拖动排序（MT 0x7f110140「长按后拖动排序」） */
fun BrowserController.reorderBookmarks(orderedIds: List<Long>) {
    runCatching { container.bookmarkDao.reorder(orderedIds) }
}

/** 打开书签：网络路径若未挂载则自动重连 */
fun BrowserController.openBookmark(bookmark: com.u707t.panelfm.core.data.Bookmark) {
    val side = _state.value.focused
    container.scope.launch {
        try {
            val uri = bookmark.uri
            if (uri.scheme != "local" && container.locator.find(uri) == null) {
                val config = container.connectionOf(com.u707t.panelfm.core.vfs.VfsUris.connectionId(uri))
                    ?: container.connectionByAuthority(uri.scheme, uri.authority)
                    ?: throw VfsException.Unsupported("找不到该连接：${uri.authority}")
                container.openConnection(config)
                open(side, uri, config.id, config.name)
            } else {
                open(side, uri, bookmark.connectionId, bookmark.name)
            }
        } catch (e: Exception) {
            showStatus((e as? VfsException)?.userMessage ?: "打开书签失败：${e.message}")
        }
    }
}

/** MT 的「设为首页」 */
fun BrowserController.setAsHome(side: PaneSide) {
    val uri = pane(side).uri.toString()
    container.scope.launch { container.prefs.setHomePath(uri) }
    showStatus("已设为首页：${pane(side).uri.displayPath}")
}

/**
 * MT「已设置为该网络存储的初始路径」（0x7f1104ab）：把**当前窗格路径**写回该连接的「初始路径」，
 * 下次从侧栏/主页打开该存储时直达这里。
 *
 * 初始路径的语义与 [ConnectionConfig.openPath] 相反（见 Connection.kt）：
 *  - WebDAV：basePath 是服务挂载点，初始路径是**虚拟根下**的相对子路径；
 *  - 其余协议：初始路径相对 basePath 追加。
 */
fun BrowserController.setAsConnectionInitialPath(side: PaneSide) {
    val pane = pane(side)
    val uri = pane.uri
    if (uri.scheme == "local" || uri.scheme == "archive") {
        showStatus("当前窗口不是网络存储，无法设置初始路径")
        return
    }
    val config = container.connectionOf(pane.tab.connectionId)
        ?: container.connectionOf(com.u707t.panelfm.core.vfs.VfsUris.connectionId(uri))
        ?: container.connectionByAuthority(uri.scheme, uri.authority)
    if (config == null) {
        showStatus("找不到该网络存储的连接配置（可先在侧栏重新打开一次）")
        return
    }
    // 初始路径是「相对连接根」的，路径必须落在连接根之内；上溯到根之外无法用相对路径表达（MT 同限制）
    val initial = initialPathFor(config, uri.path)
    if (initial == null) {
        showStatus("当前路径不在该连接的根目录下，无法设置为初始路径")
        return
    }
    val options = config.options.toMutableMap().apply {
        if (initial.isEmpty()) remove(ConnectionConfig.OPT_INITIAL_PATH)
        else put(ConnectionConfig.OPT_INITIAL_PATH, initial)
    }
    container.scope.launch {
        runCatching {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.connectionDao.update(config.copy(options = options))
            }
            container.reloadConnections()
        }.onFailure {
            showStatus("写入初始路径失败：${it.message}")
            return@launch
        }
        showStatus("已设置为该网络存储的初始路径")
    }
}

fun BrowserController.setSort(side: PaneSide, sort: SortSpec) {
    updatePane(side) { it.copy(sort = sort) }
    load(side)
}

/** 当前目录实际生效的排序：目录专属规则优先，否则用本窗格排序（不回写 pane.sort，见 [applySort]）。 */
fun BrowserController.sortSpecFor(side: PaneSide): SortSpec {
    val pane = pane(side)
    val rule = container.settings.value.folderSorts[VfsUris.stripped(pane.uri).toString()]
    return rule?.let { decodeSortSpec(it) } ?: pane.sort
}

/**
 * MT 排序对话框「确定」：
 *  - folderOnly = 「仅应用于此文件夹」→ 只给当前路径记一条规则；
 *  - 否则写全局默认排序，并清掉该文件夹的专属规则。
 */
fun BrowserController.applySort(side: PaneSide, spec: SortSpec, folderOnly: Boolean) {
    val key = VfsUris.stripped(pane(side).uri).toString()
    // 「仅应用于此文件夹」只写规则、不回写 pane.sort（避免泄漏到其它目录）；
    // 其余情况写全局默认排序并清掉该目录的专属规则。
    if (!folderOnly) updatePane(side) { it.copy(sort = spec) }
    load(side)
    container.scope.launch {
        if (folderOnly) {
            container.prefs.setFolderSort(key, encodeSortSpec(spec))
        } else {
            container.prefs.setFolderSort(key, null)
            container.prefs.setSort(spec.by, spec.ascending)
            container.prefs.setDirsFirst(spec.dirsFirst)
        }
    }
}

/** 当前目录是否已有「仅应用于此文件夹」的排序规则 */
fun BrowserController.hasFolderSortRule(uri: VfsUri): Boolean =
    container.settings.value.folderSorts.containsKey(VfsUris.stripped(uri).toString())

/** 已记忆的全部文件夹排序（排序管理对话框用） */
fun BrowserController.folderSortRules(): Map<String, String> = container.settings.value.folderSorts

fun BrowserController.clearFolderSorts() = container.scope.launch { container.prefs.clearFolderSorts() }

fun BrowserController.toggleHidden(side: PaneSide) {
    updatePane(side) { it.copy(showHidden = !it.showHidden) }
    load(side)
}

/**
 * MT 0x7f110630「开启后点击列表中任意两个项，将会自动选择它们中间所有的项。」
 *
 * 多选态下点击（不是长按）第二项 = 区间选择（追加）；未开启该设置时退回普通的加/减选。
 * 返回 true 表示已按「点击连选」处理（调用方不需要再走 toggle 分支）。
 */
fun BrowserController.tapSelect(side: PaneSide, item: FileMetadata): Boolean {
    if (!container.settings.value.tapRangeSelect) return false
    val pane = pane(side)
    if (pane.items.none { it.uri == item.uri }) return false
    val key = item.uri.toString()
    val anchor = pane.tapAnchor
    if (anchor != null && anchor != key) {
        // 第二击：从锚点连到这一项，并把锚点挪过来（可继续延伸：点 3 项 = 连选这 3 项之间）
        updatePane(side) {
            it.copy(
                selection = MtSelection.unionRange(it.selection, keysOf(it), anchor, key),
                tapAnchor = key,
                // 点击连选是「点击」这一路：清掉滑动锚点，避免之后一次滑动把区间接到滑动那一路去
                selectionAnchor = null,
            )
        }
        return true
    }
    updatePane(side) {
        val next = MtSelection.toggle(it.selection, key)
        // 取消选中时不再留锚点：从一项「没被选中」的项开始连选没有意义
        it.copy(
            selection = next,
            tapAnchor = if (next.contains(key)) key else null,
            selectionAnchor = null,
        )
    }
    return true
}

/**
 * MT 的「搜索」：文件名匹配（沿用过滤语法：普通文本 / `!否定` / `/正则` / `!/正则`），
 * 可递归子目录；高级条件：按内容（文本类且 ≤2MB，读前 512K）与文件大小范围。
 *
 * **MT 的三条反馈文案（0x7f110430 / 0x7f110620 / 0x7f110686）**：
 *  - 到达 [confirmEvery] 条时回调 [onAskContinue]：返回 false = 停止（"搜索结果数量过多，已停止搜索"）
 *  - [isCancelled] 每轮检查一次 → 用户点「停止搜索」后立即中断
 *  - 搜索会实时把中间结果交给 [onPartial]（界面可边搜边显示，与 MT 的「搜索结果(%d)」一致）
 */
suspend fun BrowserController.searchTree(
    side: PaneSide,
    nameQuery: String,
    recursive: Boolean,
    contentQuery: String = "",
    minSize: Long = -1L,
    maxSize: Long = -1L,
    nameRegex: Boolean = false,
    limit: Int = 300,
    scanCap: Int = 8000,
    /** 达到该条数时询问是否继续（返回 false = 停止）；null = 不询问 */
    confirmEvery: Int? = null,
    onAskContinue: (suspend (Int) -> Boolean)? = null,
    isCancelled: () -> Boolean = { false },
    onPartial: ((List<FileMetadata>) -> Unit)? = null,
): SearchOutcome {
    val root = pane(side).uri
    val vfs = container.locator.find(root) ?: throw VfsException.Unsupported("会话不可用")
    val out = ArrayList<FileMetadata>()
    var scanned = 0
    var stopped = false
    var askedAt = 0

    // MT 搜索类型：文件名（包含 / 正则）
    val nameOk: (FileMetadata) -> Boolean = when {
        nameQuery.isBlank() -> { _ -> true }
        nameRegex -> { item -> runCatching { Regex(nameQuery).containsMatchIn(item.name) }.getOrDefault(false) }
        else -> { item -> matchesSearch(item.name, nameQuery) }
    }

    fun sizeOk(item: FileMetadata): Boolean =
        (minSize < 0 || item.size >= minSize) && (maxSize < 0 || item.size <= maxSize)

    suspend fun contentOk(item: FileMetadata): Boolean {
        if (contentQuery.isBlank()) return true
        if (item.isDirectory || item.size < 0 || item.size > 2 * 1024 * 1024) return false
        val kind = com.u707t.panelfm.core.common.MimeTypes.kindOf(item.extension)
        if (kind != com.u707t.panelfm.core.common.MimeTypes.Kind.TEXT &&
            kind != com.u707t.panelfm.core.common.MimeTypes.Kind.CODE &&
            kind != com.u707t.panelfm.core.common.MimeTypes.Kind.OTHER
        ) return false
        return runCatching {
            withContext(container.dispatchers.vfs) {
                val reader = vfs.openRead(item.uri, 0, 512 * 1024)
                try {
                    // 先整体读字节再按编码识别解码（旧实现逐块 String(buf) 会用平台默认字符集：
                    // UTF-8 中文在块边界被截断、GBK 文件整段乱码，中文内容搜索几乎不可用）
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0
                    var emptyReads = 0
                    while (total < 512 * 1024) {
                        val n = reader.read(buf, 0, buf.size)
                        if (n < 0) break
                        if (n == 0) {
                            if (++emptyReads >= 3) break
                            continue
                        }
                        emptyReads = 0
                        out.write(buf, 0, n)
                        total += n
                    }
                    val text = com.u707t.panelfm.core.common.TextEncodings.decode(out.toByteArray()).text
                    text.contains(contentQuery, ignoreCase = true)
                } finally {
                    runCatching { reader.close() }
                }
            }
        }.getOrDefault(false)
    }

    suspend fun walk(dir: VfsUri, depth: Int) {
        if (out.size >= limit || scanned >= scanCap || depth > 12 || stopped) return
        if (isCancelled()) { stopped = true; return }
        val items = runCatching { withContext(container.dispatchers.vfs) { vfs.list(dir) } }
            .getOrDefault(emptyList())
        scanned += items.size
        for (item in items) {
            if (out.size >= limit) return
            if (!item.isDirectory &&
                nameOk(item) &&
                sizeOk(item) &&
                contentOk(item)
            ) {
                out.add(item)
                // MT 0x7f110430「已搜索到 %s 个结果，你确定继续搜索？」：到阈值先问再继续
                if (confirmEvery != null && onAskContinue != null &&
                    out.size >= confirmEvery && out.size > askedAt
                ) {
                    askedAt = out.size
                    onPartial?.invoke(out.toList())
                    val go = runCatching { onAskContinue(out.size) }.getOrDefault(false)
                    if (!go) { stopped = true; return }
                }
                // 边搜边显示（MT 的「搜索结果(%d)」实时更新）
                if (out.size % 20 == 0) onPartial?.invoke(out.toList())
            }
        }
        if (recursive) {
            for (item in items) {
                if (out.size >= limit || scanned >= scanCap || stopped) return
                if (item.isDirectory && !item.isHidden) walk(item.uri, depth + 1)
            }
        }
    }
    walk(root, 0)
    onPartial?.invoke(out.toList())
    return SearchOutcome(
        items = out,
        stopped = stopped || scanned >= scanCap,
        scanned = scanned,
    )
}

// --------------------------------------------------------------------------- MT 排序规则编解码

/** "By|asc|dirs" ⇄ SortSpec（用于「仅应用于此文件夹」的记忆规则） */
internal fun encodeSortSpec(spec: com.u707t.panelfm.core.model.SortSpec): String =
"${spec.by.name}|${spec.ascending}|${spec.dirsFirst}"

internal fun decodeSortSpec(line: String): com.u707t.panelfm.core.model.SortSpec? {
val parts = line.split('|')
if (parts.size != 3) return null
val by = runCatching { com.u707t.panelfm.core.model.SortBy.valueOf(parts[0]) }.getOrNull() ?: return null
return com.u707t.panelfm.core.model.SortSpec(
    by = by,
    ascending = parts[1].toBooleanStrictOrNull() ?: true,
    dirsFirst = parts[2].toBooleanStrictOrNull() ?: true,
)
}

// --------------------------------------------------------------------------- 网络存储「初始路径」

/**
 * 把当前路径换算成连接的「初始路径」选项值（MT 0x7f1104ab 的写入逻辑）。
 *
 * 语义（与 [ConnectionConfig.openPath] 互为逆运算）：
 *  - WebDAV：basePath 是服务挂载点，初始路径相对**虚拟根**（去前导 `/`）；
 *  - 其余协议：初始路径相对 basePath（根目录 → 空串 = 清除选项）。
 *
 * @return 相对初始路径（可为空串 = 连接根）；**null = 当前路径不在连接根内**（无法表达）。
 */
internal fun initialPathFor(config: ConnectionConfig, currentPath: String): String? {
val p = currentPath.trimEnd('/')
return if (config.type == ConnectionType.WEBDAV) {
    p.trim('/')
} else {
    val base = config.basePath.ifBlank { "/" }.trimEnd('/')
    when {
        base.isEmpty() -> p.trim('/')
        p == base -> ""
        p.startsWith("$base/") -> p.removePrefix(base).trim('/')
        else -> null
    }
}
}

