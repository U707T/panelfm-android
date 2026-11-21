package com.u707t.panelfm.open

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.local.LocalVolumes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

sealed interface IncomingState {
    data object Idle : IncomingState
    data class Copying(val name: String, val copied: Long, val total: Long) : IncomingState
    data class Ready(val uri: VfsUri, val name: String, val mime: String?, val size: Long) : IncomingState
    data class Error(val message: String) : IncomingState
}

/**
 * Receives one ACTION_VIEW content URI into the private incoming cache.
 * Provider operations use CancellationSignal; active descriptors are also closed on cancellation.
 */
class IncomingFileReceiver(
    private val context: Context,
    private val appDirs: AppDirs,
    private val dispatchers: PanelDispatchers,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<IncomingState>(IncomingState.Idle)
    val state: StateFlow<IncomingState> = _state

    private val generation = AtomicInteger(0)
    private val operationLock = Any()
    private val active = LinkedHashMap<Int, ActiveReceive>()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private class ReceiveOperation {
        val signal = CancellationSignal()
        private val lock = Any()
        private val resources = LinkedHashSet<Closeable>()
        @Volatile var cancelled = false
            private set

        fun attach(resource: Closeable) {
            val closeNow = synchronized(lock) {
                if (cancelled) true else {
                    resources += resource
                    false
                }
            }
            if (closeNow) runCatching { resource.close() }
        }

        fun release(resource: Closeable) {
            synchronized(lock) { resources.remove(resource) }
        }

        fun markCancelled(): List<Closeable> {
            cancelled = true
            return synchronized(lock) { resources.toList().also { resources.clear() } }
        }

        fun closeCancelledResources(close: List<Closeable>) {
            runCatching { signal.cancel() }
            close.forEach { runCatching { it.close() } }
        }

        fun finish() {
            val remaining = synchronized(lock) { resources.toList().also { resources.clear() } }
            remaining.forEach { runCatching { it.close() } }
        }
    }

    private class ActiveReceive(val generation: Int, val uri: Uri, val operation: ReceiveOperation) {
        var job: Job? = null
    }

    /** Returns true when an ACTION_VIEW intent was accepted or reported to the UI. */
    fun handle(intent: Intent?, resumePersisted: Boolean = false): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?: run {
                _state.value = IncomingState.Error("没有收到文件")
                return true
            }
        if (uri.scheme != "content") {
            _state.value = IncomingState.Error("只支持 content:// 文件（收到 ${uri.scheme}://）")
            clearPersistedRequest()
            return true
        }

        val cancelled = ArrayList<Pair<ActiveReceive, List<Closeable>>>()
        synchronized(operationLock) {
            // Configuration Activity recreation must not duplicate an active copy. A process restart
            // has an empty active map and resumes the persisted URI below.
            active.values.firstOrNull { it.uri == uri && it.job?.isActive == true }?.let {
                return true
            }
            if (resumePersisted) {
                persistedReady(uri.toString())?.let {
                    _state.value = it
                    return true
                }
                recoverRenamedTarget(uri.toString())?.let {
                    _state.value = it
                    return true
                }
            }
            val pending = active.values.filter { it.uri != uri }
            pending.forEach { old ->
                cancelled += old to old.operation.markCancelled()
            }
            // Keep cancelled operations in this map until their blocking provider call actually exits.
            cancelOutsideLock(cancelled)
            if (active.size >= MAX_IN_FLIGHT) {
                _state.value = IncomingState.Error("接收任务过多：请等待当前文件处理结束后重试")
                return true
            }
            // A fresh request must not inherit the previous URI's target/name/Ready fields. A
            // recreation of the same request keeps these fields so it can resume idempotently.
            val samePending = prefs.getString(KEY_PENDING_URI, null) == uri.toString()
            if (!(resumePersisted && samePending)) {
                val editor = prefs.edit()
                    .remove(KEY_PENDING_NAME).remove(KEY_PENDING_MIME).remove(KEY_PENDING_SIZE)
                    .remove(KEY_PENDING_TARGET).remove(KEY_READY_URI).remove(KEY_READY_NAME)
                    .remove(KEY_READY_MIME).remove(KEY_READY_SIZE)
                    .putString(KEY_PENDING_URI, uri.toString())
                if (!editor.commit()) {
                    _state.value = IncomingState.Error("无法保存接收状态，未开始读取文件")
                    return true
                }
            }
            val gen = generation.incrementAndGet()
            val operation = ReceiveOperation()
            val entry = ActiveReceive(gen, uri, operation)
            active[gen] = entry
            _state.value = IncomingState.Copying("正在读取…", 0, -1)
            entry.job = scope.launch {
                try {
                    withContext(dispatchers.io) {
                        operation.signal.throwIfCanceledCompat()
                        val (rawName, size) = queryNameAndSize(uri, operation)
                        val suppliedMime = intent.type?.takeIf { it.isNotBlank() && it != "*/*" }
                        // getType has no CancellationSignal overload. Keep this optional call inside
                        // the bounded receiver worker pool, and prefer MIME supplied by the caller.
                        val mime = suppliedMime ?: runCatching {
                            context.contentResolver.getType(uri)
                        }.getOrNull()
                        val name = displayName(rawName, uri, mime)
                        if (size > MAX_BYTES) {
                            failIfCurrent(gen, "文件超过 ${Fmt.size(MAX_BYTES)}，暂不支持直接打开")
                            return@withContext
                        }
                        copy(uri, name, mime, size, gen, operation)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OperationCanceledException) {
                    if (!entry.operation.cancelled) failIfCurrent(gen, "来源应用取消了文件读取")
                } catch (e: Exception) {
                    if (generation.get() == gen && !operation.cancelled) {
                        Logx.w("Incoming", "prepare failed: ${e.message}", e)
                        _state.value = IncomingState.Error(friendlyMessage(e))
                        clearPersistedRequest()
                    }
                } finally {
                    synchronized(operationLock) { active.remove(gen) }
                    operation.finish()
                }
            }
        }
        return true
    }

    private fun cancelOutsideLock(entries: List<Pair<ActiveReceive, List<Closeable>>>) {
        entries.forEach { (entry, resources) ->
            scope.launch(dispatchers.io) {
                entry.job?.cancel()
                entry.operation.closeCancelledResources(resources)
            }
        }
    }

    /** Cancel the provider request itself, close open descriptors, and remove its temporary copy. */
    fun cancel() {
        synchronized(operationLock) {
            generation.incrementAndGet()
            val cancellations = active.values.map { entry -> entry to entry.operation.markCancelled() }
            cancelOutsideLock(cancellations)
        }
        clearPersistedRequest()
        _state.value = IncomingState.Idle
    }

    /** Acknowledging Ready keeps its durable URI→cache mapping for Activity/process recreation. */
    fun consume() {
        if (_state.value !is IncomingState.Ready) clearPersistedRequest()
        _state.value = IncomingState.Idle
    }

    fun cleanupNow() {
        scope.launch {
            runCatching {
                withContext(dispatchers.io) { IncomingCache.cleanup(File(appDirs.incomingDir)) }
            }.onFailure { Logx.w("Incoming", "cleanup failed: ${it.message}", it) }
        }
    }

    @SuppressLint("UsableSpace")
    private suspend fun copy(
        uri: Uri,
        name: String,
        mime: String?,
        declaredSize: Long,
        gen: Int,
        operation: ReceiveOperation,
    ) {
        val dir = File(appDirs.incomingDir)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建缓存目录")
        IncomingCache.cleanup(dir)

        val persistedTarget = prefs.getString(KEY_PENDING_TARGET, null)
            ?.takeIf { prefs.getString(KEY_PENDING_URI, null) == uri.toString() }
        val target = persistedTarget?.let { File(dir, it) }
            ?.takeIf { it.parentFile == dir && !it.exists() }
            ?: uniqueIncomingTarget(dir, name)
        val part = File(dir, "${target.name}.${gen}$PART_SUFFIX")
        if (!prefs.edit().putString(KEY_PENDING_NAME, name).putString(KEY_PENDING_MIME, mime)
                .putLong(KEY_PENDING_SIZE, declaredSize).putString(KEY_PENDING_TARGET, target.name).commit()
        ) throw IOException("无法保存接收文件状态")

        var copied = 0L
        try {
            val initialFree = dir.usableSpace
            if (initialFree <= 0L) {
                throw IOException("无法确认缓存可用空间，已停止接收以保护设备存储")
            }
            if (declaredSize > 0 && declaredSize + MIN_FREE_BYTES > initialFree) {
                throw IOException("缓存空间不足：需要 ${Fmt.size(declaredSize)}，可用 ${Fmt.size(initialFree)}")
            }
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r", operation.signal)
                ?: throw IOException("无法读取文件")
            operation.attach(descriptor)
            try {
                val input = FileInputStream(descriptor.fileDescriptor)
                operation.attach(input)
                try {
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var lastReport = 0L
                        var zeroReads = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (operation.cancelled) throw CancellationException("incoming copy cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) {
                                if (++zeroReads >= MAX_EMPTY_READS) throw IOException("来源应用连续返回空数据")
                                continue
                            }
                            zeroReads = 0
                            copied += count
                            if (copied > MAX_BYTES) throw IOException("文件超过 ${Fmt.size(MAX_BYTES)}，暂不支持直接打开")
                            // The declared metadata may be wrong or malicious. Check current free space
                            // on every chunk; usableSpace already accounts for bytes written so far.
                            val freeNow = dir.usableSpace
                            if (freeNow > 0 && count.toLong() + MIN_FREE_BYTES > freeNow) {
                                throw IOException("缓存空间不足：至少保留 ${Fmt.size(MIN_FREE_BYTES)} 可用空间")
                            }
                            output.write(buffer, 0, count)
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= REPORT_MS && generation.get() == gen) {
                                lastReport = now
                                _state.value = IncomingState.Copying(target.name, copied, declaredSize)
                            }
                        }
                        if (declaredSize > 0 && copied != declaredSize) {
                            throw IOException("来源应用报告 ${Fmt.size(declaredSize)}，实际读取 ${Fmt.size(copied)}；文件可能不完整")
                        }
                        output.flush()
                        output.fd.sync()
                    }
                } finally {
                    operation.release(input)
                    runCatching { input.close() }
                }
            } finally {
                operation.release(descriptor)
                runCatching { descriptor.close() }
            }

            currentCoroutineContext().ensureActive()
            if (generation.get() != gen || operation.cancelled) throw CancellationException("incoming copy cancelled")
            if (!moveNoReplace(part, target)) throw IOException("无法保存接收文件（目标冲突或 rename 失败）")
            if (generation.get() != gen || operation.cancelled) {
                target.delete()
                throw CancellationException("incoming copy cancelled")
            }
            val ready = IncomingState.Ready(
                uri = VfsUri.of("local", LocalVolumes.AUTHORITY_INCOMING, "/${target.name}"),
                name = target.name,
                mime = mime,
                size = target.length(),
            )
            val saved = prefs.edit()
                .putString(KEY_READY_URI, uri.toString())
                .putString(KEY_READY_NAME, ready.name)
                .putString(KEY_READY_MIME, ready.mime)
                .putLong(KEY_READY_SIZE, ready.size)
                .commit()
            if (!saved) {
                target.delete()
                throw IOException("无法保存接收完成状态")
            }
            if (generation.get() == gen) _state.value = ready
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            if (generation.get() == gen && !operation.cancelled) {
                Logx.w("Incoming", "copy failed: ${e.message}", e)
                _state.value = IncomingState.Error(friendlyMessage(e))
                clearPersistedRequest()
            }
        }
    }

    private fun queryNameAndSize(uri: Uri, operation: ReceiveOperation): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
            operation.signal,
        ) ?: return null to size
        operation.attach(cursor)
        try {
            cursor.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !it.isNull(nameIndex)) name = it.getString(nameIndex)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
                }
            }
        } finally {
            operation.release(cursor)
            runCatching { cursor.close() }
        }
        return name to size
    }

    private fun persistedReady(requestUri: String): IncomingState.Ready? {
        if (prefs.getString(KEY_READY_URI, null) != requestUri) return null
        val name = prefs.getString(KEY_READY_NAME, null) ?: return null
        val file = File(appDirs.incomingDir, name)
        if (!file.isFile) return null
        return IncomingState.Ready(
            VfsUri.of("local", LocalVolumes.AUTHORITY_INCOMING, "/${file.name}"),
            name,
            prefs.getString(KEY_READY_MIME, null),
            file.length(),
        )
    }

    private fun recoverRenamedTarget(requestUri: String): IncomingState.Ready? {
        if (prefs.getString(KEY_PENDING_URI, null) != requestUri) return null
        val name = prefs.getString(KEY_PENDING_TARGET, null) ?: return null
        val target = File(appDirs.incomingDir, name)
        if (target.parentFile != File(appDirs.incomingDir) || !target.isFile) return null
        // rename is the final data operation. If the process died before writing the Ready marker,
        // absence of this request's .part file means the completed cache object can be replayed.
        val hasPart = target.parentFile?.listFiles()?.any {
            it.name.startsWith(target.name + ".") && it.name.endsWith(PART_SUFFIX)
        } == true
        if (hasPart) return null
        val ready = IncomingState.Ready(
            VfsUri.of("local", LocalVolumes.AUTHORITY_INCOMING, "/${target.name}"),
            target.name,
            prefs.getString(KEY_PENDING_MIME, null),
            target.length(),
        )
        prefs.edit().putString(KEY_READY_URI, requestUri).putString(KEY_READY_NAME, ready.name)
            .putString(KEY_READY_MIME, ready.mime).putLong(KEY_READY_SIZE, ready.size).commit()
        return ready
    }

    private fun failIfCurrent(gen: Int, message: String) {
        if (generation.get() == gen) {
            _state.value = IncomingState.Error(message)
            clearPersistedRequest()
        }
    }

    private fun clearPersistedRequest() {
        prefs.edit().remove(KEY_PENDING_URI).remove(KEY_PENDING_NAME).remove(KEY_PENDING_MIME)
            .remove(KEY_PENDING_SIZE).remove(KEY_PENDING_TARGET)
            .remove(KEY_READY_URI).remove(KEY_READY_NAME).remove(KEY_READY_MIME).remove(KEY_READY_SIZE)
            .commit()
    }

    private fun displayName(raw: String?, uri: Uri, mime: String?): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "incoming-${System.currentTimeMillis()}"
        val sanitized = sanitizeIncomingName(raw?.takeIf { it.isNotBlank() } ?: fallback)
        val dot = sanitized.lastIndexOf('.')
        if (dot > 0 && sanitized.substring(dot + 1).isNotBlank()) return sanitized
        val extension = mime?.takeIf { it.isNotBlank() && it != "*/*" }
            ?.let { runCatching { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        return if (extension == null) sanitized else "$sanitized.$extension"
    }

    private fun friendlyMessage(e: Exception): String = when (e) {
        is SecurityException -> "没有读取权限（来源应用未授权）"
        is java.io.FileNotFoundException -> "文件不存在或已被移动"
        else -> e.message ?: "接收失败"
    }

    private class CancellationSignalCompat

    private fun CancellationSignal.throwIfCanceledCompat() {
        if (isCanceled) throw OperationCanceledException()
    }

    private fun moveNoReplace(source: File, destination: File): Boolean = try {
        if (destination.exists()) false else {
            try {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), destination.toPath())
            }
            true
        }
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val PREFS_NAME = "incoming_view_state"
        const val KEY_PENDING_URI = "pending_uri"
        const val KEY_PENDING_NAME = "pending_name"
        const val KEY_PENDING_MIME = "pending_mime"
        const val KEY_PENDING_SIZE = "pending_size"
        const val KEY_PENDING_TARGET = "pending_target"
        const val KEY_READY_URI = "ready_uri"
        const val KEY_READY_NAME = "ready_name"
        const val KEY_READY_MIME = "ready_mime"
        const val KEY_READY_SIZE = "ready_size"
        const val PART_SUFFIX = ".part"
        const val BUFFER_BYTES = 256 * 1024
        const val REPORT_MS = 200L
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024
        const val MIN_FREE_BYTES = 64L * 1024 * 1024
        const val MAX_EMPTY_READS = 3
        const val MAX_IN_FLIGHT = 2
    }
}
