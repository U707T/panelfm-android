package com.u707t.panelfm.core.vfs.archive

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.vfs.ByteArrayReader
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.ListOptions
import com.u707t.panelfm.core.vfs.ProgressCallback
import com.u707t.panelfm.core.vfs.VfsCapabilities
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsReader
import com.u707t.panelfm.core.vfs.VfsState
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsWriter
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 加密压缩包的**端到端闭环**回归：`压缩(带口令) → 重新挂载 → 列目录 → 读内容 → 改内容`。
 *
 * 这组用例是为了锁死三条实测过的真实缺陷（见 `docs/AUDIT-2026-10-05-CODE-TRUTH.md` C1/C2/C3）：
 *  1. ZIP 加密压缩路径 `addRawArchiveEntry` 抛 `UnsupportedZipFeatureException`，写出 0 字节；
 *  2. 加密 7z 能创建、却因 `SevenZFile` 不带口令而读不回来（`PasswordRequiredException`）；
 *  3. 加密 ZIP 同理读不回来，且 commons-compress 1.27.1 **根本没有**加密 ZIP 读实现。
 *
 * 旧测试（[EncryptedZipRoundTripTest]）只用 Python 验证了写侧格式正确，**没有**验证
 * 「PanelFM 自己能不能读回来」——正是这个缺口让上面三条一直没被发现。
 */
class EncryptedArchiveRoundTripTest {

    private class Mem : VirtualFileSystem {
        override val id = "mem"
        override val scheme = "mem"
        override val label = "mem"
        override val capabilities = VfsCapabilities(rename = true, rangeRead = true, rangeWrite = true, writable = true)
        val files = linkedMapOf<String, ByteArray>()
        private val _state = MutableStateFlow(VfsState.Ready)
        override val state: StateFlow<VfsState> = _state

        fun put(path: String, data: ByteArray) { files[path] = data }
        fun content(path: String) = files[path] ?: ByteArray(0)

        override suspend fun connect() = Unit

        override suspend fun list(uri: VfsUri, options: ListOptions): List<FileMetadata> {
            val prefix = uri.path.trimEnd('/') + "/"
            val out = ArrayList<FileMetadata>()
            files.keys.filter { it.startsWith(prefix) }.forEach { key ->
                val rest = key.removePrefix(prefix)
                val name = rest.substringBefore('/')
                if (out.none { it.name == name }) {
                    out.add(
                        FileMetadata(
                            uri = uri.child(name),
                            name = name,
                            isDirectory = rest.contains('/'),
                            size = files[key]?.size?.toLong() ?: 0,
                        )
                    )
                }
            }
            return out
        }

        override suspend fun stat(uri: VfsUri): FileMetadata {
            files[uri.path]?.let {
                return FileMetadata(uri = uri, name = uri.name, isDirectory = false, size = it.size.toLong())
            }
            return FileMetadata.dir(uri, uri.name)
        }

        override suspend fun mkdir(uri: VfsUri, parents: Boolean) = Unit
        override suspend fun delete(uris: List<VfsUri>, onProgress: ProgressCallback?) = Unit
        override suspend fun rename(from: VfsUri, to: VfsUri) = true

        override fun openRead(uri: VfsUri, offset: Long, length: Long): VfsReader =
            ByteArrayReader(files[uri.path] ?: ByteArray(0))

        override suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long): VfsWriter {
            val buffer = ByteArrayOutputStream()
            return object : VfsWriter {
                override val writtenBytes: Long get() = buffer.size().toLong()
                override suspend fun write(b: ByteArray, o: Int, l: Int) { buffer.write(b, o, l) }
                override suspend fun flush() = Unit
                override suspend fun commit() { files[uri.path] = buffer.toByteArray() }
                override suspend fun abort() = Unit
            }
        }
    }

    private fun env() = VfsEnv(AppDirs("/tmp/panelfm-test", "/tmp/panelfm-test"), PanelDispatchers())

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Fixture(val mem: Mem, val locator: VfsLocator)

    private fun fixture(): Fixture {
        val mem = Mem()
        mem.put("/src/hello.txt", "hello 加密内容".toByteArray())
        mem.put("/src/sub/nested.bin", ByteArray(3000) { (it % 251).toByte() })
        val locator = object : VfsLocator {
            override fun find(uri: VfsUri): VirtualFileSystem? = if (uri.authority == "one") mem else null
        }
        return Fixture(mem, locator)
    }

    /** 把内存里的压缩包落到临时文件再挂载（与 AppContainer.openArchive 的做法一致） */
    private fun mount(f: Fixture, dest: VfsUri, kind: ArchiveVfs.ArchiveKind, password: String?): ArchiveVfs {
        val tmp = File.createTempFile("panelfm-enc", ".${kind.id}").apply { writeBytes(f.mem.content(dest.path)) }
        return ArchiveVfs(dest, kind, tmp, env(), password)
    }

    private fun basePath(dest: VfsUri) = "/" + VfsUri.encodeHost(dest.toString()) + "!/"

    private suspend fun readAll(vfs: ArchiveVfs, uri: VfsUri): ByteArray {
        val reader = vfs.openRead(uri)
        try {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = reader.read(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0) out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            runCatching { reader.close() }
        }
    }

    // ------------------------------------------------------------------ ZIP

    @Test
    fun `ZIP 带口令压缩后能被自己读回（C1 回归）`() = runBlocking {
        val f = fixture()
        val dest = VfsUri.of("mem", "one", "/out.zip")
        ArchiveCompressor(f.locator).compress(
            listOf(VfsUri.of("mem", "one", "/src")), dest,
            ArchiveCompressor.Format.ZIP, password = "pw",
        )

        val bytes = f.mem.content("/out.zip")
        assertTrue("加密 ZIP 不能是 0 字节（旧实现在 addRawArchiveEntry 抛异常后写出空文件）", bytes.size > 0)

        val vfs = mount(f, dest, ArchiveVfs.ArchiveKind.ZIP, "pw")
        vfs.connect()
        val base = basePath(dest)
        val names = vfs.list(VfsUri.of("archive", "zip", base + "src")).map { it.name }
        assertTrue("应能列出 hello.txt，实际 $names", names.contains("hello.txt"))

        val text = String(readAll(vfs, VfsUri.of("archive", "zip", base + "src/hello.txt")))
        assertEquals("hello 加密内容", text)
    }

    @Test
    fun `ZIP 加密包嵌套目录与二进制内容能原样读回`() = runBlocking {
        val f = fixture()
        val dest = VfsUri.of("mem", "one", "/nested.zip")
        ArchiveCompressor(f.locator).compress(
            listOf(VfsUri.of("mem", "one", "/src")), dest,
            ArchiveCompressor.Format.ZIP, password = "pw",
        )
        val vfs = mount(f, dest, ArchiveVfs.ArchiveKind.ZIP, "pw")
        vfs.connect()
        val got = readAll(vfs, VfsUri.of("archive", "zip", basePath(dest) + "src/sub/nested.bin"))
        val expected = f.mem.content("/src/sub/nested.bin")
        assertEquals("嵌套二进制文件长度必须一致", expected.size, got.size)
        assertTrue("内容必须逐字节一致", expected.contentEquals(got))
    }

    @Test
    fun `ZIP 口令错误时给出可读文案而不是抛底层异常`() = runBlocking {
        val f = fixture()
        val dest = VfsUri.of("mem", "one", "/wrong.zip")
        ArchiveCompressor(f.locator).compress(
            listOf(VfsUri.of("mem", "one", "/src")), dest,
            ArchiveCompressor.Format.ZIP, password = "right-pw",
        )
        val vfs = mount(f, dest, ArchiveVfs.ArchiveKind.ZIP, "wrong-pw")
        vfs.connect()
        val err = runCatching {
            readAll(vfs, VfsUri.of("archive", "zip", basePath(dest) + "src/hello.txt"))
        }.exceptionOrNull()
        assertNotNull("错误口令必须抛错", err)
        assertTrue("必须是 VfsException，实际 ${err?.javaClass?.name}", err is VfsException)
        assertTrue(
            "文案应说明口令不对，实际：${(err as VfsException).userMessage}",
            err.userMessage.contains("口令"),
        )
    }

    @Test
    fun `ZIP 无口令包不受影响（回归）`() = runBlocking {
        val f = fixture()
        val dest = VfsUri.of("mem", "one", "/plain.zip")
        ArchiveCompressor(f.locator).compress(
            listOf(VfsUri.of("mem", "one", "/src")), dest,
            ArchiveCompressor.Format.ZIP,
        )
        val vfs = mount(f, dest, ArchiveVfs.ArchiveKind.ZIP, null)
        vfs.connect()
        assertEquals(
            "hello 加密内容",
            String(readAll(vfs, VfsUri.of("archive", "zip", basePath(dest) + "src/hello.txt"))),
        )
    }

    @Test
    fun `ZIP 仅存储级别的加密包也能读回（STORED + 加密）`() = runBlocking {
        val f = fixture()
        val dest = VfsUri.of("mem", "one", "/store.zip")
        ArchiveCompressor(f.locator).compress(
            listOf(VfsUri.of("mem", "one", "/src")), dest,
            ArchiveCompressor.Format.ZIP,
            level = ArchiveCompressor.Level.STORE,
            password = "pw",
        )
        val vfs = mount(f, dest, ArchiveVfs.ArchiveKind.ZIP, "pw")
        vfs.connect()
        assertEquals(
            "hello 加密内容",
            String(readAll(vfs, VfsUri.of("archive", "zip", basePath(dest) + "src/hello.txt"))),
        )
    }

    @Test
    fun `加密 ZIP 走随机读路径也能读回（readFullyAt）`() = runBlocking {
        val f = fixture()
        val dest = VfsUri.of("mem", "one", "/seek.zip")
        ArchiveCompressor(f.locator).compress(
            listOf(VfsUri.of("mem", "one", "/src")), dest,
            ArchiveCompressor.Format.ZIP, password = "pw",
        )
        val vfs = mount(f, dest, ArchiveVfs.ArchiveKind.ZIP, "pw")
        vfs.connect()
        val reader = vfs.openRead(VfsUri.of("archive", "zip", basePath(dest) + "src/sub/nested.bin"))
        try {
            val got = reader.readFullyAt(100, 64)
            val expected = f.mem.content("/src/sub/nested.bin").copyOfRange(100, 164)
            assertTrue("随机读必须与源一致", expected.contentEquals(got))
        } finally {
            runCatching { reader.close() }
        }
    }

    @Test
    fun `加密 ZIP 不允许内部增删改名（避免整包重写破坏加密）`() = runBlocking {
        val f = fixture()
        val dest = VfsUri.of("mem", "one", "/edit.zip")
        ArchiveCompressor(f.locator).compress(
            listOf(VfsUri.of("mem", "one", "/src")), dest,
            ArchiveCompressor.Format.ZIP, password = "pw",
        )
        val vfs = mount(f, dest, ArchiveVfs.ArchiveKind.ZIP, "pw")
        vfs.connect()
        val err = runCatching { ZipEditor(vfs, f.locator).rewrite(remove = setOf("src/hello.txt")) }
            .exceptionOrNull()
        assertNotNull("加密包必须拒绝内部改写", err)
        val msg = (err as? VfsException)?.userMessage ?: err?.message.orEmpty()
        assertTrue("应给出可执行的中文文案，实际：$msg", msg.contains("加密"))
    }

    // ------------------------------------------------------------------ 7z

    @Test
    fun `7z 带口令压缩后能被自己读回（C2 回归）`() = runBlocking {
        val f = fixture()
        val dest = VfsUri.of("mem", "one", "/out.7z")
        ArchiveCompressor(f.locator).compress(
            listOf(VfsUri.of("mem", "one", "/src")), dest,
            ArchiveCompressor.Format.SEVEN_Z, password = "pw",
        )
        assertTrue("7z 应有内容", f.mem.content("/out.7z").size > 0)

        val vfs = mount(f, dest, ArchiveVfs.ArchiveKind.SEVEN_Z, "pw")
        vfs.connect()
        val names = vfs.list(VfsUri.of("archive", "7z", basePath(dest) + "src")).map { it.name }
        assertTrue("应能列出 hello.txt，实际 $names", names.contains("hello.txt"))
        assertEquals(
            "hello 加密内容",
            String(readAll(vfs, VfsUri.of("archive", "7z", basePath(dest) + "src/hello.txt"))),
        )
    }

    @Test
    fun `7z 无口令压缩后读回不受影响（回归）`() = runBlocking {
        val f = fixture()
        val dest = VfsUri.of("mem", "one", "/plain.7z")
        ArchiveCompressor(f.locator).compress(
            listOf(VfsUri.of("mem", "one", "/src")), dest,
            ArchiveCompressor.Format.SEVEN_Z,
        )
        val vfs = mount(f, dest, ArchiveVfs.ArchiveKind.SEVEN_Z, null)
        vfs.connect()
        assertEquals(
            "hello 加密内容",
            String(readAll(vfs, VfsUri.of("archive", "7z", basePath(dest) + "src/hello.txt"))),
        )
    }
}
