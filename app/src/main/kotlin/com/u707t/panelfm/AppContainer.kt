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
import com.u707t.panelfm.core.data.ResumeDao
import com.u707t.panelfm.core.data.SecretStore
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.transfer.FileOperationPlanner
import com.u707t.panelfm.core.transfer.TransferEngine
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
        userAgent = "PanelFM/0.1 (Android)",
        timeoutMs = 30_000L,
        localNetworkAllowed = { LocalNetwork.isGranted(app) },
    )

    // ---------------------------------------------------------------- 持久化

    val db = PanelDb(app)
    val connectionDao = ConnectionDao(db)
    val bookmarkDao = BookmarkDao(db)
    val resumeDao = ResumeDao(db)
    val secretStore = SecretStore(db)
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

    /** 已挂载的压缩包（hostUri → ArchiveVfs） */
    private val archives = ConcurrentHashMap<String, ArchiveVfs>()

    val planner = FileOperationPlanner(locator)

    val engine = TransferEngine(
        planner = planner,
        locator = locator,
        resumeStore = resumeDao,
        dispatchers = dispatchers,
        scope = scope,
    )

    val browser = BrowserController(this)

    /** 本地回收站（删除 → 回收站，可还原） */
    val trash = TrashService(appDirs, localVfs)

    /** 远程管理：内置只读 HTTP 服务 */
    val remote = RemoteHttpServer(locator)

    /** Media3 播放用的统一 VFS 数据源（本地/SFTP/WebDAV/SMB/S3 通吃） */
    val vfsDataSourceFactory = VfsDataSourceFactory(locator)

    init {
        ThumbCache.init(appDirs.thumbsDir)
        scope.launch {
            prefs.settings.collect { _settings.value = it }
        }
        // 任务运行时启用前台服务通知（M9）
        scope.launch {
            var foregound = false
            engine.tasks.collect { tasks ->
                val active = tasks.any {
                    val st = it.state.value
                    st !is com.u707t.panelfm.core.transfer.TaskState.Done &&
                        st !is com.u707t.panelfm.core.transfer.TaskState.Cancelled &&
                        st !is com.u707t.panelfm.core.transfer.TaskState.Failed
                }
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

    /** 打开（或复用）一个网络连接，返回可用的 VFS 实例 */
    suspend fun openConnection(config: ConnectionConfig): com.u707t.panelfm.core.vfs.VirtualFileSystem {
        val secret = withContext(Dispatchers.IO) { secretStore.get(connectionDao.secretRef(config.id)) }
        val lease = registry.acquire(config, secret)
        val vfs = lease.use()
        lease.close()
        mounted[config.sessionKey] = vfs
        mounted["${config.scheme}://${config.host}:${config.port}"] = vfs
        vfs.connect()
        withContext(Dispatchers.IO) { connectionDao.touch(config.id) }
        return vfs
    }

    fun mountedOf(key: String): com.u707t.panelfm.core.vfs.VirtualFileSystem? = mounted[key]

    fun archiveOf(hostUri: String): ArchiveVfs? = archives[hostUri]

    /**
     * 挂载压缩包：本地文件直接随机访问；远程文件先下载到 cache（zip 才支持随机访问，7z/tar 顺序读）。
     */
    suspend fun openArchive(host: com.u707t.panelfm.core.vfs.VfsUri): ArchiveVfs {
        archives[host.toString()]?.let { return it }
        val kind = ArchiveVfs.ArchiveKind.ofFileName(host.name)
            ?: throw com.u707t.panelfm.core.vfs.VfsException.Unsupported("不支持的压缩格式：${host.name}")
        val local = withContext(Dispatchers.IO) {
            if (host.scheme == "local") {
                val path = localVfs.absolutePath(host)
                java.io.File(path)
            } else {
                val vfs = locator.find(host) ?: throw com.u707t.panelfm.core.vfs.VfsException.Unsupported("会话不可用")
                val name = host.name.hashCode().toString(16) + "-" + host.name
                val tmp = java.io.File(appDirs.tmpDir, name)
                if (!tmp.exists() || tmp.length() != vfs.stat(host).size) {
                    val reader = vfs.openRead(host)
                    try {
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = reader.read(buf, 0, buf.size)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                    } finally {
                        runCatching { reader.close() }
                    }
                }
                tmp
            }
        }
        val vfs = ArchiveVfs(host, kind, local, env)
        vfs.connect()
        archives[host.toString()] = vfs
        return vfs
    }

    fun forgetArchive(host: String) {
        archives.remove(host)?.let { runCatching { it.close() } }
    }

    fun saveSecret(configId: Long, secret: String?) {
        scope.launch(Dispatchers.IO) { secretStore.put(connectionDao.secretRef(configId), secret) }
    }

    fun loadSecret(configId: Long): String? = secretStore.get(connectionDao.secretRef(configId))

    fun close() {
        runCatching { remote.stop() }
        scope.launch { registry.closeAll() }
        Logx.i("AppContainer", "closed")
    }
}
