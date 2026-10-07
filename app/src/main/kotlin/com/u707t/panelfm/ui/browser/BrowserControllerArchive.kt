package com.u707t.panelfm.ui.browser

// ================================================================================================
// BrowserController 拆分（extension）：压缩包（挂载/压缩/解压/完整性/包内编辑）
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

/** 把压缩包挂载成只读 VFS 并在当前窗格进入（MT 的「进入压缩包」体验）。远程包先下载：可见进度、可取消（审计 U4）。 */
fun BrowserController.openArchiveInPane(side: PaneSide, item: FileMetadata) {
    launchBusy("打开压缩包 ${item.name}") { report ->
        val archive = container.openArchive(item.uri) { done, total ->
            report.report(done, total, "下载中 " + Fmt.transferred(done, total))
        }
        val inner = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.uriFor(item.uri, archive.kind)
        open(side, inner, connectionId = null, label = "${item.name} · ${archive.kind.label}")
    }
}

/** 压缩到对面窗格（zip） */
fun BrowserController.compressToOther(
    side: PaneSide = _state.value.focused,
    format: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format =
        com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.ZIP,
    fileName: String? = null,
    level: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level =
        com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level.NORMAL,
    password: String? = null,
    encryptNames: Boolean = false,
    /** 显式目标（长按菜单按「这一项」压缩时传入）；null = 当前选择集 / 当前目录 */
    overrideSources: List<VfsUri>? = null,
) {
    val st = _state.value
    val dstPane = st.pane(side.other)
    // F7：显式目标必须端到端生效 —— 长按「这一项压缩」时不得退化成选择集 / 整个目录
    val plan = planCompressTargets(overrideSources, targetSources(side), fileName, format.ext)
    if (plan == null) {
        showStatus("当前目录没有可压缩的项")
        return
    }
    val sources = plan.sources
    val name = plan.name
    val dest = dstPane.uri.child(name)
    showStatus("压缩 ${sources.size} 项 → ${dest.displayPath}")
    update { it.copy(highlight = true) }
    container.scope.launch {
        kotlinx.coroutines.delay(1200)
        update { it.copy(highlight = false) }
    }
    launchBusy("压缩 ${sources.size} 项 → $name") { report ->
        compressInto(report, sources, dest, name, format, level, password, encryptNames)
        load(side.other)
    }
}

/** 压缩的公共实现（两个入口共用）：进度映射 + 取消清理（只清本次新建的半成品，绝不碰用户原文件）。 */
private suspend fun BrowserController.compressInto(
    report: BusyReporter,
    sources: List<VfsUri>,
    dest: VfsUri,
    name: String,
    format: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format,
    level: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level,
    password: String?,
    encryptNames: Boolean,
) {
    // 目标是否是「本次新建」：取消时据此决定是否清理半成品
    val existedBefore = runCatching { container.locator.find(dest)?.stat(dest) != null }.getOrDefault(false)
    try {
        com.u707t.panelfm.core.vfs.archive.ArchiveCompressor(container.locator)
            .compress(
                sources, dest, format,
                onProgress = { done, total ->
                    report.report(done, total, "已处理 " + Fmt.transferred(done, total))
                },
                level = level,
                password = password,
                encryptNames = encryptNames,
            )
    } catch (e: CancellationException) {
        // 取消后目标只剩半成品：仅当目标是「本次新建」时才清理
        if (!existedBefore) runCatching { container.locator.find(dest)?.delete(listOf(dest)) }
        throw e
    }
    showStatus("已压缩为 $name")
}

/**
 * MT「解压到单独的文件夹」：在 [parentDir] 下按压缩包名建一个同名目录再解压进去。
 * 名字冲突时自动加 (1)(2)…（MT 的行为：不会直接覆盖已有目录）。
 */
fun BrowserController.extractToOwnFolder(side: PaneSide, parentDir: VfsUri) {
    val pane = pane(side)
    val archiveName = pane.archiveHostName ?: run {
        showStatus("无法确定压缩包名称")
        return
    }
    val folderName = archiveName.substringBeforeLast('.', archiveName)
    container.scope.launch {
        val target = container.uniqueChild(parentDir, folderName)
        runCatching { container.locator.find(parentDir)?.mkdir(target) }
        extractTo(side, target)
    }
}

/** 解压：把当前（压缩包内）选中项复制到指定目录 */
fun BrowserController.extractTo(side: PaneSide, destDir: VfsUri) {
    val sources = targetSources(side)
    if (sources.isEmpty()) {
        showStatus("当前目录没有可解压的项")
        return
    }
    showStatus("解压 ${sources.size} 项 → ${destDir.displayPath}")
    container.engine.enqueue(
        TransferRequest(sources = sources, destDir = destDir, op = TransferOp.COPY, conflict = ConflictPolicy.ASK)
    )
    clearSelection(side)
}

/**
 * 解压**文件列表里的压缩包文件**（MT 长按菜单的「解压到当前目录 / 解压到单独的文件夹」）。
 *
 * 与 [extractTo] 的区别：那个是在**压缩包内部**选中若干条目再解压；
 * 这里是在文件列表里直接对 `.zip` 文件本身解压 —— 整包展开到目标目录。
 *
 * @param ownFolder true = 先在 [destDir] 下建一个与压缩包同名的目录再解压进去
 *                  （MT `0x7f110252`「解压到单独的文件夹」，重名自动加 (1)）
 */
fun BrowserController.extractArchiveTo(item: FileMetadata, destDir: VfsUri, ownFolder: Boolean = false) {
    val archiveName = item.name.substringBeforeLast('.', item.name)
    val kind = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.ArchiveKind.ofFileName(item.name)
    if (kind == null) {
        showStatus("不支持的压缩格式：${item.name}")
        return
    }
    // 整段包在 launchBusy 里（有兜底 catch）——这条链路以前有「未挂载 + 未捕获异常 → 直接闪退」
    // 的问题；远程包下载期间状态条可见、可取消（审计 U4）。
    launchBusy("打开压缩包 ${item.name}") { report ->
        try {
            val target = if (ownFolder) {
                val dir = container.uniqueChild(destDir, archiveName)
                try {
                    container.locator.find(destDir)?.mkdir(dir)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showStatus("创建目录失败：${e.message}")
                    return@launchBusy
                }
                dir
            } else {
                destDir
            }
            // ⚠️ 必须先**挂载**压缩包：引擎按 URI 找会话（SessionLocator 对 archive:// 只查已挂载的
            // `archiveOf`），旧实现直接构造 archive:// 根 URI 就入队 → 计划阶段报
            // 「源位置不可用」/在某些路径上直接抛异常（用户报的「解压闪退/报错」）。
            val vfs = try {
                container.openArchive(item.uri) { done, total ->
                    report.report(done, total, "下载中 " + Fmt.transferred(done, total))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showStatus("打开压缩包失败：${e.message ?: "未知错误"}")
                return@launchBusy
            }
            if (vfs.kind != kind) {
                // 挂载出来的类型与后缀推断不一致（少见：改名 / 伪装），以实际挂载结果为准继续
                Logx.w("Browser", "extract: kind mismatch ${item.name} ${vfs.kind} != $kind")
            }
            // 压缩包挂载的**根 URI**（整包内容都在它下面）
            val root = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.uriFor(item.uri, vfs.kind)
            showStatus("解压 ${item.name} → ${target.displayPath}")
            container.engine.enqueue(
                TransferRequest(
                    sources = listOf(root),
                    destDir = target,
                    op = TransferOp.COPY,
                    conflict = ConflictPolicy.ASK,
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logx.e("Browser", "extract failed ${item.name}: ${e.message}", e)
            showStatus("解压失败：${e.message ?: e::class.simpleName}")
        }
    }
}

/** 「解压到文件夹…」：进入选择目录模式，确认后解压该压缩包（供 [extractArchiveTo] 用） */
fun BrowserController.startPickArchiveExtract(item: FileMetadata) {
    pendingArchiveExtract = item
    startPickDir(PickDirPurpose.EXTRACT)
}

/** 清除待解压的压缩包（选择目录流程结束后调用，避免串到下一次解压） */
fun BrowserController.clearPendingArchiveExtract() {
    pendingArchiveExtract = null
}

/** 压缩包完整性测试（ZIP：逐条读取校验 CRC）—— 状态条显示进度、可取消（审计 U4） */
fun BrowserController.testArchive(side: PaneSide) {
    val pane = pane(side)
    launchBusy("测试压缩包完整性") { report ->
        val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(pane.uri.path)
        val host = encoded?.let { runCatching { VfsUri.parse(VfsUri.decodeHost(it)) }.getOrNull() }
        if (host == null) {
            showStatus("当前不在压缩包内")
            return@launchBusy
        }
        report.note("正在列出压缩包内容…")
        val vfs = container.openArchive(host)
        val all = withContext(container.dispatchers.vfs) {
            listRecursive(vfs, com.u707t.panelfm.core.vfs.archive.ArchiveVfs.uriFor(host, vfs.kind))
        }
        val files = all.filter { !it.isDirectory }
        val totalBytes = files.sumOf { it.size.coerceAtLeast(0) }
        var doneBytes = 0L
        var ok = 0
        var bad = 0
        files.forEachIndexed { index, item ->
            val label = "第 ${index + 1}/${files.size} 项 · " + Fmt.transferred(doneBytes, totalBytes)
            report.report(doneBytes, totalBytes, label)
            try {
                val reader = vfs.openRead(item.uri)
                val buf = ByteArray(64 * 1024)
                try {
                    while (true) {
                        val n = reader.read(buf, 0, buf.size)
                        if (n < 0) break
                        doneBytes += n.coerceAtLeast(0)
                        // 逐块上报：单条大文件中途也能取消
                        report.report(doneBytes, totalBytes, label)
                    }
                } finally {
                    runCatching { reader.close() }
                }
                ok++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bad++
            }
        }
        showStatus(if (bad == 0) "压缩包完整性检查通过（$ok 个文件）" else "压缩包有 $bad 个文件损坏（共 $ok 正常）")
    }
}

private suspend fun BrowserController.listRecursive(
    vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
    dir: VfsUri,
    depth: Int = 0,
): List<FileMetadata> {
    if (depth > 16) return emptyList()
    val out = ArrayList<FileMetadata>()
    withContext(container.dispatchers.vfs) { vfs.list(dir) }.forEach { item ->
        out.add(item)
        if (item.isDirectory) out.addAll(listRecursive(vfs, item.uri, depth + 1))
    }
    return out
}

// ------------------------------------------------------------------ 压缩包内写操作（MT：添加/删除/重命名）

private suspend fun BrowserController.archiveEditor(side: PaneSide): com.u707t.panelfm.core.vfs.archive.ZipEditor? {
    val pane = pane(side)
    if (pane.uri.scheme != "archive") return null
    val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(pane.uri.path) ?: return null
    val host = runCatching { VfsUri.parse(VfsUri.decodeHost(encoded)) }.getOrNull() ?: return null
    val vfs = container.openArchive(host)
    if (vfs.kind != com.u707t.panelfm.core.vfs.archive.ArchiveVfs.ArchiveKind.ZIP) {
        showStatus("只有 ZIP 支持内部修改（7z/tar 为只读）")
        return null
    }
    return com.u707t.panelfm.core.vfs.archive.ZipEditor(vfs, container.locator)
}

private suspend fun BrowserController.refreshArchive(side: PaneSide) {
    val pane = pane(side)
    val encoded = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseEncodedHost(pane.uri.path)
    if (encoded != null) {
        val host = runCatching { VfsUri.parse(VfsUri.decodeHost(encoded)) }.getOrNull()
        if (host != null) {
            // 先丢弃旧挂载（旧索引），再**重新挂载**——否则 load() 里 locator.find(archive://…)
            // 找不到会话，面板会变成「未连接」（旧实现漏了重挂载这一步）。
            container.forgetArchive(host.toString())
            runCatching { container.openArchive(host) }
                .onFailure { showStatus("重新打开压缩包失败：${it.message}") }
        }
    }
    load(side)
}

/** 删除压缩包内条目（整包重写） */
fun BrowserController.deleteInsideArchive(side: PaneSide, items: List<FileMetadata>) {
    container.scope.launch {
        try {
            val editor = archiveEditor(side) ?: return@launch
            showStatus("正在重写压缩包（删除 ${items.size} 项）…")
            val remove = items.map { entryPathOf(side, it) }.toSet()
            editor.rewrite(remove = remove)
            showStatus("已从压缩包删除 ${items.size} 项")
            clearSelection(side)
            refreshArchive(side)
        } catch (e: Exception) {
            showStatus((e as? VfsException)?.userMessage ?: "修改压缩包失败：${e.message}")
        }
    }
}

/** 重命名压缩包内条目（完整路径，可改父目录 = 移动） */
fun BrowserController.renameInsideArchive(side: PaneSide, item: FileMetadata, newFullPath: String) {
    container.scope.launch {
        try {
            val editor = archiveEditor(side) ?: return@launch
            val from = entryPathOf(side, item)
            showStatus("正在重写压缩包…")
            editor.rewrite(rename = mapOf(from to newFullPath.trimStart('/')))
            showStatus("已更新压缩包")
            refreshArchive(side)
        } catch (e: Exception) {
            showStatus((e as? VfsException)?.userMessage ?: "重命名失败：${e.message}")
        }
    }
}

/** 把对面窗格选中的项添加到当前压缩包（MT：和复制文件一样） */
fun BrowserController.addToArchive(side: PaneSide) {
    val st = _state.value
    val other = st.pane(side.other)
    // F14：旧实现在对面**没有选中项**时静默改成「对面整个目录」，与菜单文案「添加对面选中项」不符。
    // 现在语义与文案一致：没有选中项就提示先选（要整目录请先「全选」）。
    val sources = other.selectedItems.map { it.uri }
    if (sources.isEmpty()) {
        showStatus(
            if (other.items.isEmpty()) "对面窗格为空"
            else "请先在对面窗格选中要添加的项（把整个目录加进压缩包请先「全选」）"
        )
        return
    }
    container.scope.launch {
        try {
            val editor = archiveEditor(side) ?: return@launch
            val inner = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseInner(pane(side).uri.path)
            showStatus("正在添加 ${sources.size} 项到压缩包…")
            val additions = sources.map { uri ->
                val name = (if (inner.isEmpty()) "" else "$inner/") + uri.name
                name to uri
            }
            editor.rewrite(additions = additions)
            showStatus("已添加 ${sources.size} 项到压缩包")
            refreshArchive(side)
        } catch (e: Exception) {
            showStatus((e as? VfsException)?.userMessage ?: "添加失败：${e.message}")
        }
    }
}

private fun BrowserController.entryPathOf(side: PaneSide, item: FileMetadata): String {
    val base = com.u707t.panelfm.core.vfs.archive.ArchiveVfs.parseInner(pane(side).uri.path)
    val rel = item.name
    return (if (base.isEmpty()) rel else "$base/$rel").trimStart('/')
}

/** 压缩到当前目录（MT 的「压缩」）：支持 zip / 7z / tar / tar.gz / tar.bz2 */
fun BrowserController.compressHere(
    side: PaneSide,
    format: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format =
        com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Format.ZIP,
    fileName: String? = null,
    level: com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level =
        com.u707t.panelfm.core.vfs.archive.ArchiveCompressor.Level.NORMAL,
    password: String? = null,
    encryptNames: Boolean = false,
    /** 显式目标（长按菜单按「这一项」压缩时传入）；null = 当前选择集 / 当前目录 */
    overrideSources: List<VfsUri>? = null,
) {
    val st = _state.value
    val pane = st.pane(side)
    // F7：显式目标必须端到端生效（长按「这一项」压缩时不能退化成选择集/整个目录）
    val plan = planCompressTargets(overrideSources, targetSources(side), fileName, format.ext)
    if (plan == null) {
        showStatus("当前目录没有可压缩的项")
        return
    }
    val sources = plan.sources
    val name = plan.name
    val dest = pane.uri.child(name)
    launchBusy("压缩 ${sources.size} 项 → $name") { report ->
        compressInto(report, sources, dest, name, format, level, password, encryptNames)
        clearSelection(side)
        load(side)
    }
}


