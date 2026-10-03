package com.u707t.panelfm.core.vfs.sftp

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.vfs.HostKeyStore
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * SFTP 端到端测试：测试内起一个真的 MINA SSHD 服务端（虚拟文件系统挂到临时目录），
 * 用 [SftpVfs] 走完整的 连接 → 列目录 → 建目录 → 上传（含偏移续传）→ 下载 → 重命名 → 删除。
 */
class SftpVfsTest {

    private lateinit var server: SshServer
    private lateinit var root: Path
    private lateinit var vfs: SftpVfs
    private var port: Int = 0

    private class MemoryHostKeys : HostKeyStore {
        val map = mutableMapOf<String, String>()
        override fun fingerprint(host: String, port: Int): String? = map["$host:$port"]
        override fun trust(host: String, port: Int, fingerprint: String, keyType: String) {
            map["$host:$port"] = fingerprint
        }
        override fun forget(host: String, port: Int) { map.remove("$host:$port") }
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("sftp-root")
        Files.write(root.resolve("hello.txt"), "hello sftp".toByteArray())

        server = SshServer.setUpDefaultServer()
        server.host = "127.0.0.1"
        server.port = 0
        server.keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempDirectory("sftp-hostkey").resolve("hostkey.ser"))
        server.passwordAuthenticator = PasswordAuthenticator { user, pass, _ -> user == "tester" && pass == "secret" }
        server.subsystemFactories = listOf(SftpSubsystemFactory())
        server.fileSystemFactory = VirtualFileSystemFactory(root)
        server.start()
        port = server.port

        val env = VfsEnv(
            appDirs = AppDirs(Files.createTempDirectory("files").toString(), Files.createTempDirectory("cache").toString()),
            dispatchers = PanelDispatchers(
                io = Dispatchers.IO,
                vfs = Dispatchers.IO,
                decode = Dispatchers.Default,
                main = Dispatchers.Default,
            ),
            localNetworkAllowed = { true },
        )
        val config = ConnectionConfig(
            type = ConnectionType.SFTP,
            name = "test",
            host = "127.0.0.1",
            port = port,
            user = "tester",
            basePath = "/",
        )
        vfs = SftpVfs(SftpConfig.from(config, "secret"), env, MemoryHostKeys())
    }

    @After
    fun tearDown() {
        runCatching { vfs.close() }
        runCatching { server.stop(true) }
    }

    private fun uri(path: String) = VfsUri.of("sftp", "127.0.0.1:$port", path)

    @Test
    fun `连接并列出根目录`() = runTest {
        vfs.connect()
        val items = vfs.list(uri("/"))
        assertTrue(items.any { it.name == "hello.txt" })
    }

    @Test
    fun `建目录 上传 下载 重命名 删除 全链路`() = runTest {
        vfs.connect()
        vfs.mkdir(uri("/sub"), parents = true)

        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val writer = vfs.openWrite(uri("/sub/data.bin"), size = payload.size.toLong(), offset = 0L)
        writer.write(payload, 0, payload.size)
        writer.commit()

        val stat = vfs.stat(uri("/sub/data.bin"))
        assertEquals(payload.size.toLong(), stat.size)
        assertTrue(vfs.list(uri("/sub")).any { it.name == "data.bin" })

        val reader = vfs.openRead(uri("/sub/data.bin"))
        val out = ByteArray(payload.size)
        var read = 0
        while (read < out.size) {
            val n = reader.read(out, read, out.size - read)
            if (n < 0) break
            read += n
        }
        reader.close()
        assertEquals(payload.size, read)
        assertTrue(payload.contentEquals(out))

        assertTrue(vfs.rename(uri("/sub/data.bin"), uri("/sub/renamed.bin")))
        assertEquals("renamed.bin", vfs.stat(uri("/sub/renamed.bin")).name)

        vfs.delete(listOf(uri("/sub")))
        assertTrue(runCatching { vfs.stat(uri("/sub")) }.isFailure)
    }

    @Test
    fun `上传中断后可从偏移续传`() = runTest {
        vfs.connect()
        val total = 32 * 1024
        val payload = ByteArray(total) { (it % 127).toByte() }
        val half = total / 2

        // 第一次：只写一半，然后「失败」（close 不删 .part）——模拟网络中断/进程被杀
        val w1 = vfs.openWrite(uri("/resume.bin"), size = total.toLong(), offset = 0L)
        w1.write(payload, 0, half)
        w1.close()

        // 第二次：从断点继续
        val w2 = vfs.openWrite(uri("/resume.bin"), size = total.toLong(), offset = half.toLong())
        w2.write(payload, half, total - half)
        w2.commit()

        val stat = vfs.stat(uri("/resume.bin"))
        assertEquals(total.toLong(), stat.size)

        val reader = vfs.openRead(uri("/resume.bin"))
        val out = ByteArray(total)
        var read = 0
        while (read < out.size) {
            val n = reader.read(out, read, out.size - read)
            if (n < 0) break
            read += n
        }
        reader.close()
        assertTrue("续传后的内容应与原始一致", payload.contentEquals(out))
        // 临时文件已清理
        assertTrue(vfs.list(uri("/")).none { it.name.contains("panelfm.part") })
    }

    @Test
    fun `随机读取（流媒体 seek 场景）`() = runTest {
        vfs.connect()
        val payload = ByteArray(8192) { (it % 97).toByte() }
        val w = vfs.openWrite(uri("/seek.bin"), size = payload.size.toLong(), offset = 0L)
        w.write(payload, 0, payload.size)
        w.commit()

        val reader = vfs.openRead(uri("/seek.bin"))
        val chunk = reader.readFullyAt(4096, 1024)
        assertEquals(1024, chunk.size)
        assertTrue(payload.copyOfRange(4096, 5120).contentEquals(chunk))
        reader.close()
    }

    @Test
    fun `错误的密码给出认证错误`() = runTest {
        val env = com.u707t.panelfm.core.vfs.VfsEnv(
            appDirs = AppDirs(Files.createTempDirectory("f2").toString(), Files.createTempDirectory("c2").toString()),
            dispatchers = PanelDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.Default, Dispatchers.Default),
        )
        val bad = ConnectionConfig(type = ConnectionType.SFTP, name = "bad", host = "127.0.0.1", port = port, user = "tester")
        val badVfs = SftpVfs(SftpConfig.from(bad, "wrong"), env, MemoryHostKeys())
        var authFailed = false
        try {
            badVfs.connect()
        } catch (e: Exception) {
            authFailed = e is com.u707t.panelfm.core.vfs.VfsException.Auth ||
                e.message?.contains("认证", ignoreCase = true) == true
        }
        assertTrue("应当抛出认证失败，而不是超时或崩溃", authFailed)
        runCatching { badVfs.close() }
    }
}
