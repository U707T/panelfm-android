package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.Throttle
import com.u707t.panelfm.core.model.ConflictDecision
import com.u707t.panelfm.core.model.ConflictInfo
import com.u707t.panelfm.core.model.ConflictPolicy
import com.u707t.panelfm.core.model.TransferOp
import com.u707t.panelfm.core.vfs.Resumability
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
        gate.pause()
        if (_state.value is TaskState.Running) _state.value = TaskState.Paused
    }

    fun resume() {
        gate.resume()
        if (_state.value is TaskState.Paused) {
            _state.value = lastRunning ?: TaskState.Running(0, 0, "继续…", 0, 0, 0, -1)
        }
    }

    fun cancel() {
        gate.cancel()
        conflictWaiter?.cancel()
    }

    suspend fun resolveConflict(decision: ConflictDecision) {
        if (decision.applyAll) appliedPolicy = decision.policy
        conflictWaiter?.complete(decision)
    }

    // ------------------------------------------------------------------ 执行

    private fun running(state: TaskState.Running) {
        lastRunning = state
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
            running(TaskState.Running(0, 0, "正在统计…", 0, 0, 0, -1))
            val plan = planner.plan(request) { count, bytes ->
                running(TaskState.Running(0, count, "正在统计…", 0, bytes, 0, -1))
            }
            val total = plan.items.size
            val totalBytes = plan.totalBytes

            // ---- 服务端快路径（零中转）
            if (plan.fastPath != FastPath.NONE) {
                val roots = rootItems()
                roots.forEachIndexed { i, (src, dst) ->
                    gate.checkpoint()
                    val vfs = locator.find(src) ?: throw VfsException.Unsupported("会话已关闭")
                    val target = resolveDest(src, dst, vfs) ?: run { skipped++; return@forEachIndexed }
                    val success = when (plan.fastPath) {
                        FastPath.SERVER_MOVE -> vfs.rename(src, target)
                        FastPath.SERVER_COPY -> vfs.serverSideCopy(src, target)
                        FastPath.NONE -> false
                    }
                    if (success) ok++ else failed += FailedItem(src, "服务端操作失败")
                    running(TaskState.Running(i + 1, roots.size, src.name, ok.toLong(), roots.size.toLong(), 0, -1))
                }
                _state.value = TaskState.Done(ok, skipped, failed.size, 0, System.currentTimeMillis() - startedAt, "服务端完成")
                return
            }

            // ---- 慢路径：逐项流式
            var lastSampleAt = System.currentTimeMillis()
            var lastSampleBytes = 0L
            plan.items.forEachIndexed { index, item ->
                gate.checkpoint()
                try {
                    if (item.isDirectory) {
                        val vfs = locator.find(item.dest) ?: throw VfsException.Unsupported("会话已关闭")
                        vfs.mkdir(item.dest, parents = true)
                        ok++
                    } else {
                        val vfsDst = locator.find(item.dest) ?: throw VfsException.Unsupported("会话已关闭")
                        val vfsSrc = locator.find(item.source) ?: throw VfsException.Unsupported("会话已关闭")
                        val target = resolveDest(item.source, item.dest, vfsDst)
                        if (target == null) {
                            skipped++
                        } else {
                            val copied = transferFile(item, target, index, plan.items.size, totalBytes, doneBytes) { delta ->
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
                                            index + 1, plan.items.size, item.dest.name,
                                            doneBytes, totalBytes, speed, eta,
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

            // ---- 慢路径收尾：MOVE 时清理源目录骨架（只删「已经空了」的目录；
            //      失败/跳过的文件会让目录保持非空 → 原样保留，避免误删）
            if (request.op == TransferOp.MOVE && plan.fastPath == FastPath.NONE) {
                val dirs = plan.items.filter { it.isDirectory }.sortedByDescending { it.depth }
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
            // 注意：取消时**不清空续传记录**（.part 与记录都保留，下次同一文件传输从断点继续）；
            // 过期记录由启动时的 purgeStale 清理。
            _state.value = TaskState.Cancelled
        } catch (e: Exception) {
            Logx.e("TransferTask", "task failed: ${e.message}", e)
            _state.value = TaskState.Failed((e as? VfsException)?.userMessage ?: e.message ?: "传输失败")
        }
    }

    /** UI 用的任务快照（进度 / 冲突 / 完成状态变化时由引擎重新生成） */
    fun toSnapshot(): TransferTaskSnapshot {
        val s = _state.value
        val subtitle = when (s) {
            is TaskState.Running -> listOf(
                s.currentName,
                com.u707t.panelfm.core.common.Fmt.transferred(s.doneBytes, s.totalBytes),
                com.u707t.panelfm.core.common.Fmt.speed(s.speedBps),
                if (s.etaSeconds > 0) "剩 ${com.u707t.panelfm.core.common.Fmt.eta(s.etaSeconds)}" else "",
            ).filter { it.isNotBlank() }.joinToString(" · ")
            is TaskState.Done -> "完成 ${s.ok} 项" + if (s.failed > 0) "，失败 ${s.failed}" else ""
            is TaskState.Failed -> s.message
            is TaskState.Cancelled -> "已取消"
            is TaskState.Paused -> "已暂停"
            is TaskState.WaitingConflict -> "等待冲突选择"
            TaskState.Queued -> "排队中"
        }
        return TransferTaskSnapshot(id = id, title = title, subtitle = subtitle, state = s, op = request.op)
    }

    /** 顶层条目（服务端快路径按顶层操作，目录内部由协议递归处理） */
    private suspend fun rootItems(): List<Pair<VfsUri, VfsUri>> {
        val result = mutableListOf<Pair<VfsUri, VfsUri>>()
        for (src in request.sources) {
            val vfs = locator.find(src) ?: continue
            val meta = vfs.stat(src)
            result += src to request.destDir.child(meta.name)
        }
        return result
    }

    /** 冲突处理：返回实际写入目标（null = 跳过） */
    private suspend fun resolveDest(source: VfsUri, desired: VfsUri, destVfs: com.u707t.panelfm.core.vfs.VirtualFileSystem): VfsUri? {
        val existing = runCatching { destVfs.stat(desired) }.getOrNull() ?: return desired
        // 目标与源是同一个条目（例如把文件夹复制到它自己的父目录）：
        // 「覆盖」= 无操作——绝不能先删目标再拷贝，那会把源本身删掉（数据丢失）。
        val sameAsSource = source.sameMount(desired) && source.path.trimEnd('/') == desired.path.trimEnd('/')
        val policy = appliedPolicy ?: request.conflict
        val decided = when (policy) {
            ConflictPolicy.ASK -> askConflict(source, desired, existing)
            else -> policy
        }
        return when (decided) {
            ConflictPolicy.OVERWRITE -> {
                if (sameAsSource) {
                    null
                } else {
                    runCatching { destVfs.delete(listOf(desired)) }
                    desired
                }
            }
            ConflictPolicy.SKIP -> null
            ConflictPolicy.KEEP_BOTH -> keepBoth(destVfs, desired)
            ConflictPolicy.ASK -> desired
        }
    }

    private suspend fun askConflict(source: VfsUri, desired: VfsUri, existing: com.u707t.panelfm.core.vfs.FileMetadata): ConflictPolicy {
        val info = ConflictInfo(
            index = 0,
            total = 1,
            sourceName = source.name,
            sourceSize = -1,
            sourceModified = -1,
            destName = existing.name,
            destSize = existing.size,
            destModified = existing.lastModified,
            isDirectory = existing.isDirectory,
        )
        val waiter = CompletableDeferred<ConflictDecision>()
        conflictWaiter = waiter
        _state.value = TaskState.WaitingConflict(info)
        // 用户「取消任务」会 cancel() 这个 Deferred → 转成 VfsException.Cancelled，
        // 让任务状态显示「已取消」而不是「失败」
        val decision = try {
            waiter.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw VfsException.Cancelled()
        } finally {
            conflictWaiter = null
        }
        return decision.policy
    }

    private suspend fun keepBoth(destVfs: com.u707t.panelfm.core.vfs.VirtualFileSystem, desired: VfsUri): VfsUri {
        val name = desired.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (i < 1000) {
            val candidate = desired.parent?.child("$base ($i)$ext") ?: return desired
            if (!runCatching { destVfs.stat(candidate) }.isSuccess) return candidate
            i++
        }
        return desired
    }

    /** 单文件泵：offset 续传 → chunk 循环 → commit。返回 false 表示被跳过。 */
    private suspend fun transferFile(
        item: PlanItem,
        target: VfsUri,
        index: Int,
        total: Int,
        totalBytes: Long,
        doneBefore: Long,
        onDelta: suspend (Long) -> Unit,
    ): Boolean {
        val srcVfs = locator.find(item.source) ?: throw VfsException.Unsupported("源会话已关闭")
        val dstVfs = locator.find(target) ?: throw VfsException.Unsupported("目标会话已关闭")

        // 续传查找：按 (source → dest) 路径匹配（任务 id 每次都变，按 id 查永远命中不了）。
        // 同时校验源文件修改时间：源被替换（mtime 变化）时不用旧断点，避免拼出损坏文件。
        val srcMeta = runCatching { srcVfs.stat(item.source) }.getOrNull()
        val validator = srcMeta?.lastModified?.takeIf { it > 0 }?.toString()
        var startOffset = 0L
        if (dstVfs.capabilities.resumable != Resumability.NONE && item.size > 0) {
            val entry = resumeStore.findFor(item.source, target)
            if (entry != null && entry.total == item.size && entry.offset in 1 until item.size &&
                (validator == null || entry.validator == null || entry.validator == validator)
            ) {
                // 再校验 .part 的实际长度 ≥ 断点偏移：.part 被清理过 / 不完整时不能续传，
                // 否则会在偏移处直接写 → 拼出「中间有空洞」的损坏文件（旧实现无此校验）。
                val partUri = target.parent?.child("." + target.name + ".panelfm.part")
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
            reader.close()
            throw e
        }

        val writer = dstVfs.openWrite(target, size = if (item.size > 0) item.size else null, offset = startOffset)
        var written = startOffset
        val resumable = dstVfs.capabilities.resumable != Resumability.NONE
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            if (startOffset > 0) onDelta(0)
            while (true) {
                gate.checkpoint()
                val n = reader.read(buffer, 0, buffer.size)
                if (n < 0) break
                writer.write(buffer, 0, n)
                written += n
                onDelta(n.toLong())
                if (resumable) {
                    resumeStore.save(
                        ResumeEntry(
                            taskId = id, itemIndex = index, source = item.source, dest = target,
                            tempUri = null, offset = written, total = item.size,
                            validator = validator, updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
            writer.flush()
            writer.commit()
            resumeStore.clearFor(item.source, target)
            // MT「保留文件时间」：提交后把源文件的 mtime 写回目标（失败静默，不影响传输结果）
            if (preserveModifiedTime && item.lastModified > 0) {
                runCatching { dstVfs.setModified(target, item.lastModified) }
            }
            return true
        } catch (e: Exception) {
            withContext(NonCancellable) {
                if (dstVfs.capabilities.resumable != Resumability.NONE) {
                    // 可续传目标（本地 / SFTP / SMB）：保留 .part 与断点记录，下次从断点继续
                    runCatching { writer.close() }
                } else {
                    // 不可续传目标（FTP / WebDAV / S3 / 压缩包）：清理半成品
                    runCatching { writer.abort() }
                }
            }
            throw e
        } finally {
            runCatching { reader.close() }
            runCatching { writer.close() }
        }
    }

    companion object {
        const val BUFFER_SIZE = 256 * 1024
    }
}
