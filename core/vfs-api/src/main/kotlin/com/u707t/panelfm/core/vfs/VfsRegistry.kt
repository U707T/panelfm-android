package com.u707t.panelfm.core.vfs

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.model.ConnectionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 会话注册表：同一连接配置（同口令）在多个窗格 / 多个任务间共享同一个 VFS 实例，
 * 从而复用底层连接（FTP 控制连接、SFTP channel、SMB session、HTTP 连接池）。
 */
class VfsRegistry(
    private val factories: Map<String, VfsFactory>,
    private val env: VfsEnv,
    private val scope: CoroutineScope,
) {
    private class Holder(val vfs: VirtualFileSystem, val config: ConnectionConfig) {
        var refs: Int = 0
        var lastUsed: Long = System.currentTimeMillis()
        var pinned: Boolean = false
    }

    private val mutex = Mutex()
    private val holders = mutableMapOf<String, Holder>()

    fun supports(scheme: String): Boolean = factories.containsKey(scheme)

    suspend fun acquire(config: ConnectionConfig, secret: String? = null): VfsLease {
        val key = config.sessionKey
        val holder = mutex.withLock {
            holders[key]?.also {
                it.refs++
                it.lastUsed = System.currentTimeMillis()
            } ?: run {
                val factory = factories[config.scheme]
                    ?: throw VfsException.Unsupported("暂不支持的协议：${config.scheme}")
                val vfs = factory.create(config, secret, env)
                val h = Holder(vfs, config)
                h.refs = 1
                holders[key] = h
                Logx.i("VfsRegistry", "create session ${config.scheme} ${config.name}")
                h
            }
        }
        return VfsLease(holder.vfs) {
            scope.launch {
                mutex.withLock {
                    holders[key]?.let {
                        it.refs = (it.refs - 1).coerceAtLeast(0)
                        it.lastUsed = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    fun peek(config: ConnectionConfig): VirtualFileSystem? = holders[config.sessionKey]?.vfs

    fun sessions(): List<VirtualFileSystem> = holders.values.map { it.vfs }

    /** 任务运行期间 pin 住会话，避免被空闲回收 */
    suspend fun pin(config: ConnectionConfig, pinned: Boolean) {
        mutex.withLock { holders[config.sessionKey]?.pinned = pinned }
    }

    /** 回收空闲会话（默认 5 分钟） */
    suspend fun closeIdle(now: Long = System.currentTimeMillis(), idleMs: Long = 5 * 60_000L) {
        val toClose = mutex.withLock {
            val list = holders.entries
                .filter { it.value.refs <= 0 && !it.value.pinned && now - it.value.lastUsed > idleMs }
            list.forEach { holders.remove(it.key) }
            list.map { it.value }
        }
        toClose.forEach {
            runCatching { it.vfs.close() }
            Logx.i("VfsRegistry", "close idle session ${it.config.name}")
        }
    }

    suspend fun closeAll() {
        val all = mutex.withLock {
            val list = holders.values.toList()
            holders.clear()
            list
        }
        all.forEach { runCatching { it.vfs.close() } }
    }

    /** 立即关闭某个会话（「断开」/删除连接）：从注册表移除并 close()，不等空闲回收。 */
    suspend fun closeSession(sessionKey: String) {
        val holder = mutex.withLock { holders.remove(sessionKey) }
        holder?.let {
            runCatching { it.vfs.close() }
            Logx.i("VfsRegistry", "close session ${it.config.name}")
        }
    }
}

/** 使用引用计数的会话租约：use { } 结束后归还。 */
class VfsLease internal constructor(
    private val vfs: VirtualFileSystem,
    private val onRelease: () -> Unit,
) : AutoCloseable {
    fun use(): VirtualFileSystem = vfs
    override fun close() = onRelease()
}
