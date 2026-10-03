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
import com.u707t.panelfm.core.vfs.s3.S3Vfs
import com.u707t.panelfm.core.vfs.sftp.SftpVfs
import com.u707t.panelfm.core.vfs.smb.SmbVfs
import com.u707t.panelfm.core.vfs.webdav.WebDavVfsFactory
import com.u707t.panelfm.ui.browser.BrowserController
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

    val planner = FileOperationPlanner(locator)

    val engine = TransferEngine(
        planner = planner,
        locator = locator,
        resumeStore = resumeDao,
        dispatchers = dispatchers,
        scope = scope,
    )

    val browser = BrowserController(this)

    init {
        scope.launch {
            prefs.settings.collect { _settings.value = it }
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

    fun saveSecret(configId: Long, secret: String?) {
        scope.launch(Dispatchers.IO) { secretStore.put(connectionDao.secretRef(configId), secret) }
    }

    fun loadSecret(configId: Long): String? = secretStore.get(connectionDao.secretRef(configId))

    fun close() {
        scope.launch { registry.closeAll() }
        Logx.i("AppContainer", "closed")
    }
}
