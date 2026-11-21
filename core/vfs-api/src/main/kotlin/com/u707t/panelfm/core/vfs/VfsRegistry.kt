package com.u707t.panelfm.core.vfs

import com.u707t.panelfm.core.common.toHex

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.model.ConnectionConfig
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话注册表：同一连接配置（同口令）在多个窗格 / 多个任务间共享同一个 VFS 实例，
 * 从而复用底层连接（FTP 控制连接、SFTP channel、SMB session、HTTP 连接池）。
 */
class VfsRegistry(
    private val factories: Map<String, VfsFactory>,
    private val env: VfsEnv,
    @Suppress("unused") private val scope: CoroutineScope,
) {
    private class Holder(val vfs: VirtualFileSystem, val config: ConnectionConfig) {
        var refs: Int = 0
        var lastUsed: Long = System.currentTimeMillis()
        var pinned: Boolean = false
    }

    private val lock = Any()
    // peek()/sessions() 由 UI 与后台任务并发读取；不能用普通 MutableMap。
    private val holders = ConcurrentHashMap<String, Holder>()
    private data class Activation(val holderKey: String, val token: Long)
    private val active = ConcurrentHashMap<String, Activation>()
    private val activationSequence = java.util.concurrent.atomic.AtomicLong(0)

    private fun connectionKey(config: ConnectionConfig): String =
        if (config.id > 0) "id:${config.id}" else "config:${config.sessionKey}"

    fun supports(scheme: String): Boolean = factories.containsKey(scheme)

    suspend fun acquire(config: ConnectionConfig, secret: String? = null): VfsLease {
        // Android 17（API 37）局域网授权：未授权时网络协议直接给出可执行文案
        //（此前该字段传入后零消费，只会在系统层表现为「连接超时」）
        if (!env.localNetworkAllowed()) {
            throw VfsException.Network(
                VfsException.Network.Kind.LOCAL_NETWORK_DENIED,
                "系统未允许访问局域网（Android 17 需要单独授权）",
            )
        }
        // secret 不在 ConnectionConfig.sessionKey 中；必须参与会话隔离，
        // 否则用户修改密码后会复用旧的已认证连接。
        val key = sessionKey(config, secret)
        val holder = synchronized(lock) {
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
            // 引用计数只改内存且与 acquire/closeConnection 共用短锁；不得延迟到后台作用域，
            // 否则同步断开后 closeConnection 会看到过期 refs 并留下旧会话。
            synchronized(lock) {
                holders[key]?.takeIf { it === holder }?.let {
                    it.refs = (it.refs - 1).coerceAtLeast(0)
                    it.lastUsed = System.currentTimeMillis()
                }
            }
        }
    }

    /** 正式连接 connect 成功后显式激活；测试连接 acquire 不得调用此方法。 */
    fun activate(config: ConnectionConfig, lease: VfsLease): Boolean = synchronized(lock) {
        val entry = holders.entries.firstOrNull {
            it.value.vfs === lease.use() && it.value.config.sessionKey == config.sessionKey
        } ?: return@synchronized false
        active[connectionKey(config)] = Activation(entry.key, activationSequence.incrementAndGet())
        true
    }

    /** 无凭据查找只返回显式激活的身份，或唯一且无歧义的 holder。 */
    fun peek(config: ConnectionConfig): VirtualFileSystem? = synchronized(lock) {
        active[connectionKey(config)]?.let { activation ->
            val holder = holders[activation.holderKey]
            if (holder != null && holder.config.sessionKey == config.sessionKey) return@synchronized holder.vfs
            active.remove(connectionKey(config), activation)
        }
        if (config.id <= 0) {
            holders.values.filter { it.config.sessionKey == config.sessionKey }.singleOrNull()?.vfs
        } else null
    }

    /** 有凭据的调用方可精确查找，不更新正式连接的激活身份。 */
    fun peek(config: ConnectionConfig, secret: String?): VirtualFileSystem? = synchronized(lock) {
        holders[sessionKey(config, secret)]?.vfs
    }

    fun sessions(): List<VirtualFileSystem> = holders.values.map { it.vfs }

    /** 任务运行期间 pin 住会话，避免被空闲回收 */
    suspend fun pin(config: ConnectionConfig, pinned: Boolean) {
        synchronized(lock) {
            holders.values
                .filter { it.config.sessionKey == config.sessionKey }
                .forEach { it.pinned = pinned }
        }
    }

    /** 回收空闲会话（默认 5 分钟） */
    suspend fun closeIdle(now: Long = System.currentTimeMillis(), idleMs: Long = 5 * 60_000L) {
        val toClose = synchronized(lock) {
            val list = holders.entries
                .filter { it.value.refs <= 0 && !it.value.pinned && active.values.none { a -> a.holderKey == it.key } && now - it.value.lastUsed > idleMs }
            list.forEach { holders.remove(it.key) }
            list.map { it.value }
        }
        toClose.forEach {
            runCatching { it.vfs.close() }
            Logx.i("VfsRegistry", "close idle session ${it.config.name}")
        }
    }

    suspend fun closeAll() {
        val all = synchronized(lock) {
            val list = holders.values.toList()
            holders.clear()
            active.clear()
            list
        }
        all.forEach { runCatching { it.vfs.close() } }
    }

    /** 在发起异步断开之前捕获；之后重新激活的连接不受旧请求影响。 */
    fun activationToken(config: ConnectionConfig): Long? = active[connectionKey(config)]?.token

    suspend fun closeConnection(config: ConnectionConfig) = closeConnection(config, activationToken(config))

    /** 仅关闭调用方观察到的激活代际；另一连接仍激活同 holder 时保留它。 */
    suspend fun closeConnection(config: ConnectionConfig, expectedActivation: Long?) {
        val toClose = synchronized(lock) {
            val key = connectionKey(config)
            val current = active[key] ?: return@synchronized emptyList<Holder>()
            val holder = holders[current.holderKey] ?: return@synchronized emptyList<Holder>()
            if (current.token != expectedActivation || holder.config.sessionKey != config.sessionKey) {
                return@synchronized emptyList<Holder>()
            }
            if (!active.remove(key, current)) return@synchronized emptyList<Holder>()
            if (holder.refs > 0 || holder.pinned || active.values.any { it.holderKey == current.holderKey }) {
                return@synchronized emptyList<Holder>()
            }
            holders.remove(current.holderKey)
            listOf(holder)
        }
        toClose.forEach { runCatching { it.vfs.close() } }
    }

    /** 立即关闭某个会话（「断开」/删除连接）：从注册表移除并 close()，不等空闲回收。 */
    suspend fun closeSession(sessionKey: String) {
        val holdersToClose = synchronized(lock) {
            val removed = holders.entries
                .filter { it.value.config.sessionKey == sessionKey }
                .mapNotNull { entry -> entry.key to holders.remove(entry.key) }
            val keys = removed.map { it.first }.toSet()
            active.entries.removeIf { it.value.holderKey in keys }
            removed.mapNotNull { it.second }
        }
        holdersToClose.forEach {
            runCatching { it.vfs.close() }
            Logx.i("VfsRegistry", "close session ${it.config.name}")
        }
    }

    private fun sessionKey(config: ConnectionConfig, secret: String?): String {
        val bytes = (secret ?: "").toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        // Locale.ROOT：部分 locale（阿拉伯语等）会把 %x 的数字本地化成非 ASCII 字符，
        // 指纹会随系统语言变化 → 会话 key 抖动、复用判断失效。哈希串必须固定 ASCII。
        val fingerprint = digest.toHex()
        return "${config.sessionKey}|secret=$fingerprint"
    }
}

/** 使用引用计数的会话租约：use { } 结束后归还。 */
class VfsLease internal constructor(
    private val vfs: VirtualFileSystem,
    private val onRelease: () -> Unit,
) : AutoCloseable {
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)
    fun use(): VirtualFileSystem = vfs
    override fun close() { if (released.compareAndSet(false, true)) onRelease() }
}
