package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

/**
 * 目录大小懒计算。
 *
 * 三条硬约束（否则「给文件夹显示大小」会变成新的卡顿源）：
 *  1. **只算可视行**：由 `LaunchedEffect` 在行进入组合时触发，滚动中（`skip`）不启动；
 *  2. **可取消**：离开目录 / 快速滚动会让 effect 取消，BFS 每个目录检查一次取消；
 *  3. **有上限**：单目录 5 万项 / 30 秒，超限标 `complete = false`，界面显示 `> X` 而不是假精确值。
 *
 * key 带 `lastModified`：改名 / 替换 / 新增子项后旧缓存自然失效（不需要失效广播）。
 */
internal object DirSizeCache {

    data class Value(val bytes: Long, val complete: Boolean, val items: Int)

    /** 访问序 LRU，上限 2000 条（一条几十字节，内存可忽略） */
    private val map = object : LinkedHashMap<String, Value>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Value>): Boolean = size > 2000
    }

    fun key(uri: VfsUri, lastModified: Long): String = "${uri}|$lastModified"

    fun get(key: String): Value? = synchronized(map) { map[key] }

    fun put(key: String, value: Value) = synchronized(map) { map[key] = value }

    fun clear() = synchronized(map) { map.clear() }
}

/**
 * 递归统计目录：返回字节数 + 是否完整。
 *
 * 只读 `list()`，不 `stat` 子项（列表结果里已经带了 size / isDirectory，
 * 再逐项 stat 会把远端请求数翻倍）。
 */
internal suspend fun computeDirSize(
    vfs: VirtualFileSystem,
    uri: VfsUri,
    maxItems: Int = 50_000,
    maxMs: Long = 30_000,
): DirSizeCache.Value = withContext(Dispatchers.IO) {
    var bytes = 0L
    var items = 0
    var complete = true
    val started = System.currentTimeMillis()
    val queue = ArrayDeque<VfsUri>().apply { add(uri) }
    while (queue.isNotEmpty()) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (items >= maxItems || System.currentTimeMillis() - started > maxMs) {
            complete = false
            break
        }
        val dir = queue.removeFirst()
        val children = runCatching { vfs.list(dir) }.getOrNull()
        if (children == null) {
            // 单个子目录读失败：整体标不完整，但不把已统计的部分丢掉
            complete = false
            continue
        }
        for (child in children) {
            items++
            if (child.isDirectory) {
                queue.add(child.uri)
            } else if (child.size > 0) {
                bytes += child.size
            }
            if (items >= maxItems) {
                complete = false
                break
            }
        }
    }
    DirSizeCache.Value(bytes, complete, items)
}
