package com.u707t.panelfm.ui.browser

// ================================================================================================
// BrowserController 拆分（extension）：文件操作（删除/重命名/交换/新建/权限/校验值）
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

// ------------------------------------------------------------------ 单窗格内操作

internal fun BrowserController.targetSources(side: PaneSide): List<VfsUri> {
    val pane = pane(side)
    return if (pane.hasSelection) pane.selectedItems.map { it.uri } else pane.items.map { it.uri }
}

/** 统计本地目录内的条目数（用于「极速删除」提示，最多数到 cap 就返回） */
suspend fun BrowserController.countLocalEntries(uri: VfsUri, cap: Int = 1200): Int {
    if (uri.scheme != "local") return 0
    var count = 0
    suspend fun walk(dir: java.io.File) {
        if (count >= cap) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            count++
            if (count >= cap) return
            if (child.isDirectory) walk(child)
        }
    }
    return withContext(container.dispatchers.io) {
        walk(java.io.File(container.localVfs.absolutePath(uri)))
        count
    }
}

/** 交换两个选中项的文件名（MT 的「交换文件名」，本地与网络都支持） */
fun BrowserController.swapSelectedNames(side: PaneSide) {
    val picked = pane(side).selectedItems
    if (picked.size != 2) {
        showStatus("请正好选中 2 个文件再交换文件名")
        return
    }
    val a = picked[0]
    val b = picked[1]
    if (!a.uri.sameMount(b.uri)) {
        showStatus("两个文件不在同一位置，无法交换文件名")
        return
    }
    container.scope.launch {
        try {
            val vfs = container.locator.find(a.uri) ?: throw VfsException.Unsupported("会话不可用")
            val tmpName = ".panelfm.swap.${System.currentTimeMillis()}"
            val tmp = a.uri.parent?.child(tmpName) ?: throw VfsException.ProtocolError("无法交换")
            val targetA = a.uri.parent?.child(b.name) ?: throw VfsException.ProtocolError("无法交换")
            val targetB = b.uri.parent?.child(a.name) ?: throw VfsException.ProtocolError("无法交换")
            withContext(container.dispatchers.vfs) {
                // 三步交换；中途失败则尽力回滚，避免「a 变成临时名、b 丢失」的坏状态
                vfs.rename(a.uri, tmp)
                try {
                    // b 改到 a 的名字（targetB），a 的临时名再改到 b 的名字（targetA）——顺序写反会变成空操作
                    vfs.rename(b.uri, targetB)
                    try {
                        vfs.rename(tmp, targetA)
                    } catch (e: Exception) {
                        runCatching { vfs.rename(targetA, b.uri) } // 回滚 b
                        runCatching { vfs.rename(tmp, a.uri) }     // 回滚 a
                        throw e
                    }
                } catch (e: Exception) {
                    runCatching { vfs.rename(tmp, a.uri) }         // 回滚 a
                    throw e
                }
            }
            showStatus("已交换「${a.name}」与「${b.name}」")
            clearSelection(side)
            load(side)
        } catch (e: Exception) {
            showStatus((e as? VfsException)?.userMessage ?: "交换失败：${e.message}")
        }
    }
}

fun BrowserController.deleteSelected(
    side: PaneSide,
    fastDelete: Boolean = false,
    /** 显式目标（长按菜单按「这一项」删除时传入）；null = 当前选择集 / 当前目录 */
    overrideSources: List<VfsUri>? = null,
) {
    val pane = pane(side)
    val sources = overrideSources ?: targetSources(side)
    if (sources.isEmpty()) {
        showStatus("没有可删除的项")
        return
    }
    container.scope.launch {
        try {
            // 本地文件优先进回收站（可还原）；网络位置或「极速删除」直接删除
            val localOnly = if (fastDelete) emptyList() else sources.filter { it.scheme == "local" }
            val remoteOnly = if (fastDelete) sources else sources.filter { it.scheme != "local" }
            var trashed = 0
            if (localOnly.isNotEmpty()) trashed = container.trash.moveToTrash(localOnly)
            if (remoteOnly.isNotEmpty()) {
                // 按 VFS 会话分组删除（选中项可能来自不同会话，旧实现只用第一个的会话 → 其余报错）
                val bySession = remoteOnly.groupBy { uri ->
                    container.locator.find(uri)?.let { System.identityHashCode(it) } ?: -1
                }
                for ((_, group) in bySession) {
                    val vfs = container.locator.find(group.first())
                        ?: throw VfsException.Unsupported("会话不可用：${group.first().authority}")
                    withContext(container.dispatchers.vfs) { vfs.delete(group) }
                }
            }
            showStatus(
                when {
                    fastDelete -> "已极速删除 ${sources.size} 项"
                    remoteOnly.isEmpty() -> "已移入回收站 $trashed 项（可还原）"
                    trashed == 0 -> "已删除 ${remoteOnly.size} 项"
                    else -> "已删除 ${sources.size} 项（其中 $trashed 项移入回收站）"
                }
            )
            clearSelection(side)
            load(side)
        } catch (e: Exception) {
            // 部分项可能已删除：失败后也要刷新，避免列表里留着已经不存在的项
            load(side)
            showStatus((e as? VfsException)?.userMessage ?: "删除失败：${e.message}")
        }
    }
}

fun BrowserController.rename(uri: VfsUri, newName: String, side: PaneSide) {
    if (!isValidChildName(newName)) {
        showStatus("名称不能包含 / 或 .. 等字符")
        return
    }
    container.scope.launch {
        try {
            val vfs = container.locator.find(uri) ?: throw VfsException.Unsupported("会话不可用")
            val target = uri.parent?.child(newName) ?: throw VfsException.ProtocolError("无法重命名根目录")
            // MT：目标已存在且都不是文件夹时，弹「交换 / 删除 / 备份」选择
            val exists = runCatching { withContext(container.dispatchers.vfs) { vfs.stat(target) } }.getOrNull()
            if (exists != null && !exists.isDirectory) {
                update { it.copy(renameConflict = RenameConflict(uri, target, uri.name)) }
                return@launch
            }
            if (exists != null) {
                // 目标是同名文件夹：rename 语义不允许覆盖，给出明确提示
                // （旧实现直接尝试 rename → 报「服务器拒绝重命名（可能需要服务端复制）」，误导排查方向）
                showStatus("目标已存在同名文件夹：$newName")
                return@launch
            }
            val ok = withContext(container.dispatchers.vfs) { vfs.rename(uri, target) }
            if (!ok) throw VfsException.ProtocolError("服务器拒绝重命名（可能需要服务端复制）")
            showStatus("已重命名为 $newName")
            load(side)
        } catch (e: Exception) {
            showStatus((e as? VfsException)?.userMessage ?: "重命名失败：${e.message}")
        }
    }
}

/** 处理重命名冲突（MT 的三种处理） */
fun BrowserController.resolveRenameConflict(action: String) {
    val conflict = _state.value.renameConflict ?: return
    update { it.copy(renameConflict = null) }
    container.scope.launch {
        try {
            val vfs = container.locator.find(conflict.from) ?: throw VfsException.Unsupported("会话不可用")
            val side = _state.value.focused
            when (action) {
                "swap" -> {
                    // 交换文件名：目标先改到临时名，源改到目标名，临时名再改成源名（失败则尽力回滚）
                    val tmp = conflict.target.parent?.child(".panelfm.swap.${System.currentTimeMillis()}")
                    if (tmp == null) throw VfsException.ProtocolError("无法交换")
                    withContext(container.dispatchers.vfs) {
                        vfs.rename(conflict.target, tmp)
                        try {
                            vfs.rename(conflict.from, conflict.target)
                            try {
                                vfs.rename(tmp, conflict.from)
                            } catch (e: Exception) {
                                runCatching { vfs.rename(conflict.target, conflict.from) }
                                runCatching { vfs.rename(tmp, conflict.target) }
                                throw e
                            }
                        } catch (e: Exception) {
                            runCatching { vfs.rename(tmp, conflict.target) }
                            throw e
                        }
                    }
                    showStatus("已交换文件名")
                }
                "delete" -> {
                    withContext(container.dispatchers.vfs) {
                        vfs.delete(listOf(conflict.target))
                        vfs.rename(conflict.from, conflict.target)
                    }
                    showStatus("已删除同名文件并完成重命名")
                }
                "backup" -> {
                    withContext(container.dispatchers.vfs) {
                        vfs.rename(conflict.target, conflict.target.parent?.child(conflict.target.name + ".bak") ?: conflict.target)
                        vfs.rename(conflict.from, conflict.target)
                    }
                    showStatus("原文件已备份为 .bak")
                }
            }
            load(side)
        } catch (e: Exception) {
            showStatus((e as? VfsException)?.userMessage ?: "重命名失败：${e.message}")
        }
    }
}

fun BrowserController.dismissRenameConflict() = update { it.copy(renameConflict = null) }

fun BrowserController.createFolder(side: PaneSide, name: String) {
    val dir = pane(side).uri
    if (!isValidChildName(name)) {
        showStatus("名称不能包含 / 或 .. 等字符")
        return
    }
    container.scope.launch {
        try {
            val vfs = container.locator.find(dir) ?: throw VfsException.Unsupported("会话不可用")
            withContext(container.dispatchers.vfs) { vfs.mkdir(dir.child(name), parents = true) }
            showStatus("已创建文件夹 $name")
            load(side)
        } catch (e: Exception) {
            showStatus((e as? VfsException)?.userMessage ?: "创建失败：${e.message}")
        }
    }
}

fun BrowserController.createFile(side: PaneSide, name: String) {
    val dir = pane(side).uri
    if (!isValidChildName(name)) {
        showStatus("名称不能包含 / 或 .. 等字符")
        return
    }
    container.scope.launch {
        try {
            val vfs = container.locator.find(dir) ?: throw VfsException.Unsupported("会话不可用")
            withContext(container.dispatchers.vfs) { vfs.touch(dir.child(name)) }
            showStatus("已创建文件 $name")
            load(side)
        } catch (e: Exception) {
            showStatus((e as? VfsException)?.userMessage ?: "创建失败：${e.message}")
        }
    }
}

/** 新建 / 重命名用的名称校验：拒绝路径分隔符与穿越，避免写到目录之外 */
private fun BrowserController.isValidChildName(name: String): Boolean {
    val n = name.trim()
    if (n.isEmpty() || n == "." || n == "..") return false
    return !n.contains('/') && !n.contains('\\') && !n.contains('\u0000')
}

/**
 * 修改权限（MT 0x7f0c0096）：可选递归应用到子文件 / 子文件夹。
 * 递归时用 BFS 遍历（有深度与数量上限，避免超大目录卡死）。
 */
fun BrowserController.changePermissions(
    uri: VfsUri,
    mode: Int,
    recurseFiles: Boolean,
    recurseDirs: Boolean,
) {
    container.scope.launch {
        val vfs = container.locator.find(uri)
        if (vfs == null) {
            showStatus("会话不可用")
            return@launch
        }
        if (!vfs.capabilities.permissions) {
            showStatus("该位置不支持修改权限")
            return@launch
        }
        var ok = 0
        var failed = 0
        runCatching { vfs.setPermissions(uri, mode) }
            .onSuccess { ok++ }
            .onFailure { failed++ }

        if (recurseFiles || recurseDirs) {
            showStatus("正在递归修改权限…")
            val queue = ArrayDeque<Pair<VfsUri, Int>>()
            queue += uri to 0
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_CHMOD_ITEMS) {
                val (dir, depth) = queue.removeFirst()
                if (depth > MAX_CHMOD_DEPTH) continue
                val children = runCatching { vfs.list(dir) }.getOrNull() ?: continue
                for (child in children) {
                    visited++
                    if (visited > MAX_CHMOD_ITEMS) break
                    if (child.isDirectory) {
                        if (recurseDirs) {
                            runCatching { vfs.setPermissions(child.uri, mode) }
                                .onSuccess { ok++ }
                                .onFailure { failed++ }
                        }
                        queue += child.uri to (depth + 1)
                    } else if (recurseFiles) {
                        runCatching { vfs.setPermissions(child.uri, mode) }
                            .onSuccess { ok++ }
                            .onFailure { failed++ }
                    }
                }
            }
        }
        val octal = Integer.toOctalString(mode and 0xFFF)
        showStatus(
            if (failed == 0) "权限已修改为 $octal（$ok 项）"
            else "权限已修改：成功 $ok 项，失败 $failed 项"
        )
        refreshAll()
    }
}

/** 校验值（MD5/SHA-256）：本地与网络都能算；状态条显示读取进度、可取消（审计 U4） */
fun BrowserController.checksum(uri: VfsUri, algorithm: String, onResult: (String?) -> Unit) {
    launchBusy("计算 $algorithm") { report ->
        val vfs = container.locator.find(uri)
        if (vfs == null) {
            onResult(null)
            return@launchBusy
        }
        val total = runCatching { withContext(container.dispatchers.vfs) { vfs.stat(uri).size } }.getOrDefault(-1L)
        onResult(
            checksumNow(uri, algorithm, total) { done, effectiveTotal ->
                report.report(done, effectiveTotal, "已读取 " + Fmt.transferred(done, effectiveTotal))
            }
        )
    }
}

/**
 * 校验值的挂起实现（APK 信息页等复用；算法：CRC32 / MD5 / SHA-1 / SHA-256）。
 *
 * @param totalHint 总字节数（-1 = 未知，只报已读量）
 * @param onProgress 按块上报（本地/网络逐块回调；调用方负责节流）
 */
suspend fun BrowserController.checksumNow(
    uri: VfsUri,
    algorithm: String,
    totalHint: Long = -1L,
    onProgress: ((done: Long, total: Long) -> Unit)? = null,
): String? {
    val vfs = container.locator.find(uri) ?: return null
    return withContext(container.dispatchers.vfs) {
        val reader = vfs.openRead(uri)
        try {
            // MT 的校验值清单（0x7f030009）：CRC32 / MD5 / SHA1 / SHA256
            // CRC32 不是 MessageDigest，单独走 java.util.zip.CRC32
            val crc = if (algorithm == "CRC32") java.util.zip.CRC32() else null
            val digest = if (crc == null) java.security.MessageDigest.getInstance(algorithm) else null
            val buf = ByteArray(256 * 1024)
            var done = 0L
            while (true) {
                val n = reader.read(buf, 0, buf.size)
                if (n < 0) break
                crc?.update(buf, 0, n)
                digest?.update(buf, 0, n)
                done += n.coerceAtLeast(0)
                onProgress?.invoke(done, totalHint)
            }
            // Locale.ROOT：校验值必须是固定 ASCII 十六进制（本地化数字会让「比对校验值」失去意义）
            crc?.let { "%08x".format(java.util.Locale.ROOT, it.value) }
                ?: digest!!.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        } finally {
            runCatching { reader.close() }
        }
    }
}

/** 递归 chmod 的安全上限（防超大目录把界面拖死） */
private const val MAX_CHMOD_ITEMS = 20_000
private const val MAX_CHMOD_DEPTH = 32

