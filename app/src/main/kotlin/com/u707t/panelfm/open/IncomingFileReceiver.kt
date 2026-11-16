package com.u707t.panelfm.open

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「用其他应用打开」收到的文件状态。
 *
 * 只做 `ACTION_VIEW` + `content://` + 单文件：这是系统「打开方式」的真实形态；
 * `ACTION_SEND` 多文件（分享面板）需要单独的接收列表 UI，不在 v1 范围内。
 */
sealed interface IncomingState {
    data object Idle : IncomingState

    /** 正在复制到缓存。`total <= 0` 表示提供方没给大小。 */
    data class Copying(val name: String, val copied: Long, val total: Long) : IncomingState

    /** 已落到 `local://incoming/...`，可以交给现有预览链路。 */
    data class Ready(val uri: VfsUri, val name: String, val mime: String?, val size: Long) : IncomingState

    data class Error(val message: String) : IncomingState
}

/**
 * 外部 `ACTION_VIEW` → 缓存 → 本地 VFS URI。
 *
 * 为什么复制而不是直接读 `content://`：
 *  - `content://` 的读授权绑定在接收 Activity 上，且 `ContentResolver` 不保证随机读；
 *    直接接进 VFS 要在图片/视频/PDF/Office/编辑器五条链路各写一套 content 适配。
 *  - 复制到 `cache/incoming` 后就是一个普通本地文件，现有预览全部原样复用。
 *
 * 代价与边界（v1 明确接受）：
 *  - 单文件、≤ 2 GB；超过直接提示，不做流式；
 *  - 复制期间可取消；缓存 7 天 / 512 MB 上限，启动与接收新文件时顺手清理；
 *  - 编辑只改缓存副本，不回写源 App（状态条会提示）。
 */
class IncomingFileReceiver(
    private val context: Context,
    private val appDirs: AppDirs,
    private val dispatchers: PanelDispatchers,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<IncomingState>(IncomingState.Idle)
    val state: StateFlow<IncomingState> = _state

    /** 代际号：取消 / 新 intent 到达时让旧协程的结果失效，避免旧任务把新状态覆盖掉。 */
    private val generation = AtomicInteger(0)
    private var job: Job? = null

    /**
     * 处理 Activity 收到的 intent。非 `ACTION_VIEW` 直接忽略
     * （launcher 的 MAIN、通知的 CLEAR_TOP 都会走到这里）。
     */
    fun handle(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?: run {
                _state.value = IncomingState.Error("没有收到文件")
                return
            }
        if (uri.scheme != "content") {
            _state.value = IncomingState.Error("只支持 content:// 文件（收到 ${uri.scheme}://）")
            return
        }

        val gen = generation.incrementAndGet()
        job?.cancel()
        // 先给一个同步可见的状态：冷启动时 AppRoot 才能在首帧把「正在接收」画出来，
        // 也避免「恢复上次预览」和本次 intent 同时弹两个页面。
        _state.value = IncomingState.Copying("正在读取…", 0, -1)
        job = scope.launch {
            try {
                withContext(dispatchers.io) {
                    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                        ?: intent.type
                    val (rawName, size) = queryNameAndSize(uri)
                    val name = displayName(rawName, uri, mime)
                    if (size > MAX_BYTES) {
                        if (generation.get() == gen) {
                            _state.value = IncomingState.Error(
                                "文件超过 ${Fmt.size(MAX_BYTES)}，暂不支持直接打开",
                            )
                        }
                        return@withContext
                    }
                    copy(uri, name, mime, size, gen)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation.get() == gen) {
                    Logx.w("Incoming", "prepare failed: ${e.message}", e)
                    _state.value = IncomingState.Error(friendlyMessage(e))
                }
            }
        }
    }

    /** 用户取消复制：旧协程会自己删掉 `.part`，这里只负责让 UI 立刻回到 Idle。 */
    fun cancel() {
        generation.incrementAndGet()
        job?.cancel()
        job = null
        _state.value = IncomingState.Idle
    }

    /** UI 消费掉 Ready / Error 后调用，避免重组时重复弹预览。 */
    fun consume() {
        _state.value = IncomingState.Idle
    }

    /**
     * 启动时顺手清一次 `cache/incoming`（7 天 / 512 MB / `.part` 残片）。
     * 后台执行，不阻塞首帧；失败只记日志。
     */
    fun cleanupNow() {
        scope.launch {
            runCatching {
                withContext(dispatchers.io) {
                    IncomingCache.cleanup(File(appDirs.incomingDir))
                }
            }.onFailure { Logx.w("Incoming", "cleanup failed: ${it.message}", it) }
        }
    }

    // ------------------------------------------------------------------ 复制

    // 缓存卷就是用来装临时文件的：`usableSpace` 足够判断「这次复制会不会把缓存撑爆」；
    // 不引入 StorageManager#getAllocatableBytes（它会尝试清理别的缓存，副作用超出本功能）。
    @SuppressLint("UsableSpace")
    private suspend fun copy(uri: Uri, name: String, mime: String?, size: Long, gen: Int) {
        val dir = File(appDirs.incomingDir)
        if (!dir.exists() && !dir.mkdirs()) {
            _state.value = IncomingState.Error("无法创建缓存目录")
            return
        }
        IncomingCache.cleanup(dir)

        val target = uniqueIncomingTarget(dir, name)
        // 代际号进 .part 名：连续两次打开时，旧协程可能还没退出；
        // 共用同一个 .part 会让两个协程写同一文件，旧协程的清理还会删掉新协程的进度。
        val part = File(dir, "${target.name}.$gen$PART_SUFFIX")
        var copied = 0L
        try {
            val usable = dir.usableSpace
            if (size > 0 && usable > 0 && size + MIN_FREE_BYTES > usable) {
                _state.value = IncomingState.Error(
                    "缓存空间不足：需要 ${Fmt.size(size)}，可用 ${Fmt.size(usable)}",
                )
                return
            }

            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("无法读取文件")
            input.use { ins ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(BUFFER_BYTES)
                    var lastReport = 0L
                    var zeroReads = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n == 0) {
                            // 坏 provider 可能永远返回 0：不能空转（与搜索链路的 0 读保护同口径）。
                            if (++zeroReads >= 3) break
                            continue
                        }
                        zeroReads = 0
                        copied += n
                        if (copied > MAX_BYTES) {
                            throw IOException("文件超过 ${Fmt.size(MAX_BYTES)}，暂不支持直接打开")
                        }
                        if (size <= 0 && usable > 0 && copied + MIN_FREE_BYTES > usable) {
                            throw IOException("缓存空间不足")
                        }
                        out.write(buf, 0, n)
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= REPORT_MS) {
                            lastReport = now
                            if (generation.get() == gen) {
                                _state.value = IncomingState.Copying(target.name, copied, size)
                            }
                        }
                    }
                    out.flush()
                }
            }

            currentCoroutineContext().ensureActive()
            if (generation.get() != gen) {
                part.delete()
                return
            }
            // 目标名是在复制开始前选的；复制期间可能被另一笔接收抢先落位。
            // rename 失败就重新取一个不冲突的名字再试一次，而不是把用户这次打开直接判失败。
            var finalTarget = target
            if (!part.renameTo(finalTarget)) {
                finalTarget = uniqueIncomingTarget(dir, name)
                if (!part.renameTo(finalTarget)) {
                    throw IOException("无法保存接收文件（rename 失败）")
                }
            }
            if (generation.get() != gen) {
                finalTarget.delete()
                return
            }
            _state.value = IncomingState.Ready(
                uri = VfsUri.of("local", LocalVolumes.AUTHORITY_INCOMING, "/${finalTarget.name}"),
                name = finalTarget.name,
                mime = mime,
                size = finalTarget.length(),
            )
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            if (generation.get() == gen) {
                Logx.w("Incoming", "copy failed: ${e.message}", e)
                _state.value = IncomingState.Error(friendlyMessage(e))
            }
        }
    }

    /** 把 `Permission Denial…` / `FileNotFoundException…` 这类系统文案换成用户能行动的说明。 */
    private fun friendlyMessage(e: Exception): String = when (e) {
        is SecurityException -> "没有读取权限（来源应用未授权）"
        is java.io.FileNotFoundException -> "文件不存在或已被移动"
        else -> e.message ?: "接收失败"
    }

    // ------------------------------------------------------------------ ContentResolver 元数据

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        return name to size
    }

    /**
     * 显示名 + 扩展名补全。
     *
     * 有些 provider 的 `DISPLAY_NAME` 没有后缀（例如 `document`），但 MIME 是 `application/pdf`。
     * 不补后缀会让 `PreviewScreen` 的 AUTO 分派退化成编辑器（二进制乱码），所以这里按 MIME 补一次。
     */
    private fun displayName(raw: String?, uri: Uri, mime: String?): String {
        val fallback = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "incoming-${System.currentTimeMillis()}"
        val sanitized = sanitizeIncomingName(raw?.takeIf { it.isNotBlank() } ?: fallback)
        val dot = sanitized.lastIndexOf('.')
        if (dot > 0 && sanitized.substring(dot + 1).isNotBlank()) return sanitized
        val ext = mime
            ?.takeIf { it.isNotBlank() && it != "*/*" }
            ?.let { runCatching { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        return if (ext == null) sanitized else "$sanitized.$ext"
    }

    // ------------------------------------------------------------------ 缓存清理

    private companion object {
        const val PART_SUFFIX = ".part"
        const val BUFFER_BYTES = 256 * 1024
        const val REPORT_MS = 200L
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024
        const val MIN_FREE_BYTES = 64L * 1024 * 1024
    }
}
