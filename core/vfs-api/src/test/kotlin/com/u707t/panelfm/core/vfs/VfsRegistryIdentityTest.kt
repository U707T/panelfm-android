package com.u707t.panelfm.core.vfs

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VfsRegistryIdentityTest {
    private class Store(val credential: String?) : VirtualFileSystem {
        override val id = "store"
        override val scheme = "sftp"
        override val label = "test"
        override val capabilities = VfsCapabilities()
        override val state = MutableStateFlow<VfsState>(VfsState.Ready)
        var closed = false
        override suspend fun connect() = Unit
        override suspend fun list(uri: VfsUri, options: ListOptions) = emptyList<FileMetadata>()
        override suspend fun stat(uri: VfsUri) = FileMetadata.dir(uri, "/")
        override suspend fun mkdir(uri: VfsUri, parents: Boolean) = Unit
        override suspend fun delete(uris: List<VfsUri>, onProgress: ProgressCallback?) = Unit
        override suspend fun rename(from: VfsUri, to: VfsUri) = false
        override fun openRead(uri: VfsUri, offset: Long, length: Long): VfsReader = error("unused")
        override suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long): VfsWriter = error("unused")
        override fun close() { closed = true }
    }
    private val config = ConnectionConfig(id = 1, type = ConnectionType.SFTP, name = "NAS", host = "nas", user = "same-user")
    private val factory = object : VfsFactory {
        override val scheme = "sftp"
        override fun create(config: ConnectionConfig, secret: String?, env: VfsEnv) = Store(secret)
    }
    private fun env() = VfsEnv(AppDirs("unused", "unused"), PanelDispatchers(Dispatchers.Default, Dispatchers.Default, Dispatchers.Default, Dispatchers.Default))

    @Test fun `临时测试新凭据及旧租约晚释放不能改变正式身份`() = runTest {
        val registry = VfsRegistry(mapOf("sftp" to factory), env(), this)
        val old = registry.acquire(config, "old")
        assertTrue(registry.activate(config, old))
        val replacement = registry.acquire(config, "new")
        assertTrue(registry.activate(config, replacement))
        old.close()
        runCurrent()
        assertSame(replacement.use(), registry.peek(config))
        val test = registry.acquire(config, "unsaved-test-password")
        test.close()
        runCurrent()
        assertSame(replacement.use(), registry.peek(config))
        assertSame(old.use(), registry.peek(config, "old"))
        registry.closeAll()
        assertNull(registry.peek(config))
    }

    @Test fun `同配置双连接各自激活且断开一个不关闭另一个共享holder`() = runTest {
        val registry = VfsRegistry(mapOf("sftp" to factory), env(), this)
        val first = registry.acquire(config, "password")
        val secondConfig = config.copy(id = 2)
        val second = registry.acquire(secondConfig, "password")
        registry.activate(config, first)
        registry.activate(secondConfig, second)
        first.close()
        registry.closeConnection(config)
        runCurrent()
        assertNull(registry.peek(config))
        assertSame(second.use(), registry.peek(secondConfig))
        assertFalse((second.use() as Store).closed)
        second.close()
        registry.closeConnection(secondConfig)
        assertTrue((second.use() as Store).closed)
    }

    @Test fun `不同凭据不能按最近使用猜测且激活会话不会闲置回收`() = runTest {
        val registry = VfsRegistry(mapOf("sftp" to factory), env(), this)
        val first = registry.acquire(config, "old")
        val second = registry.acquire(config, "new")
        assertNull(registry.peek(config))
        registry.activate(config, second)
        first.close()
        second.close()
        second.close()
        runCurrent()
        registry.closeIdle(Long.MAX_VALUE, 0)
        assertSame(second.use(), registry.peek(config))
        assertFalse((second.use() as Store).closed)
        assertTrue((first.use() as Store).closed)
        registry.closeConnection(config)
    }

    @Test fun `旧异步断开不得撤销同holder刚重新激活的连接`() = runTest {
        val registry = VfsRegistry(mapOf("sftp" to factory), env(), this)
        val first = registry.acquire(config, "password")
        registry.activate(config, first)
        val captured = registry.activationToken(config)
        val reopened = registry.acquire(config, "password")
        registry.activate(config, reopened)
        assertNotEquals(captured, registry.activationToken(config))
        first.close()
        runCurrent()
        registry.closeConnection(config, captured)
        assertSame(reopened.use(), registry.peek(config))
        assertFalse((reopened.use() as Store).closed)
        reopened.close()
        registry.closeConnection(config)
    }

    @Test fun `disconnect不关闭仍有未激活租约的凭据holder`() = runTest {
        val registry = VfsRegistry(mapOf("sftp" to factory), env(), this)
        val formal = registry.acquire(config, "formal")
        registry.activate(config, formal)
        val token = registry.activationToken(config)
        val inFlightTest = registry.acquire(config, "test")
        formal.close() // synchronous release in registry
        registry.closeConnection(config, token)
        assertNull(registry.peek(config))
        assertSame(inFlightTest.use(), registry.peek(config, "test"))
        assertFalse((inFlightTest.use() as Store).closed)
        inFlightTest.close()
        registry.closeIdle(Long.MAX_VALUE, 0)
        assertTrue((inFlightTest.use() as Store).closed)
    }
}
