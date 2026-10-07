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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 压缩的「取消」语义（审计 U4）回归：
 *  - 取消必须以 CancellationException **原样上抛** —— 旧实现会把它包成
 *    VfsException「压缩失败：Job was cancelled」，UI 会把用户主动取消误报成失败（与 U5 同类）；
 *  - 取消后**不得 commit 半成品**：本地实现由 abort() 删掉 `.name.panelfm.part`（此处的内存实现
 *    以「不写入目标」表示同样的承诺）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArchiveCompressorCancelTest {

    private class Mem : VirtualFileSystem {
        override val id = "mem"
        override val scheme = "mem"
        override val label = "mem"
        override val capabilities = VfsCapabilities(rename = true, rangeRead = true, rangeWrite = true, writable = true)
        val files = linkedMapOf<String, ByteArray>()
        private val _state = MutableStateFlow(VfsState.Ready)
        override val state: StateFlow<VfsState> = _state

        fun put(path: String, data: ByteArray) { files[path] = data }

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
            val bytes = ByteArrayOutputStream()
            return object : VfsWriter {
                override val writtenBytes: Long get() = bytes.size().toLong()
                override suspend fun write(buffer: ByteArray, offset: Int, length: Int) { bytes.write(buffer, offset, length) }
                override suspend fun flush() = Unit
                override suspend fun commit() { files[uri.path] = bytes.toByteArray() }
                override suspend fun abort() = Unit
            }
        }
    }

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun env() = VfsEnv(AppDirs("/tmp/panelfm-test", "/tmp/panelfm-test"), PanelDispatchers())

    private fun fixture(): Pair<Mem, VfsLocator> {
        val mem = Mem()
        // 300KB：确保按 64KB 分块、中途取消点一定命中
        mem.put("/src/big.bin", ByteArray(300 * 1024) { (it % 251).toByte() })
        val locator = object : VfsLocator {
            override fun find(uri: VfsUri): VirtualFileSystem? = if (uri.authority == "one") mem else null
        }
        return mem to locator
    }

    @Test
    fun `ZIP 压缩中途取消：原样上抛 CancellationException 且不 commit 半成品`() = runBlocking {
        val (mem, locator) = fixture()
        val dest = VfsUri.of("mem", "one", "/out.zip")
        var progressCalls = 0
        val err = runCatching {
            ArchiveCompressor(locator).compress(
                listOf(VfsUri.of("mem", "one", "/src")), dest,
                ArchiveCompressor.Format.ZIP,
                onProgress = { _, _ ->
                    progressCalls++
                    throw CancellationException("用户取消")
                },
            )
        }.exceptionOrNull()

        assertTrue("必须是 CancellationException，实际 ${err?.javaClass?.name}", err is CancellationException)
        assertFalse("取消不得被包成 VfsException（旧实现在此误报「压缩失败」）", err is VfsException)
        assertTrue("取消前应收到至少一次进度回调（逐块上报）", progressCalls >= 1)
        assertNull("取消后不得写目标", mem.files["/out.zip"])
    }

    @Test
    fun `加密 ZIP 压缩中途取消：原样上抛 CancellationException 且不 commit 半成品`() = runBlocking {
        val (mem, locator) = fixture()
        val dest = VfsUri.of("mem", "one", "/enc.zip")
        var progressCalls = 0
        val err = runCatching {
            ArchiveCompressor(locator).compress(
                listOf(VfsUri.of("mem", "one", "/src")), dest,
                ArchiveCompressor.Format.ZIP,
                onProgress = { _, _ ->
                    progressCalls++
                    throw CancellationException("用户取消")
                },
                password = "pw",
            )
        }.exceptionOrNull()

        assertTrue("必须是 CancellationException，实际 ${err?.javaClass?.name}", err is CancellationException)
        assertFalse("取消不得被包成 VfsException", err is VfsException)
        assertTrue("加密路径也应按块上报（大文件中途可取消）", progressCalls >= 1)
        assertNull("取消后不得写目标", mem.files["/enc.zip"])
    }
}
