package com.u707t.panelfm

import android.app.Application
import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.sha256Hex
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
import com.u707t.panelfm.core.vfs.copyTo
import com.u707t.panelfm.core.vfs.local.LocalVfs
import com.u707t.panelfm.core.vfs.ftp.FtpVfsFactory
import com.u707t.panelfm.core.vfs.archive.ArchiveVfs
import com.u707t.panelfm.core.vfs.s3.S3Vfs
import com.u707t.panelfm.core.vfs.sftp.SftpVfs
import com.u707t.panelfm.core.vfs.smb.SmbVfs
import com.u707t.panelfm.core.vfs.webdav.WebDavVfsFactory
import com.u707t.panelfm.open.IncomingFileReceiver
import com.u707t.panelfm.tools.RemoteHttpServer
import com.u707t.panelfm.tools.TrashService
import com.u707t.panelfm.service.TransferService
import com.u707t.panelfm.ui.browser.BrowserController
import com.u707t.panelfm.ui.browser.ThumbCache
import com.u707t.panelfm.ui.preview.VfsDataSourceFactory
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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

    /**
     * 应用级协程作用域。
     *
     * ⚠️ 必须挂 [CoroutineExceptionHandler]：`SupervisorJob` 只保证「一个子作业失败不连坐兄弟」，
     * **不会**阻止未捕获异常继续上抛到线程默认处理器 —— 默认处理器直接杀进程。
     * 全仓 100+ 处 `scope.launch`，其中大量是「读数据库 / 落盘 / 连服务器」这类外部失败面，
     * 一处漏掉 try/catch（例如 DataStore 写失败、SQLite 磁盘满、协程里抛的非 VfsException）
     * 就是整个 App 闪退。有了 handler：记一条 ERROR 日志、App 继续跑。
     */
    private val crashGuard = CoroutineExceptionHandler { _, t ->
        runCatching { Logx.e("Scope", "未捕获的协程异常（已兜住，未崩溃）", t) }
    }

    val scope = CoroutineScope(SupervisorJob() + crashGuard + dispatchers.io)

    /**
     * 外部「用其他应用打开」的接收器：
     * `content://` → `cache/incoming/` → `local://incoming/...` → 现有预览链路。
     */
    val incoming = IncomingFileReceiver(app, appDirs, dispatchers, scope)

    init {
        // 冷启动也执行一次缓存清理：不能只靠「下次再收文件」触发 7 天 / 512 MB 策略。
        runCatching { incoming.cleanupNow() }
    }

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
    private val connectionIntents = ConnectionOpenIntents()

    private val archives = ConcurrentHashMap<String, ArchiveVfs>()
    private val archiveVersions = ConcurrentHashMap<String, CacheSourceVersion>()
    /** Replaced mounts stay alive so background readers holding them are not interrupted. */
    private val retiredArchives = ConcurrentHashMap.newKeySet<ArchiveVfs>()

    private data class OpeningArchive(
        val version: CacheSourceVersion,
        val passwordFingerprint: String?,
        val result: kotlinx.coroutines.CompletableDeferred<ArchiveVfs>,
    )
    private val openingArchives = ConcurrentHashMap<String, OpeningArchive>()

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

    /**
     * 「APKS / XAPK / APKM → APK」：合并分包 + 清单补丁 + 内置测试证书重签（见 core:apk）。
     *
     * 签名材料放 **noBackupFilesDir**：① 放 cache 会被系统清掉，清一次签名就换一次，
     * 装过的包全变成「签名不一致」；② 放 filesDir 会进云备份 —— 私钥跟着备份跑到别的设备上，
     * 既没用也不安全。工作目录放 cache/tmp，转换结束即删。
     */
    val apkConvert = com.u707t.panelfm.core.apk.ApksToApk(
        workRoot = java.io.File(appDirs.tmpDir),
        signingKeyDir = java.io.File(app.noBackupFilesDir, "apk-signing"),
    )

    /** 本地回收站（删除 → 回收站，可还原） */
    val trash = TrashService(appDirs, localVfs)

    /** 远程管理：内置只读 HTTP 服务 */
    val remote = RemoteHttpServer(
        locator,
        protectedRoots = listOf(app.filesDir, app.noBackupFilesDir, app.getDatabasePath("panel.db").parentFile!!),
    )

    /** Media3 播放用的统一 VFS 数据源（本地/SFTP/WebDAV/SMB/S3 通吃） */
    @androidx.media3.common.util.UnstableApi
    val vfsDataSourceFactory = VfsDataSourceFactory(locator, app, resolver = { resolveSession(it) })

    init {
        ThumbCache.init(appDirs.thumbsDir)
        scope.launch {
            var concurrencyApplied = false
            prefs.settings.collect { s ->
                _settings.value = s
                // 「定位此文件」开关：外部收文件时优先定位原文件而不是复制副本。
                incoming.preferLocate = s.locateIncomingOriginal
                // 并发数必须在这里落地：设置页只在「点按钮那一刻」调 updateConcurrency，
                // 冷启动不补这一步的话，用户选的 1/3/4 会在重启后悄悄回到默认 2。
                if (!concurrencyApplied) {
                    concurrencyApplied = true
                    engine.updateConcurrency(s.maxConcurrentTasks)
                }
            }
        }
        // 请求已接受不等于已进入前台；同时观察任务和服务真实生命周期。
        scope.launch {
            var failedForBatch = false
            kotlinx.coroutines.flow.combine(
                engine.taskEvents,
                com.u707t.panelfm.service.TransferForegroundState.status,
            ) { tasks, status -> tasks to status }.collect { (tasks, _) ->
                val needed = tasks.any { com.u707t.panelfm.service.needsTransferForeground(it.state.value) }
                val status = com.u707t.panelfm.service.TransferForegroundState.status.value
                if (!needed) {
                    failedForBatch = false
                    if (status == com.u707t.panelfm.service.TransferForegroundStatus.Running ||
                        status == com.u707t.panelfm.service.TransferForegroundStatus.Starting
                    ) TransferService.stop(app)
                    else if (status != com.u707t.panelfm.service.TransferForegroundStatus.Stopped) {
                        com.u707t.panelfm.service.TransferForegroundState.publish(
                            com.u707t.panelfm.service.TransferForegroundStatus.Stopped,
                        )
                    }
                } else when (status) {
                    com.u707t.panelfm.service.TransferForegroundStatus.Stopped -> {
                        if (!failedForBatch && !TransferService.start(app)) {
                            failedForBatch = true
                            browser.showStatus("后台传输通知不可用：切到后台后传输可能被系统中断")
                        }
                    }
                    com.u707t.panelfm.service.TransferForegroundStatus.Failed,
                    com.u707t.panelfm.service.TransferForegroundStatus.TimedOut -> {
                        if (!failedForBatch) {
                            failedForBatch = true
                            browser.showStatus(
                                if (status == com.u707t.panelfm.service.TransferForegroundStatus.TimedOut)
                                    "后台传输达到系统时间限制，任务已停止，请回到应用后重试"
                                else "后台传输保障已停止：切到后台后任务可能被系统中断",
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
        scope.launch { reloadConnections() }
        // 清理过期断点续传记录（取消/失败留下的记录超过 7 天即丢弃）
        scope.launch {
            runCatching { resumeDao.purgeStale(System.currentTimeMillis() - 7L * 24 * 3600 * 1000) }
        }
        // 回收站自动清理：启动 5 秒后在后台跑一次（幂等；没有过期条目时几乎零成本）。
        // 不放在主线程：统计容量要遍历索引，大回收站可能几十毫秒。
        //
        // 必须读 prefs.settings.first()，不能读 _settings.value：后者是内存镜像，冷启动
        // 5 秒内可能还停在默认值 (0,0)，那会让「保留 N 天」这一次启动**静默失效**。
        scope.launch {
            kotlinx.coroutines.delay(5_000)
            runCatching {
                val s = prefs.settings.first()
                val (removed, freed) = trash.autoPurge(s.trashRetentionDays, s.trashMaxBytes)
                if (removed > 0) {
                    Logx.i("Trash", "auto purge $removed item(s), freed ${com.u707t.panelfm.core.common.Fmt.size(freed)}")
                }
            }
            // 顺带清掉上次转换 / 导出被中断留下的临时产物（进程被杀时 finally 不会执行）。
            // 只删 24 小时前的：正在跑的转换绝不能碰。
            runCatching {
                val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
                fun sweep(dir: String, vararg prefixes: String) {
                    java.io.File(dir).listFiles()?.forEach { f ->
                        if (prefixes.any { f.name.startsWith(it) } && f.lastModified() < cutoff) {
                            runCatching { f.deleteRecursively() }
                        }
                    }
                }
                sweep(appDirs.tmpDir, "convert-", "apk-convert-")
                sweep(appDirs.cacheDir, "panelfm-export-")
            }
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
        connections.value.singleOrNull { it.scheme == scheme && "${it.host}:${it.port}" == authority }

    /**
     * 由一个 VFS URI 反查它属于哪个连接配置。
     *
     * 三级匹配，覆盖所有 URI 形态：
     *  1. `?c=<connectionId>`（最精确，支持同主机多账号）；
     *  2. `authority == host:port`（WebDAV / SFTP / FTP / SMB）；
     *  3. S3 的 authority 是 **bucket 名**，不是 host:port —— 先按连接的默认 bucket 匹配，
     *     只有一个 S3 连接时直接采用它。
     */
    fun connectionForUri(uri: com.u707t.panelfm.core.vfs.VfsUri): ConnectionConfig? =
        connectionConfigForUri(connections.value, uri)

    /**
     * 在当前**活着**的会话里找 URI 所属的 VFS。
     *
     * 为什么要有这条：`mounted` 表只是历史缓存（会话被空闲回收 / 断开后不会自动清理），
     * 单靠它会返回一个已经死掉的实例；而 URI 丢了 `?c=` 时（历史书签、旧路径记录、
     * 解析层丢掉 query）又没法用连接号定位。这里直接以「注册表里真实存在的会话」为准。
     */
    fun liveSessionFor(uri: com.u707t.panelfm.core.vfs.VfsUri): com.u707t.panelfm.core.vfs.VirtualFileSystem? =
        connectionForUri(uri)?.let { registry.peek(it) }

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
        val ticket = connectionIntents.begin(config.id)
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
        var published = false
        connectionIntents.publish(ticket) {
            if (registry.activate(config, lease)) {
                val key = heldConnectionKey(config)
                heldLeases.entries.filter { it.key.startsWith("${config.id}|") && it.key != key }.forEach { old ->
                    if (heldLeases.remove(old.key, old.value)) old.value.close()
                    mounted.remove(old.key)
                }
                heldLeases.put(key, lease)?.close()
                mounted[key] = vfs
                published = true
            }
        }
        if (!published) {
            lease.close()
            throw com.u707t.panelfm.core.vfs.VfsException.Network(
                com.u707t.panelfm.core.vfs.VfsException.Network.Kind.DISCONNECTED,
                "连接请求已被更新或取消，请重新打开",
            )
        }
        withContext(Dispatchers.IO) { connectionDao.touch(config.id) }
        return vfs
    }

    /**
     * 测试未保存的连接配置：lease 只覆盖 connect + list 生命周期，
     * 不写入 mounted/heldLeases，也不 touch connection 表。
     */
    suspend fun testConnection(config: ConnectionConfig, secretOverride: String?): Int {
        val secret = secretOverride
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
        val activation = connectionIntents.disconnect(config.id) {
            val token = registry.activationToken(config)
            heldLeases.remove(heldConnectionKey(config))?.close()
            mounted.remove(heldConnectionKey(config))
            token
        }
        scope.launch { registry.closeConnection(config, activation) }
    }

    /** 只撤销本次观察到的会话；之后新开的会话不受异步清理影响。 */
    fun disconnectAll() {
        val activations = connectionIntents.disconnectAll {
            val snapshot = connections.value.map { it to registry.activationToken(it) }
            heldLeases.keys.toList().forEach { key -> heldLeases.remove(key)?.close() }
            mounted.clear()
            snapshot
        }
        scope.launch { activations.forEach { (config, token) -> registry.closeConnection(config, token) } }
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
    ): com.u707t.panelfm.core.vfs.VfsUri = connectionUri(config, path)

    fun archiveOf(hostUri: String): ArchiveVfs? = archives[hostUri]
        ?: runCatching { archives[com.u707t.panelfm.core.vfs.VfsUri.parse(hostUri).toString()] }.getOrNull()

    private suspend fun archiveSourceVersion(
        host: com.u707t.panelfm.core.vfs.VfsUri,
    ): CacheSourceVersion = withContext(Dispatchers.IO) {
        if (host.scheme == "local") {
            val file = runCatching { java.io.File(localVfs.absolutePath(host)) }.getOrNull()
            if (file?.isFile == true) CacheSourceVersion(file.length(), file.lastModified())
            else CacheSourceVersion()
        } else {
            val vfs = locator.find(host) ?: return@withContext CacheSourceVersion()
            runCatching { vfs.stat(host) }.getOrNull()?.let {
                CacheSourceVersion(
                    size = it.size,
                    lastModified = it.lastModified,
                    etag = it.etag,
                )
            } ?: CacheSourceVersion()
        }
    }

    /**
     * 挂载压缩包：本地文件直接随机访问；远程文件先下载到 cache（zip 才支持随机访问，7z/tar 顺序读）。
     * 归档复用必须通过源版本校验；未知版本每次都视为新版本，不能无条件复用旧句柄。
     */
    suspend fun openArchive(
        host: com.u707t.panelfm.core.vfs.VfsUri,
        onProgress: com.u707t.panelfm.core.vfs.ProgressCallback? = null,
        password: String? = null,
    ): ArchiveVfs {
        val key = host.toString()
        val sourceVersion = archiveSourceVersion(host)
        archives[key]?.let { cached ->
            val cachedVersion = archiveVersions[key]
            if (sourceVersion.isReliable && cachedVersion == sourceVersion &&
                (password == null || cached.password == password)
            ) return cached
        }
        val mine = OpeningArchive(
            version = sourceVersion,
            passwordFingerprint = password?.let(::secretFingerprint),
            result = kotlinx.coroutines.CompletableDeferred(),
        )
        val inflight = openingArchives.putIfAbsent(key, mine)
        if (inflight != null) {
            if (!archivePasswordsCompatible(password, inflight.passwordFingerprint) ||
                !archiveVersionsCompatible(sourceVersion, inflight.version)
            ) {
                // 在途挂载与本次请求不兼容（口令不同 / 源已变）：等它收尾后重试。
                // 失败可以吞掉（由重试路径给出真实错误），取消必须透传。
                awaitIgnoringFailure(inflight.result)
                return openArchive(host, onProgress, password)
            }
            val result = inflight.result.await()
            // A replacement can happen while the older download is in flight. Do not hand its
            // mounted index to a caller that already observed a different reliable version.
            val currentVersion = archiveSourceVersion(host)
            if (!archiveVersionsCompatible(currentVersion, inflight.version)) {
                return openArchive(host, onProgress, password)
            }
            return result
        }
        try {
            // Do not close the prior mount: background decompression/readers may still hold it.
            forgetArchive(key)
            val vfs = openArchiveInner(host, onProgress, password)
            mine.result.complete(vfs)
            return vfs
        } catch (t: Throwable) {
            mine.result.completeExceptionally(t)
            throw t
        } finally {
            openingArchives.remove(key, mine)
        }
    }

    private suspend fun awaitIgnoringFailure(result: kotlinx.coroutines.Deferred<ArchiveVfs>) {
        try {
            result.await()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Throwable) {
            // 在途挂载失败：由本次调用的重试路径重建并给出真实错误。
        }
    }

    private suspend fun openArchiveInner(
        host: com.u707t.panelfm.core.vfs.VfsUri,
        onProgress: com.u707t.panelfm.core.vfs.ProgressCallback?,
        password: String?,
    ): ArchiveVfs {
        val kind = ArchiveVfs.ArchiveKind.ofFileName(host.name)
            ?: throw com.u707t.panelfm.core.vfs.VfsException.Unsupported("不支持的压缩格式：${host.name}")
        val prepared = withContext(Dispatchers.IO) {
            if (host.scheme == "local") {
                val path = localVfs.absolutePath(host)
                val file = java.io.File(path)
                file to if (file.isFile) CacheSourceVersion(file.length(), file.lastModified()) else CacheSourceVersion()
            } else {
                val vfs = locator.find(host)
                    ?: throw com.u707t.panelfm.core.vfs.VfsException.Unsupported("会话不可用")
                val meta = vfs.stat(host)
                val version = CacheSourceVersion(meta.size, meta.lastModified, meta.etag)
                // The source version is part of the filename. Unknown versions get a unique key,
                // so a failed/partial download can never be mistaken for a later cache hit.
                val prefix = host.toString().hashCode().toString(16)
                val name = "$prefix-${version.cacheKey(host.toString())}-${host.name}"
                val tmp = java.io.File(appDirs.tmpDir, name)
                val expected = meta.size.takeIf { it >= 0L }
                if (!tmp.isFile || (expected != null && tmp.length() != expected)) {
                    writeAtomically(tmp, expectedLength = expected) { out ->
                        val reader = vfs.openRead(host)
                        try {
                            reader.copyTo(out) { done -> onProgress?.onProgress(done, meta.size) }
                        } finally {
                            runCatching { reader.close() }
                        }
                    }
                }
                tmp to version
            }
        }
        val local = prepared.first
        val sourceVersion = prepared.second
        val vfs = ArchiveVfs(host, kind, local, env, password)
        try {
            vfs.connect()
        } catch (t: Throwable) {
            // connect() may have opened ZIP/7z/native resources before rejecting a bad archive.
            runCatching { vfs.close() }
            throw t
        }
        val key = host.toString()
        archives[key] = vfs
        archiveVersions[key] = sourceVersion
        return vfs
    }

    fun forgetArchive(host: String) {
        val key = runCatching { com.u707t.panelfm.core.vfs.VfsUri.parse(host).toString() }.getOrDefault(host)
        archives.remove(key)?.let {
            archiveVersions.remove(key)
            retiredArchives += it
        }
    }

    /** 保存口令；返回 false = 写入失败（UI 必须提示，不能假装保存成功） */
    suspend fun saveSecret(configId: Long, secret: String?): Boolean =
        withContext(Dispatchers.IO) { secretStore.put(connectionDao.secretRef(configId), secret) }

    /**
     * 读取口令。内部会查 secret_ref 并做 Keystore 解密，**必须**离开主线程：
     * 编辑页的加载与保存都调用它，调用方本身已在协程里。
     */
    suspend fun loadSecret(configId: Long): String? =
        withContext(Dispatchers.IO) { secretStore.get(connectionDao.secretRef(configId)) }

    fun close() {
        runCatching { remote.stop() }
        disconnectAll()
        archives.values.toList().forEach { runCatching { it.close() } }
        retiredArchives.toList().forEach { runCatching { it.close() } }
        archives.clear()
        archiveVersions.clear()
        retiredArchives.clear()
        Logx.i("AppContainer", "closed")
    }
}
