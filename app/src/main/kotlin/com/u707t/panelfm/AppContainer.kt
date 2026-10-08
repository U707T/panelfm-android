package com.u707t.panelfm

import android.app.Application
import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.data.AppSettings
import com.u707t.panelfm.core.data.BookmarkDao
import com.u707t.panelfm.core.data.ConnectionDao
import com.u707t.panelfm.core.data.HostKeyDao
import com.u707t.panelfm.core.data.PanelDb
import com.u707t.panelfm.core.data.PrefsStore
import com.u707t.panelfm.core.data.PreviewPrefDao
import com.u707t.panelfm.core.data.ResumeDao
import com.u707t.panelfm.core.data.SecretStore
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.transfer.FileOperationPlanner
import com.u707t.panelfm.core.transfer.TransferEngine
import com.u707t.panelfm.core.transfer.isActive
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsRegistry
import com.u707t.panelfm.core.vfs.local.LocalVfs
import com.u707t.panelfm.core.vfs.ftp.FtpVfsFactory
import com.u707t.panelfm.core.vfs.archive.ArchiveVfs
import com.u707t.panelfm.core.vfs.s3.S3Vfs
import com.u707t.panelfm.core.vfs.sftp.SftpVfs
import com.u707t.panelfm.core.vfs.smb.SmbVfs
import com.u707t.panelfm.core.vfs.webdav.WebDavVfsFactory
import com.u707t.panelfm.tools.RemoteHttpServer
import com.u707t.panelfm.tools.TrashService
import com.u707t.panelfm.service.TransferService
import com.u707t.panelfm.ui.browser.BrowserController
import com.u707t.panelfm.ui.browser.ThumbCache
import com.u707t.panelfm.ui.preview.VfsDataSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用级容器（手工 DI，零注解处理器）：
 * 调度器 / 目录 / 持久化 / VFS 会话注册表 / 传输引擎 / 双列控制器。
 */
class AppContainer(val app: Application) {

    val dispatchers = PanelDispatchers()

    val appDirs: AppDirs = AppDirs(app.filesDir.absolutePath, app.cacheDir.absolutePath).also { it.ensure() }

    val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    val env: VfsEnv = VfsEnv(
        appDirs = appDirs,
        dispatchers = dispatchers,
        // 全局 UA 走 lambda：设置里改完即时生效（旧实现固定 "0.1"，改设置无效果）
        userAgent = { _settings.value.userAgent },
        timeoutMs = 30_000L,
        localNetworkAllowed = { LocalNetwork.isGranted(app) },
        // 全局「默认信任自签证书」：只作为连接未显式设置时的兜底
        trustSelfSignedDefault = { _settings.value.trustSelfSigned },
    )

    // ---------------------------------------------------------------- 持久化

    val db = PanelDb(app)
    val connectionDao = ConnectionDao(db)
    val bookmarkDao = BookmarkDao(db)
    val resumeDao = ResumeDao(db)
    val secretStore = SecretStore(db)
    val previewPrefDao = PreviewPrefDao(db)
    val hostKeyDao = HostKeyDao(db)
    val prefs = PrefsStore(app)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    private val _connections = MutableStateFlow<List<ConnectionConfig>>(emptyList())
    val connections: StateFlow<List<ConnectionConfig>> = _connections

    // ---------------------------------------------------------------- VFS

    val localVfs = LocalVfs(env)

    val registry = VfsRegistry(
        factories = buildMap {
            put("dav", WebDavVfsFactory())
            put("ftp", FtpVfsFactory("ftp"))
            put("ftps", FtpVfsFactory("ftps"))
            put("sftp", SftpVfs.Factory(hostKeyDao))
            put("smb", SmbVfs.Factory())
            put("s3", S3Vfs.Factory())
        },
        env = env,
        scope = scope,
    )

    val locator = SessionLocator(this)

    private val mounted = ConcurrentHashMap<String, com.u707t.panelfm.core.vfs.VirtualFileSystem>()

    /** 应用级持有的会话租约：只要 App 还引用着该连接，就不被空闲回收器关掉 */
    private val heldLeases = ConcurrentHashMap<String, com.u707t.panelfm.core.vfs.VfsLease>()

    /** 已挂载的压缩包（hostUri → ArchiveVfs） */
    private val archives = ConcurrentHashMap<String, ArchiveVfs>()

    /**
     * 正在挂载中的压缩包（审计 U4）：同一 host 的并发请求共享同一个挂载任务。
     * 解决的问题：远程包下载期间没有任何反馈，用户连点两次会**并发下载、并发写同一个临时文件**，
     * 可能写出坏缓存。现在第二个调用直接 await 第一个的结果。
     */
    private val openingArchives = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<ArchiveVfs>>()

    val planner = FileOperationPlanner(locator)

    val engine = TransferEngine(
        planner = planner,
        locator = locator,
        resumeStore = resumeDao,
        dispatchers = dispatchers,
        scope = scope,
    )

    /**
     * 在 [parent] 下取一个不冲突的子目录名（MT 行为：同名自动加 (1)(2)…）。
     * 用于「解压到单独的文件夹」，避免直接覆盖已有目录。
     */
    suspend fun uniqueChild(parent: com.u707t.panelfm.core.vfs.VfsUri, baseName: String): com.u707t.panelfm.core.vfs.VfsUri {
        val vfs = locator.find(parent) ?: return parent.child(baseName)
        val existing = runCatching { vfs.list(parent).map { it.name }.toSet() }.getOrDefault(emptySet())
        if (baseName !in existing) return parent.child(baseName)
        var i = 1
        while (true) {
            val candidate = "$baseName ($i)"
            if (candidate !in existing) return parent.child(candidate)
            i++
        }
    }

    val browser = BrowserController(this)

    /** 本地回收站（删除 → 回收站，可还原） */
    val trash = TrashService(appDirs, localVfs)

    /** 远程管理：内置只读 HTTP 服务 */
    val remote = RemoteHttpServer(locator)

    /** Media3 播放用的统一 VFS 数据源（本地/SFTP/WebDAV/SMB/S3 通吃） */
    @androidx.media3.common.util.UnstableApi
    val vfsDataSourceFactory = VfsDataSourceFactory(locator, app, resolver = { resolveSession(it) })

    init {
        ThumbCache.init(appDirs.thumbsDir)
        scope.launch {
            var concurrencyApplied = false
            prefs.settings.collect { s ->
                _settings.value = s
                // 并发数必须在这里落地：设置页只在「点按钮那一刻」调 updateConcurrency，
                // 冷启动不补这一步的话，用户选的 1/3/4 会在重启后悄悄回到默认 2。
                if (!concurrencyApplied) {
                    concurrencyApplied = true
                    engine.updateConcurrency(s.maxConcurrentTasks)
                }
            }
        }
        // 任务运行时启用前台服务通知（M9）；用 taskEvents 才能观察到任务的开始/结束
        scope.launch {
            var foregound = false
            engine.taskEvents.collect { tasks ->
                val active = tasks.any { it.state.value.isActive }
                if (active && !foregound) {
                    foregound = true
                    TransferService.start(app)
                } else if (!active && foregound) {
                    foregound = false
                    TransferService.stop(app)
                }
            }
        }
        scope.launch { reloadConnections() }
        // 清理过期断点续传记录（取消/失败留下的记录超过 7 天即丢弃）
        scope.launch {
            runCatching { resumeDao.purgeStale(System.currentTimeMillis() - 7L * 24 * 3600 * 1000) }
        }
        // 定期回收空闲网络会话
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                runCatching { registry.closeIdle() }
            }
        }
    }

    suspend fun reloadConnections() {
        val list = withContext(Dispatchers.IO) { connectionDao.all() }
        _connections.value = list
    }

    fun connectionOf(id: Long?): ConnectionConfig? = id?.let { cid -> connections.value.firstOrNull { it.id == cid } }

    fun connectionByAuthority(scheme: String, authority: String): ConnectionConfig? =
        connections.value.firstOrNull { it.scheme == scheme && "${it.host}:${it.port}" == authority }

    /**
     * 由一个 VFS URI 反查它属于哪个连接配置。
     *
     * 三级匹配，覆盖所有 URI 形态：
     *  1. `?c=<connectionId>`（最精确，支持同主机多账号）；
     *  2. `authority == host:port`（WebDAV / SFTP / FTP / SMB）；
     *  3. S3 的 authority 是 **bucket 名**，不是 host:port —— 先按连接的默认 bucket 匹配，
     *     只有一个 S3 连接时直接采用它。
     */
    fun connectionForUri(uri: com.u707t.panelfm.core.vfs.VfsUri): ConnectionConfig? {
        connectionOf(com.u707t.panelfm.core.vfs.VfsUris.connectionId(uri))?.let { return it }
        connectionByAuthority(uri.scheme, uri.authority)?.let { return it }
        val sameScheme = connections.value.filter { it.scheme == uri.scheme }
        sameScheme.firstOrNull { it.option(com.u707t.panelfm.core.vfs.s3.S3Config.OPT_BUCKET) == uri.authority }
            ?.let { return it }
        return sameScheme.singleOrNull()
    }

    /**
     * 在当前**活着**的会话里找 URI 所属的 VFS。
     *
     * 为什么要有这条：`mounted` 表只是历史缓存（会话被空闲回收 / 断开后不会自动清理），
     * 单靠它会返回一个已经死掉的实例；而 URI 丢了 `?c=` 时（历史书签、旧路径记录、
     * 解析层丢掉 query）又没法用连接号定位。这里直接以「注册表里真实存在的会话」为准。
     */
    fun liveSessionFor(uri: com.u707t.panelfm.core.vfs.VfsUri): com.u707t.panelfm.core.vfs.VirtualFileSystem? {
        registry.peek(connectionForUri(uri) ?: return null)?.let { return it }
        // 同 scheme 且只有一个活会话时（典型是单 S3 / 单 WebDAV），直接采信
        val sameScheme = connections.value.filter { it.scheme == uri.scheme }
            .mapNotNull { c -> registry.peek(c)?.let { c to it } }
        if (sameScheme.size == 1) return sameScheme.first().second
        return null
    }

    /**
     * 解析 URI 所属会话；**找不到时自动重连一次**。
     *
     * 这是「存储会话不可用（请重新打开该存储）」的正解：把「请用户手动重开存储」
     * 变成「应用自己重连」。重连失败才把原因交给上层展示。
     */
    suspend fun resolveSession(uri: com.u707t.panelfm.core.vfs.VfsUri): com.u707t.panelfm.core.vfs.VirtualFileSystem? {
        locator.find(uri)?.let { return it }
        liveSessionFor(uri)?.let { return it }
        val config = connectionForUri(uri) ?: return null
        return runCatching {
            openConnection(config)
            locator.find(uri) ?: liveSessionFor(uri)
        }.getOrNull()
    }

    /** 打开（或复用）一个网络连接，返回可用的 VFS 实例；secretOverride 用于「测试连接」尚未落库的口令 */
    suspend fun openConnection(
        config: ConnectionConfig,
        secretOverride: String? = null,
    ): com.u707t.panelfm.core.vfs.VirtualFileSystem {
        val secret = secretOverride
            ?: withContext(Dispatchers.IO) { secretStore.get(connectionDao.secretRef(config.id)) }
        val lease = registry.acquire(config, secret)
        val vfs = lease.use()
        // 第 8 批 🔵3：先连接、成功后才登记。旧实现在 connect() 之前就把租约登记进 heldLeases ——
        // 连接失败后该会话仍被当作「App 正在引用」持有（refs≥1，空闲回收器永不回收），
        // mounted 也指向一个从未连接成功的实例；账实不符。
        try {
            vfs.connect()
        } catch (t: Throwable) {
            runCatching { lease.close() }
            throw t
        }
        // 关键：应用要一直持有租约，否则 5 分钟空闲回收会把正在浏览的会话关掉
        heldLeases.put(config.sessionKey, lease)?.let { runCatching { it.close() } }
        mounted[config.sessionKey] = vfs
        mounted["${config.scheme}://${config.host}:${config.port}"] = vfs
        withContext(Dispatchers.IO) { connectionDao.touch(config.id) }
        return vfs
    }

    /**
     * 测试未保存的连接配置：lease 只覆盖 connect + list 生命周期，
     * 不写入 mounted/heldLeases，也不 touch connection 表。
     */
    suspend fun testConnection(config: ConnectionConfig, secretOverride: String? = null): Int {
        val secret = secretOverride
            ?: withContext(Dispatchers.IO) { secretStore.get(connectionDao.secretRef(config.id)) }
        val lease = registry.acquire(config, secret)
        try {
            val vfs = lease.use()
            vfs.connect()
            val uri = com.u707t.panelfm.core.vfs.VfsUri.of(
                config.scheme,
                "${config.host}:${config.port}",
                config.openPath,
            )
            return withContext(dispatchers.vfs) { vfs.list(uri).size }
        } finally {
            lease.close()
        }
    }

    /** 断开单个连接（侧边栏「断开」/ 删除连接）：释放租约、摘掉挂载表并立即关闭会话 */
    fun disconnectConnection(config: ConnectionConfig) {
        heldLeases.remove(config.sessionKey)?.let { runCatching { it.close() } }
        mounted.remove(config.sessionKey)
        mounted.remove("${config.scheme}://${config.host}:${config.port}")
        scope.launch { registry.closeSession(config.sessionKey) }
    }

    /** 断开全部连接（保留连接配置，仅关会话） */
    fun disconnectAll() {
        heldLeases.keys.toList().forEach { key -> heldLeases.remove(key)?.let { runCatching { it.close() } } }
        mounted.clear()
        scope.launch { registry.closeAll() }
    }

    fun mountedOf(key: String): com.u707t.panelfm.core.vfs.VirtualFileSystem? = mounted[key]

    /**
     * 生成「属于某连接」的 URI：统一在这里补 `?c=<connectionId>`。
     *
     * 为什么必须收敛到一处：会话隔离（`VfsUri.sameMount`、书签 / 最近路径 / 同步路径等入口）
     * 全都依赖 URI 上带着连接号。旧实现只有两个地方手工拼 `"c=${'$'}{config.id}"`，
     * 其余入口（书签、最近路径、同步、返回上级）都不带 —— 于是同主机不同账号会被
     * `sameMount` 判成同一挂载点，`isInside` 误报「目标在源内部」直接拒绝操作。
     */
    fun uriForConnection(
        config: com.u707t.panelfm.core.model.ConnectionConfig,
        path: String = config.openPath,
    ): com.u707t.panelfm.core.vfs.VfsUri =
        com.u707t.panelfm.core.vfs.VfsUris.withConnection(
            com.u707t.panelfm.core.vfs.VfsUri.of(config.scheme, "${'$'}{config.host}:${'$'}{config.port}", path),
            config.id,
        )

    fun archiveOf(hostUri: String): ArchiveVfs? = archives[hostUri]

    /**
     * 挂载压缩包：本地文件直接随机访问；远程文件先下载到 cache（zip 才支持随机访问，7z/tar 顺序读）。
     *
     * 审计 U4：
     *  - **单飞去重**：同一 host 只允许一个挂载在途（见 [openingArchives]），连点不会并发下载；
     *  - **[onProgress]** 上报下载字节（done/total），供长操作状态条显示进度与「取消」。
     */
    suspend fun openArchive(
        host: com.u707t.panelfm.core.vfs.VfsUri,
        onProgress: com.u707t.panelfm.core.vfs.ProgressCallback? = null,
        password: String? = null,
    ): ArchiveVfs {
        archives[host.toString()]?.let { cached ->
            // 口令匹配（或调用方不关心口令）直接复用；否则换新实例重挂（第 5 批 🔴1）
            if (password == null || cached.password == password) return cached
        }
        val key = host.toString()
        val mine = kotlinx.coroutines.CompletableDeferred<ArchiveVfs>()
        val inflight = openingArchives.putIfAbsent(key, mine)
        if (inflight != null) return inflight.await()
        try {
            // 先丢弃口令不符的旧挂载，再重建 —— 否则 openArchiveInner 的复用检查会把
            // 旧实例原样返回，口令永远换不上去。
            forgetArchive(key)
            val vfs = openArchiveInner(host, onProgress, password)
            mine.complete(vfs)
            return vfs
        } catch (t: Throwable) {
            mine.completeExceptionally(t)
            throw t
        } finally {
            openingArchives.remove(key, mine)
        }
    }

    private suspend fun openArchiveInner(
        host: com.u707t.panelfm.core.vfs.VfsUri,
        onProgress: com.u707t.panelfm.core.vfs.ProgressCallback?,
        password: String?,
    ): ArchiveVfs {
        archives[host.toString()]?.let { cached ->
            if (password == null || cached.password == password) return cached
        }
        val kind = ArchiveVfs.ArchiveKind.ofFileName(host.name)
            ?: throw com.u707t.panelfm.core.vfs.VfsException.Unsupported("不支持的压缩格式：${host.name}")
        val local = withContext(Dispatchers.IO) {
            if (host.scheme == "local") {
                val path = localVfs.absolutePath(host)
                java.io.File(path)
            } else {
                val vfs = locator.find(host) ?: throw com.u707t.panelfm.core.vfs.VfsException.Unsupported("会话不可用")
                val meta = vfs.stat(host)
                // 缓存名 = 完整 URI hash + 源文件修改时间 + 文件名。
                // 旧实现只用「URI hash + 文件名」，且校验只看长度 —— 远端同名同大小的压缩包被替换后，
                // 仍然会命中旧缓存，表现为「压缩包内容明明改了，打开还是旧的」。
                val stamp = meta.lastModified.takeIf { it > 0 }?.toString() ?: "0"
                val prefix = host.toString().hashCode().toString(16)
                val name = "$prefix-$stamp-${host.name}"
                val tmp = java.io.File(appDirs.tmpDir, name)
                if (!tmp.exists() || tmp.length() != meta.size) {
                    val reader = vfs.openRead(host)
                    try {
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            var done = 0L
                            while (true) {
                                val n = reader.read(buf, 0, buf.size)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                // 下载进度（审计 U4）：取消会在下一次回调时立刻生效
                                onProgress?.onProgress(done, meta.size)
                            }
                        }
                    } finally {
                        runCatching { reader.close() }
                    }
                    // 清理同一压缩包的旧版本缓存，避免 cache 目录无限增长
                    java.io.File(appDirs.tmpDir).listFiles()
                        ?.filter { it.name.startsWith("$prefix-") && it.name != name }
                        ?.forEach { runCatching { it.delete() } }
                }
                tmp
            }
        }
        val vfs = ArchiveVfs(host, kind, local, env, password)
        vfs.connect()
        archives[host.toString()] = vfs
        return vfs
    }

    fun forgetArchive(host: String) {
        archives.remove(host)?.let { runCatching { it.close() } }
    }

    /** 保存口令；返回 false = 写入失败（UI 必须提示，不能假装保存成功） */
    suspend fun saveSecret(configId: Long, secret: String?): Boolean =
        withContext(Dispatchers.IO) { secretStore.put(connectionDao.secretRef(configId), secret) }

    fun loadSecret(configId: Long): String? = secretStore.get(connectionDao.secretRef(configId))

    fun close() {
        runCatching { remote.stop() }
        disconnectAll()
        Logx.i("AppContainer", "closed")
    }
}
