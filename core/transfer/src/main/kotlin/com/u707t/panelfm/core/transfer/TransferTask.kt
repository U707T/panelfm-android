package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.Throttle
import com.u707t.panelfm.core.model.ConflictDecision
import com.u707t.panelfm.core.model.ConflictInfo
import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.model.VerifyMode
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.Resumability
import com.u707t.panelfm.core.vfs.VfsWriter
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.partNameOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 一个传输任务：计划 → 逐项执行（目录 mkdir / 文件泵）+ 冲突策略 + 暂停闸门 + 续传。
 * 同一任务内文件串行（稳定、可预期）；任务之间由引擎控制并发。
 */
class TransferTask internal constructor(
    val request: TransferRequest,
    private val planner: FileOperationPlanner,
    private val locator: VfsLocator,
    private val resumeStore: ResumeStore,
    val id: String = UUID.randomUUID().toString(),
) {
    private val _state = MutableStateFlow<TaskState>(TaskState.Queued)
    val state: StateFlow<TaskState> = _state

    /** MT「保留文件时间」：由 request 决定（设置里可关） */
    private val preserveModifiedTime: Boolean get() = request.preserveModifiedTime

    internal val gate = TransferGate()

    private var lastRunning: TaskState.Running? = null
    private var conflictWaiter: CompletableDeferred<ConflictDecision>? = null
    private var appliedPolicy: ConflictPolicy? = null

    val title: String get() = request.describe()

    // ------------------------------------------------------------------ 控制

    fun pause() {
        // 已取消 / 正在取消的任务不再接受暂停（否则会把 paused 闸门又关回去）。
        if (gate.isCancelled) return
        val s = _state.value
        if (s is TaskState.Running || s == TaskState.Queued) {
            gate.pause()
            _state.value = TaskState.Paused
        }
    }

    fun resume() {
        if (gate.isCancelled) return
        gate.resume()
        if (_state.value is TaskState.Paused) {
            // 「排队中就被暂停」的任务还没有 lastRunning —— 恢复回「排队中」，
            // 等 worker 轮到时正常走「正在统计…」，而不是伪造一条假进度。
            _state.value = lastRunning ?: TaskState.Queued
        }
    }

    fun cancel() {
        gate.cancel()
        // 立即反馈「正在取消操作…」（MT 同款文案）：真正的停止发生在传输循环的下一个
        // checkpoint，路径长时（服务端快路径 / 大目录收尾）可能还要一小会儿。
        val s = _state.value
        if (!s.isFinished && s !is TaskState.Cancelling) _state.value = TaskState.Cancelling
        conflictWaiter?.cancel()
    }

    suspend fun resolveConflict(decision: ConflictDecision) {
        if (decision.applyAll) appliedPolicy = decision.policy
        conflictWaiter?.complete(decision)
    }

    // ------------------------------------------------------------------ 执行

    private fun running(state: TaskState.Running) {
        // 取消后不再覆盖「正在取消…」；暂停中只更新快照（lastRunning），状态维持「已暂停」——
        // 否则暂停瞬间最后一批字节回调会把状态又刷回「进行中」。
        if (gate.isCancelled) return
        lastRunning = state
        if (_state.value is TaskState.Paused) return
        _state.value = state
    }

    internal suspend fun run() {
        val startedAt = System.currentTimeMillis()
        var ok = 0
        var skipped = 0
        val failed = mutableListOf<FailedItem>()
        var doneBytes = 0L
        val progressThrottle = Throttle(200)

        try {
            // 入队后立刻被取消 / 暂停的，在这里就生效：取消直接退出（不再统计），
            // 暂停挂起等待恢复（状态已由 pause() 置为「已暂停」）。
            gate.checkpoint()
            running(TaskState.Running(0, 0, "正在统计…", 0, 0, 0, -1))
            val plan = planner.plan(request, checkpoint = gate::checkpoint) { count, bytes ->
                running(TaskState.Running(0, count, "正在统计…", 0, bytes, 0, -1))
            }
            val totalPlannedBytes = plan.totalBytes
            var slowPlan: OperationPlan? = null

            // 有校验要求时必须逐文件读取；否则服务端快路径无法证明内容一致。
            // 快路径返回 false 的含义是「当前协议不支持服务端操作」，必须降级到统一的
            // 流式慢路径（VirtualFileSystem.serverSideCopy 的契约明确要求引擎降级），
            // 不能把一个可传输任务错误地终结为失败。
            if (plan.fastPath != FastPath.NONE && request.verify == VerifyMode.NONE) {
                val roots = rootItems()
                val fallbackRoots = mutableListOf<FallbackRoot>()
                roots.forEachIndexed { i, (src, dst) ->
                    gate.checkpoint()
                    val vfs = locator.find(src) ?: throw VfsException.Unsupported("会话已关闭")
                    val sourceMeta = vfs.stat(src)
                    when (val decision = resolveFastPathTarget(src, dst, vfs, sourceMeta)) {
                        FastPathTarget.Skip -> {
                            skipped += itemCountForRoot(plan, src)
                        }
                        is FastPathTarget.Fallback -> {
                            // 冲突目录采用慢路径的「目录合并」语义；或者服务端能力在运行时
                            // 返回 false，均保留已经解析出的最终目标，避免 KEEP_BOTH 二次改名。
                            fallbackRoots += FallbackRoot(src, decision.target)
                        }
                        is FastPathTarget.Execute -> {
                            val success = when (plan.fastPath) {
                                FastPath.SERVER_MOVE -> vfs.rename(src, decision.target)
                                FastPath.SERVER_COPY -> vfs.serverSideCopy(src, decision.target)
                                FastPath.NONE -> false
                            }
                            if (success) {
                                ok += itemCountForRoot(plan, src)
                                doneBytes += bytesForRoot(plan, src)
                                // rename 天然保留 mtime；服务端复制则显式补回文件 mtime（支持时）。
                                if (request.preserveModifiedTime) {
                                    val desiredRoot = request.destDir.child(src.name)
                                    plan.items
                                        .filter { !it.isDirectory && isSameOrDescendant(it.source, src) }
                                        .filter { it.lastModified > 0 }
                                        .forEach { item ->
                                            val targetItem = rebaseDestination(item.dest, desiredRoot, decision.target)
                                            runCatching { vfs.setModified(targetItem, item.lastModified) }
                                        }
                                }
                            } else {
                                fallbackRoots += FallbackRoot(src, decision.target)
                            }
                        }
                    }
                    running(
                        TaskState.Running(
                            i + 1, roots.size, src.name, doneBytes, totalPlannedBytes, 0, -1,
                        )
                    )
                }

                if (fallbackRoots.isNotEmpty()) {
                    val fallbackSources = fallbackRoots.map { it.source }
                    val fallbackRequest = request.copy(sources = fallbackSources)
                    val rawFallbackPlan = planner.plan(fallbackRequest, checkpoint = gate::checkpoint)
                    val rewrittenItems = rawFallbackPlan.items.map { item ->
                        val root = fallbackRoots
                            .filter { isSameOrDescendant(item.source, it.source) }
                            .maxByOrNull { it.source.path.length }
                        if (root == null) {
                            item
                        } else {
                            val originalRootDest = request.destDir.child(root.source.name)
                            item.copy(dest = rebaseDestination(item.dest, originalRootDest, root.target))
                        }
                    }
                    // 强制慢路径，避免「服务端返回 false → 重新规划 → 再次进入同一快路径」循环。
                    slowPlan = rawFallbackPlan.copy(items = rewrittenItems, fastPath = FastPath.NONE)
                }

                if (slowPlan == null) {
                    _state.value = TaskState.Done(
                        ok = ok,
                        skipped = skipped,
                        failed = failed.size,
                        bytes = doneBytes,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        note = "服务端完成",
                    )
                    return
                }
            }

            val executionPlan = slowPlan ?: plan
            // 即使只有部分根目录降级，进度总量仍应使用整批原始计划的总字节数；
            // 不能只用 fallback plan，否则已完成的服务端部分会让百分比/ETA 失真。
            val totalBytes = totalPlannedBytes
            // 慢路径：先解决顶层目录冲突并记录 source → target 映射。
            // KEEP_BOTH / SKIP 必须影响整棵子树，不能只改目录项本身。
            val dirResolution = resolveDirectoryRoots(executionPlan)
            val directoryMappings = dirResolution.mappings.toMutableList()
            val skippedRoots = dirResolution.skippedRoots.toMutableSet()
            var lastSampleAt = System.currentTimeMillis()
            var lastSampleBytes = doneBytes

            executionPlan.items.forEachIndexed { index, item ->
                if (skippedRoots.any { root -> isSameOrDescendant(item.source, root) }) {
                    skipped++
                    return@forEachIndexed
                }
                gate.checkpoint()
                try {
                    val destination = mappedDestination(item, directoryMappings)
                    if (item.isDirectory) {
                        val vfs = locator.find(destination) ?: throw VfsException.Unsupported("会话已关闭")
                        val existing = existingOrNull(vfs, destination)
                        when {
                            existing == null -> {
                                vfs.mkdir(destination, parents = true)
                                ok++
                            }
                            existing.isDirectory -> {
                                // 目录合并：目录下的文件由 resolveDest 按冲突策略处理。
                                ok++
                            }
                            else -> {
                                when (decideConflict(item.source, destination, existing, item)) {
                                    ConflictPolicy.SKIP -> {
                                        skippedRoots += item.source
                                        skipped++
                                    }
                                    ConflictPolicy.KEEP_BOTH -> {
                                        val target = keepBoth(vfs, destination)
                                        directoryMappings += DirectoryMapping(item.source, target)
                                        vfs.mkdir(target, parents = true)
                                        ok++
                                    }
                                    ConflictPolicy.OVERWRITE -> {
                                        deleteForOverwrite(vfs, destination)
                                        vfs.mkdir(destination, parents = true)
                                        ok++
                                    }
                                    ConflictPolicy.ASK -> error("未解析的冲突策略")
                                }
                            }
                        }
                    } else {
                        val vfsDst = locator.find(destination) ?: throw VfsException.Unsupported("目标会话已关闭")
                        val vfsSrc = locator.find(item.source) ?: throw VfsException.Unsupported("源会话已关闭")
                        val target = resolveDest(item.source, destination, vfsDst)
                        if (target == null) {
                            skipped++
                        } else {
                            // 「当前文件」进度的基准：字节回调只给增量，记下本文件开始前的全局计数，
                            // 相减即得当前文件已完成字节（含续传起点）。
                            val itemBaseBytes = doneBytes
                            val copied = transferFile(item, target, index, totalBytes) { delta ->
                                doneBytes += delta
                                val now = System.currentTimeMillis()
                                if (progressThrottle.shouldReport(now)) {
                                    val dt = (now - lastSampleAt).coerceAtLeast(1)
                                    val speed = (doneBytes - lastSampleBytes) * 1000 / dt
                                    lastSampleAt = now
                                    lastSampleBytes = doneBytes
                                    val eta = if (speed > 0 && totalBytes > doneBytes) (totalBytes - doneBytes) / speed else -1
                                    running(
                                        TaskState.Running(
                                            index + 1, executionPlan.items.size, target.name,
                                            doneBytes, totalBytes, speed, eta,
                                            itemDoneBytes = (doneBytes - itemBaseBytes).coerceAtLeast(0),
                                            itemTotalBytes = item.size.coerceAtLeast(0),
                                        )
                                    )
                                }
                            }
                            if (copied) {
                                ok++
                                if (request.op == TransferOp.MOVE) {
                                    runCatching { vfsSrc.delete(listOf(item.source)) }
                                        .onFailure { failed += FailedItem(item.source, "已复制但删除源失败：${it.message}") }
                                }
                            } else {
                                skipped++
                            }
                        }
                    }
                } catch (e: VfsException.Cancelled) {
                    throw e
                } catch (e: Exception) {
                    Logx.w("TransferTask", "item failed ${item.source}: ${e.message}", e)
                    failed += FailedItem(item.source, (e as? VfsException)?.userMessage ?: e.message ?: "未知错误")
                }
            }

            // MOVE 只清理成功迁移后留下的空目录；被跳过的子树绝不能顺手删掉。
            if (request.op == TransferOp.MOVE) {
                val dirs = executionPlan.items
                    .filter { it.isDirectory && skippedRoots.none { root -> isSameOrDescendant(it.source, root) } }
                    .sortedByDescending { it.depth }
                for (dir in dirs) {
                    runCatching {
                        val vfs = locator.find(dir.source) ?: return@runCatching
                        if (vfs.list(dir.source).isEmpty()) vfs.delete(listOf(dir.source))
                    }
                }
            }

            _state.value = TaskState.Done(
                ok = ok,
                skipped = skipped,
                failed = failed.size,
                bytes = doneBytes,
                elapsedMs = System.currentTimeMillis() - startedAt,
                note = if (failed.isEmpty()) null else "失败 ${failed.size} 项",
            )
        } catch (c: VfsException.Cancelled) {
            // 取消时保留可续传目标的 .part 与断点记录。
            _state.value = TaskState.Cancelled
        } catch (e: Exception) {
            Logx.e("TransferTask", "task failed: ${e.message}", e)
            _state.value = TaskState.Failed((e as? VfsException)?.userMessage ?: e.message ?: "传输失败")
        }
    }

    /** UI 用的任务快照（进度 / 冲突 / 完成状态变化时由引擎重新生成） */
    fun toSnapshot(): TransferTaskSnapshot {
        val s = _state.value

        /**
         * 任务行副标题（UI 的第二行；拿不到的字段自动省略）：
         *  - 运行中：`12/156 项 · 1.2 G/2.0 G · 2.1 M/s · 剩 3m`；
         *  - 完成：`成功 x · 跳过 y · 失败 z · 用时 …`；
         *  - 其余状态：一句话说明。
         */
        val subtitle = when (s) {
            is TaskState.Running -> listOfNotNull(
                "${s.index}/${s.total} 项",
                com.u707t.panelfm.core.common.Fmt.transferred(s.doneBytes, s.totalBytes),
                com.u707t.panelfm.core.common.Fmt.speed(s.speedBps).takeIf { it.isNotBlank() },
                if (s.etaSeconds > 0) "剩 ${com.u707t.panelfm.core.common.Fmt.eta(s.etaSeconds)}" else null,
            ).joinToString(" · ")
            is TaskState.Done -> buildString {
                append("成功 ${s.ok}")
                if (s.skipped > 0) append(" · 跳过 ${s.skipped}")
                if (s.failed > 0) append(" · 失败 ${s.failed}")
                append(" · 用时 ${com.u707t.panelfm.core.common.Fmt.duration(s.elapsedMs)}")
                s.note?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            }
            is TaskState.Failed -> s.message
            is TaskState.Cancelled -> "已取消"
            is TaskState.Paused -> "已暂停"
            is TaskState.WaitingConflict -> "等待冲突处理"
            is TaskState.Cancelling -> "正在取消操作…"
            TaskState.Queued -> "排队中"
        }
        return TransferTaskSnapshot(id = id, title = title, subtitle = subtitle, state = s, op = request.op)
    }

    private sealed interface FastPathTarget {
        data object Skip : FastPathTarget
        data class Execute(val target: VfsUri) : FastPathTarget
        data class Fallback(val target: VfsUri) : FastPathTarget
    }

    private data class FallbackRoot(
        val source: VfsUri,
        val target: VfsUri,
    )

    private suspend fun resolveFastPathTarget(
        source: VfsUri,
        desired: VfsUri,
        vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        sourceMeta: FileMetadata,
    ): FastPathTarget {
        val sameAsSource = source.sameMount(desired) &&
            source.path.trimEnd('/') == desired.path.trimEnd('/')
        if (sameAsSource) return FastPathTarget.Skip

        val existing = existingOrNull(vfs, desired) ?: return FastPathTarget.Execute(desired)
        val decision = decideConflict(source, desired, existing, null)

        // 目录覆盖目录不能走「删除目标再服务端复制」：慢路径语义是目录合并，
        // 需要逐项按冲突策略处理。保留原目标，交给慢路径。
        if (sourceMeta.isDirectory && existing.isDirectory) {
            return when (decision) {
                ConflictPolicy.SKIP -> FastPathTarget.Skip
                ConflictPolicy.KEEP_BOTH -> FastPathTarget.Execute(keepBoth(vfs, desired))
                ConflictPolicy.OVERWRITE -> FastPathTarget.Fallback(desired)
                ConflictPolicy.ASK -> error("未解析的冲突策略")
            }
        }

        return when (decision) {
            ConflictPolicy.SKIP -> FastPathTarget.Skip
            ConflictPolicy.KEEP_BOTH -> FastPathTarget.Execute(keepBoth(vfs, desired))
            ConflictPolicy.OVERWRITE -> {
                // 文件/目录类型冲突：覆盖语义确实要求先移除异型目标。
                deleteForOverwrite(vfs, desired)
                FastPathTarget.Execute(desired)
            }
            ConflictPolicy.ASK -> error("未解析的冲突策略")
        }
    }

    private fun itemCountForRoot(plan: OperationPlan, root: VfsUri): Int =
        plan.items.count { isSameOrDescendant(it.source, root) }.coerceAtLeast(1)

    private fun bytesForRoot(plan: OperationPlan, root: VfsUri): Long =
        plan.items
            .filter { !it.isDirectory && isSameOrDescendant(it.source, root) }
            .sumOf { it.size.coerceAtLeast(0) }

    private data class DirectoryMapping(
        val sourceRoot: VfsUri,
        val targetRoot: VfsUri,
    )

    private data class DirectoryResolution(
        val mappings: List<DirectoryMapping>,
        val skippedRoots: Set<VfsUri>,
    )

    /** 顶层条目（服务端快路径按顶层操作，目录内部由协议递归处理） */
    private suspend fun rootItems(): List<Pair<VfsUri, VfsUri>> {
        val result = mutableListOf<Pair<VfsUri, VfsUri>>()
        for (src in request.sources) {
            val vfs = locator.find(src) ?: throw VfsException.Unsupported("源位置不可用：${src.authority}")
            val meta = vfs.stat(src)
            result += src to request.destDir.child(meta.name)
        }
        return result
    }

    /**
     * 解决慢路径下的顶层目录冲突。
     *
     * 目录不能简单当成一个文件处理：KEEP_BOTH 必须重映射整棵子树，
     * SKIP 必须阻止后续子项继续写入，已有目录则采用合并语义。
     */
    private suspend fun resolveDirectoryRoots(plan: OperationPlan): DirectoryResolution {
        val destVfs = locator.find(request.destDir)
            ?: throw VfsException.Unsupported("目标位置不可用（会话已关闭？）")
        val mappings = mutableListOf<DirectoryMapping>()
        val skipped = linkedSetOf<VfsUri>()

        plan.items
            .filter { it.isDirectory && request.sources.contains(it.source) }
            .forEach { root ->
                gate.checkpoint()
                val desired = root.dest
                val existing = existingOrNull(destVfs, desired)
                if (existing == null) {
                    mappings += DirectoryMapping(root.source, desired)
                    return@forEach
                }

                val sameAsSource = root.source.sameMount(desired) &&
                    root.source.path.trimEnd('/') == desired.path.trimEnd('/')
                if (sameAsSource) {
                    skipped += root.source
                    return@forEach
                }

                when (decideConflict(root.source, desired, existing, root)) {
                    ConflictPolicy.SKIP -> skipped += root.source
                    ConflictPolicy.KEEP_BOTH -> {
                        val target = keepBoth(destVfs, desired)
                        mappings += DirectoryMapping(root.source, target)
                    }
                    ConflictPolicy.OVERWRITE -> {
                        // 同类型目录按 MT/文件管理器惯例合并，子文件再逐项覆盖/跳过；
                        // 只有「目录 vs 文件」才先删除目标。
                        if (!existing.isDirectory) deleteForOverwrite(destVfs, desired)
                        mappings += DirectoryMapping(root.source, desired)
                    }
                    ConflictPolicy.ASK -> error("未解析的冲突策略")
                }
            }
        return DirectoryResolution(mappings, skipped)
    }

    private fun mappedDestination(item: PlanItem, mappings: List<DirectoryMapping>): VfsUri {
        val mapping = mappings
            .filter { isSameOrDescendant(item.source, it.sourceRoot) }
            .maxByOrNull { it.sourceRoot.path.length }
            ?: return item.dest
        val sourceRoot = mapping.sourceRoot.path.trimEnd('/').ifEmpty { "/" }
        val suffix = if (sourceRoot == "/") item.source.path else item.source.path.removePrefix(sourceRoot)
        return mapping.targetRoot.withPath(mapping.targetRoot.path.trimEnd('/') + suffix)
    }

    private fun rebaseDestination(original: VfsUri, oldRoot: VfsUri, newRoot: VfsUri): VfsUri {
        val oldPath = oldRoot.path.trimEnd('/').ifEmpty { "/" }
        val suffix = if (oldPath == "/") {
            original.path
        } else {
            original.path.removePrefix(oldPath)
        }
        return newRoot.withPath(newRoot.path.trimEnd('/') + suffix)
    }

    private fun isSameOrDescendant(candidate: VfsUri, root: VfsUri): Boolean {
        if (!candidate.sameMount(root)) return false
        val rootPath = root.path.trimEnd('/').ifEmpty { "/" }
        val candidatePath = candidate.path.trimEnd('/').ifEmpty { "/" }
        return candidatePath == rootPath ||
            (rootPath != "/" && candidatePath.startsWith(rootPath + "/"))
    }

    /** 只把「确实不存在」当成不存在；认证/网络/协议异常必须继续抛出。 */
    private suspend fun existingOrNull(
        vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        uri: VfsUri,
    ): FileMetadata? = try {
        vfs.stat(uri)
    } catch (_: VfsException.NotFound) {
        null
    }

    private suspend fun deleteForOverwrite(
        vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        uri: VfsUri,
    ) {
        try {
            vfs.delete(listOf(uri))
        } catch (e: Exception) {
            throw if (e is VfsException) e else VfsException.Io("无法覆盖目标：${uri.name}", e)
        }
        // delete() 成功返回不等于目标已经消失（部分远端实现可能静默失败）。
        if (existingOrNull(vfs, uri) != null) {
            throw VfsException.Io("无法覆盖目标：${uri.name}（删除后仍存在）")
        }
    }

    /** 冲突处理：返回实际写入目标（null = 跳过） */
    private suspend fun resolveDest(
        source: VfsUri,
        desired: VfsUri,
        destVfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
    ): VfsUri? {
        val sameAsSource = source.sameMount(desired) &&
            source.path.trimEnd('/') == desired.path.trimEnd('/')
        // 先判断自身复制，避免对某些 VFS 再 stat 一次后误删源文件。
        if (sameAsSource) return null
        val existing = existingOrNull(destVfs, desired) ?: return desired
        val decided = decideConflict(source, desired, existing, null)
        return when (decided) {
            ConflictPolicy.OVERWRITE -> {
                deleteForOverwrite(destVfs, desired)
                desired
            }
            ConflictPolicy.SKIP -> null
            ConflictPolicy.KEEP_BOTH -> keepBoth(destVfs, desired)
            ConflictPolicy.ASK -> error("未解析的冲突策略")
        }
    }

    private suspend fun decideConflict(
        source: VfsUri,
        desired: VfsUri,
        existing: FileMetadata,
        sourceItem: PlanItem?,
    ): ConflictPolicy {
        val policy = appliedPolicy ?: request.conflict
        return when (policy) {
            ConflictPolicy.ASK -> askConflict(source, desired, existing, sourceItem)
            else -> policy
        }
    }

    private suspend fun askConflict(
        source: VfsUri,
        desired: VfsUri,
        existing: FileMetadata,
        sourceItem: PlanItem?,
    ): ConflictPolicy {
        val info = ConflictInfo(
            index = 0,
            total = 1,
            sourceName = source.name,
            sourceSize = sourceItem?.size ?: -1,
            sourceModified = sourceItem?.lastModified ?: -1,
            destName = existing.name,
            destSize = existing.size,
            destModified = existing.lastModified,
            isDirectory = existing.isDirectory,
            isMove = request.op == TransferOp.MOVE,
        )
        val waiter = CompletableDeferred<ConflictDecision>()
        conflictWaiter = waiter
        _state.value = TaskState.WaitingConflict(info)
        val decision = try {
            waiter.await()
        } catch (_: kotlinx.coroutines.CancellationException) {
            throw VfsException.Cancelled()
        } finally {
            conflictWaiter = null
        }
        return decision.policy
    }

    private suspend fun keepBoth(
        destVfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        desired: VfsUri,
    ): VfsUri {
        val name = desired.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (i < 1000) {
            val candidate = desired.parent?.child("$base ($i)$ext") ?: return desired
            if (existingOrNull(destVfs, candidate) == null) return candidate
            i++
        }
        throw VfsException.Conflict(desired)
    }

    /** VerifyMode 的实际执行：SIZE 校验长度，HASH 再做 SHA-256 流式校验。 */
    private suspend fun verifyFile(item: PlanItem, target: VfsUri) {
        if (request.verify == VerifyMode.NONE) return
        val srcVfs = locator.find(item.source) ?: throw VfsException.Unsupported("源会话已关闭")
        val dstVfs = locator.find(target) ?: throw VfsException.Unsupported("目标会话已关闭")
        val targetMeta = dstVfs.stat(target)
        if (targetMeta.isDirectory || (item.size >= 0 && targetMeta.size != item.size)) {
            throw VfsException.ProtocolError(
                "传输校验失败：${item.source.name} 大小 ${item.size}，目标大小 ${targetMeta.size}",
            )
        }
        if (request.verify == VerifyMode.HASH) {
            val sourceHash = digest(srcVfs, item.source)
            val targetHash = digest(dstVfs, target)
            if (!sourceHash.contentEquals(targetHash)) {
                throw VfsException.ProtocolError("传输校验失败：${item.source.name} 内容不一致")
            }
        }
    }

    private suspend fun digest(
        vfs: com.u707t.panelfm.core.vfs.VirtualFileSystem,
        uri: VfsUri,
    ): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val reader = vfs.openRead(uri)
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            var emptyReads = 0
            while (true) {
                val n = reader.read(buffer, 0, buffer.size)
                if (n < 0) break
                if (n == 0) {
                    if (++emptyReads >= MAX_EMPTY_READS) {
                        throw VfsException.ProtocolError("读取无进展，源会话可能异常")
                    }
                    continue
                }
                emptyReads = 0
                digest.update(buffer, 0, n)
            }
            return digest.digest()
        } finally {
            runCatching { reader.close() }
        }
    }

    /** 单文件泵：offset 续传 → chunk 循环 → commit → 可选校验。 */
    private suspend fun transferFile(
        item: PlanItem,
        target: VfsUri,
        index: Int,
        totalBytes: Long,
        onDelta: suspend (Long) -> Unit,
    ): Boolean {
        val srcVfs = locator.find(item.source) ?: throw VfsException.Unsupported("源会话已关闭")
        val dstVfs = locator.find(target) ?: throw VfsException.Unsupported("目标会话已关闭")

        // 续传查找：按 (source → dest) 路径匹配，并校验源 mtime。
        val srcMeta = runCatching { srcVfs.stat(item.source) }.getOrNull()
        val validator = srcMeta?.lastModified?.takeIf { it > 0 }?.toString()
        var startOffset = 0L
        if (dstVfs.capabilities.resumable != Resumability.NONE && item.size > 0) {
            val entry = resumeStore.findFor(item.source, target)
            if (entry != null && entry.total == item.size && entry.offset in 1 until item.size &&
                (validator == null || entry.validator == null || entry.validator == validator)
            ) {
                // .part 不存在/长度不足时不能盲目按偏移写，否则会拼出损坏文件。
                val partUri = target.parent?.child(partNameOf(target.name))
                val partSize = partUri?.let { runCatching { dstVfs.stat(it).size }.getOrNull() } ?: -1L
                if (partSize >= entry.offset) {
                    startOffset = entry.offset
                    Logx.i("TransferTask", "resume ${target.name} @${startOffset}/${item.size}")
                } else {
                    Logx.w("TransferTask", "part missing/short (${partSize} < ${entry.offset}) → restart ${target.name}")
                }
            }
        }

        val reader = srcVfs.openRead(item.source, offset = startOffset)
        try {
            if (item.size in 1 until startOffset) {
                throw VfsException.ProtocolError("续传偏移异常：$startOffset > ${item.size}")
            }
        } catch (e: Exception) {
            runCatching { reader.close() }
            throw e
        }

        var writer: VfsWriter? = null
        var readerClosed = false
        val resumable = dstVfs.capabilities.resumable != Resumability.NONE
        var written = startOffset
        // 断点落盘节流：旧实现**每 256 KB** 写一次 SQLite（1 GB 文件 ≈ 4096 次 INSERT，
        // 全部发生在传输关键路径上）。改为 ≥1 s 落一次，并在失败/取消时补记最终偏移。
        val resumeThrottle = Throttle(1000)
        try {
            // openWrite 失败时 writer 仍为 null，但 finally 仍会关闭已经打开的 reader。
            writer = dstVfs.openWrite(target, size = if (item.size > 0) item.size else null, offset = startOffset)
            val out = writer
            var emptyReads = 0
            val buffer = ByteArray(BUFFER_SIZE)
            if (startOffset > 0) onDelta(startOffset)
            while (true) {
                gate.checkpoint()
                val n = reader.read(buffer, 0, buffer.size)
                if (n < 0) break
                if (n == 0) {
                    if (++emptyReads >= MAX_EMPTY_READS) {
                        throw VfsException.ProtocolError("读取无进展，源会话可能异常")
                    }
                    continue
                }
                emptyReads = 0
                out.write(buffer, 0, n)
                written += n
                onDelta(n.toLong())
                if (resumable && resumeThrottle.shouldReport()) {
                    saveResumePoint(item, target, index, written, validator)
                }
            }
            out.flush()
            out.commit()
            // 先释放源流再做校验：FTP 等协议的控制/数据通道不能嵌套占用。
            runCatching { reader.close() }
            readerClosed = true

            // MT「保留文件时间」：提交后把源文件的 mtime 写回目标（失败静默）。
            if (preserveModifiedTime && item.lastModified > 0) {
                runCatching { dstVfs.setModified(target, item.lastModified) }
            }
            verifyFile(item, target)
            resumeStore.clearFor(item.source, target)
            return true
        } catch (e: Exception) {
            withContext(NonCancellable) {
                val activeWriter = writer
                if (activeWriter != null) {
                    if (resumable) {
                        // 可续传目标（本地 / SFTP / SMB）：保留 .part 与断点记录。
                        runCatching { activeWriter.close() }
                        // 节流后可能少记最后不到 1 秒的偏移 → 这里补一次最终进度，
                        // 下次续传从正确位置接上（写失败也不应影响原始异常）。
                        if (written > startOffset) {
                            saveResumePoint(item, target, index, written, validator)
                        }
                    } else {
                        // 不可续传目标（FTP / WebDAV / S3 / 压缩包）：清理半成品。
                        runCatching { activeWriter.abort() }
                    }
                }
            }
            throw e
        } finally {
            if (!readerClosed) runCatching { reader.close() }
            runCatching { writer?.close() }
        }
    }

    /**
     * 落盘一条断点记录。
     *
     * 写入失败**不能**中断传输（最坏情况只是下次从头重传），因此在这里吞掉异常并只记日志；
     * 调用方负责节流（见 [transferFile] 里的 `resumeThrottle`）。
     */
    private suspend fun saveResumePoint(
        item: PlanItem,
        target: VfsUri,
        index: Int,
        offset: Long,
        validator: String?,
    ) {
        runCatching {
            resumeStore.save(
                ResumeEntry(
                    taskId = id, itemIndex = index, source = item.source, dest = target,
                    tempUri = null, offset = offset, total = item.size,
                    validator = validator, updatedAt = System.currentTimeMillis(),
                )
            )
        }.onFailure { Logx.w("TransferTask", "resume save failed: ${it.message}", it) }
    }

    companion object {
        const val BUFFER_SIZE = 256 * 1024
        private const val MAX_EMPTY_READS = 3
    }
}
