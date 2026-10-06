package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsUri

/**
 * 计划器：递归枚举源 → 生成 PlanItem，并判定是否可走服务端快路径。
 * 所有「协议差异」都在这里被抹平，传输执行阶段只看 PlanItem。
 */
class FileOperationPlanner(private val locator: VfsLocator) {

    /**
     * 生成操作计划。
     *
     * [checkpoint] 是传输闸门的可取消点（`TransferGate.checkpoint`）：大目录「正在统计…」
     * 期间也允许取消 / 暂停 —— 旧实现只有传输循环里有 checkpoint，统计 10 万文件的目录时
     * 用户点取消毫无反应。默认为空（测试可直接调用）。
     */
    suspend fun plan(
        request: TransferRequest,
        checkpoint: suspend () -> Unit = {},
        onScan: (Int, Long) -> Unit = { _, _ -> },
    ): OperationPlan {
        val items = mutableListOf<PlanItem>()
        var bytes = 0L
        var files = 0
        var dirs = 0

        val destVfs = locator.find(request.destDir)
            ?: throw VfsException.Unsupported("目标位置不可用（会话已关闭？）")

        // 自包含检查：不允许把目录复制进它自己内部
        request.sources.forEach { src ->
            if (isInside(src, request.destDir)) {
                throw VfsException.IllegalArgument("目标位于源内部，无法操作：${src.displayPath}")
            }
        }

        for (src in request.sources) {
            checkpoint()
            val vfs = locator.find(src) ?: throw VfsException.Unsupported("源位置不可用：${src.authority}")
            val meta = vfs.stat(src)
            if (meta.isSymlink) {
                throw VfsException.Unsupported("暂不支持传输符号链接：${meta.name}")
            }
            val destRoot = request.destDir.child(meta.name)
            if (meta.isDirectory) {
                collectDir(vfs = vfs, src = src, dest = destRoot, depth = 0,
                    items = items,
                    checkpoint = checkpoint,
                    onFile = { size ->
                        files++
                        bytes += size
                        if (files % 32 == 0) onScan(files, bytes)
                    },
                    onDir = { dirs++ })
            } else {
                items += PlanItem(src, destRoot, isDirectory = false, size = meta.size.coerceAtLeast(0), depth = 0, lastModified = meta.lastModified)
                files++
                bytes += meta.size.coerceAtLeast(0)
                onScan(files, bytes)
            }
        }

        val fastPath = decideFastPath(request)
        Logx.i("Planner", "items=${items.size} files=$files dirs=$dirs bytes=$bytes fastPath=$fastPath")
        return OperationPlan(items, fastPath, bytes, files, dirCount = dirs)
    }

    private suspend fun collectDir(
        vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        src: VfsUri,
        dest: VfsUri,
        depth: Int,
        items: MutableList<PlanItem>,
        checkpoint: suspend () -> Unit,
        onFile: (Long) -> Unit,
        onDir: () -> Unit,
    ) {
        if (depth > MAX_DEPTH) throw VfsException.ProtocolError("目录层级过深（> $MAX_DEPTH），疑似软链接环")
        checkpoint()
        onDir()
        items += PlanItem(src, dest, isDirectory = true, size = -1, depth = depth)
        val children = vfs.list(src)
        for (child in children) {
            checkpoint()
            if (child.isSymlink) {
                throw VfsException.Unsupported("暂不支持传输符号链接：${child.name}")
            }
            val childDest = dest.child(child.name)
            if (child.isDirectory) {
                collectDir(vfs, child.uri, childDest, depth + 1, items, checkpoint, onFile, onDir)
            } else if (!child.isDirectory) {
                items += PlanItem(child.uri, childDest, isDirectory = false, size = child.size.coerceAtLeast(0), depth = depth + 1, lastModified = child.lastModified)
                onFile(child.size.coerceAtLeast(0))
            }
        }
        if (items.size > MAX_ITEMS) throw VfsException.ProtocolError("文件数超过 $MAX_ITEMS，建议分批操作")
    }

    /** 同协议同服务端 → 服务端直连（零中转）；否则本地中转 */
    private suspend fun decideFastPath(request: TransferRequest): FastPath {
        val destVfs = locator.find(request.destDir) ?: return FastPath.NONE
        val sameVfs = request.sources.all { locator.find(it) === destVfs }
        if (!sameVfs) return FastPath.NONE
        return when {
            request.op == TransferOp.MOVE && destVfs.capabilities.rename -> FastPath.SERVER_MOVE
            request.op == TransferOp.COPY && destVfs.capabilities.serverSideCopy -> FastPath.SERVER_COPY
            else -> FastPath.NONE
        }
    }

    private fun isInside(src: VfsUri, dest: VfsUri): Boolean {
        if (!src.sameMount(dest)) return false
        val s = src.path.trimEnd('/')
        val d = dest.path.trimEnd('/')
        return d.isNotEmpty() && d != s && (d + "/").startsWith(s + "/")
    }

    companion object {
        const val MAX_DEPTH = 64
        const val MAX_ITEMS = 200_000
    }
}
