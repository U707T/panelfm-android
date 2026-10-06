package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.vfs.ByteArrayReader
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.ListOptions
import com.u707t.panelfm.core.vfs.ProgressCallback
import com.u707t.panelfm.core.vfs.Resumability
import com.u707t.panelfm.core.vfs.VfsCapabilities
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsLocator
import com.u707t.panelfm.core.vfs.VfsReader
import com.u707t.panelfm.core.vfs.VfsState
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsWriter
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream

/** 内存 VFS：单测里替代真实协议，验证计划器/引擎逻辑。 */
class FakeVfs(
    override val scheme: String = "fake",
    override val label: String = "fake",
    override val capabilities: VfsCapabilities = VfsCapabilities(
        rename = true,
        serverSideCopy = true,
        rangeRead = true,
        rangeWrite = true,
        resumable = Resumability.RANGE,
        recursiveDelete = true,
        touch = true,
    ),
) : VirtualFileSystem {

    class Node(val isDirectory: Boolean, var size: Long = 0, val content: ByteArrayOutputStream = ByteArrayOutputStream())

    val nodes = linkedMapOf<String, Node>("/" to Node(true))
    var failOpenWrite: Boolean = false
    var failDelete: Boolean = false

    /**
     * 每次 `read()` 前的模拟耗时（毫秒，默认 0）。
     *
     * 为什么需要：单测里「取消一个**正在运行**的任务」必须先确保任务真的还在传 ——
     * 内存复制极快，CI 上 300 KB 可能在测试线程调用 `cancel()` 之前就复制完（任务先变
     * Done），等待「已取消」的断言会超时。给读加一点延迟能把取消点钉在传输过程中。
     */
    var readDelayMs: Long = 0

    /**
     * 模拟「delete 调用成功返回、但目标其实还在」的远端实现。
     *
     * 为什么需要它：`TransferTask.deleteForOverwrite()` 有「删除后再次 stat 确认消失」的
     * 保险（`delete() 成功返回 ≠ 目标已消失`），但旧测试只覆盖了「delete 抛异常」分支 ——
     * 变异测试（删掉那次 stat）全绿。这个开关用来把另一条分支也测上。
     */
    var silentlyIgnoreDelete: Boolean = false
    var failServerSideCopy: Boolean = false
    var failRename: Boolean = false
    var openedReaders: Int = 0
    var closedReaders: Int = 0

    override val id: String = "fake:${hashCode()}"
    private val _state = MutableStateFlow<VfsState>(VfsState.Ready)
    override val state: StateFlow<VfsState> = _state

    fun dir(path: String): FakeVfs {
        nodes[path] = Node(true)
        return this
    }

    fun file(path: String, size: Long): FakeVfs {
        val node = Node(false, size)
        if (size in 0..1_000_000) {
            node.content.write(ByteArray(size.toInt()) { (it % 251).toByte() })
        }
        nodes[path] = node
        return this
    }

    override suspend fun connect() = Unit

    override suspend fun list(uri: VfsUri, options: ListOptions): List<FileMetadata> {
        if (nodes[uri.path]?.isDirectory != true) throw VfsException.NotFound(uri)
        val prefix = uri.path.trimEnd('/') + "/"
        return nodes.entries
            .filter { it.key.startsWith(prefix) && it.key.trimEnd('/') != uri.path.trimEnd('/') }
            .filter { it.key.removePrefix(prefix).count { ch -> ch == '/' } == 0 }
            .map { (path, node) ->
                FileMetadata(
                    uri = uri.child(path.substringAfterLast('/')),
                    name = path.substringAfterLast('/'),
                    isDirectory = node.isDirectory,
                    size = if (node.isDirectory) -1 else node.size,
                )
            }
    }

    override suspend fun stat(uri: VfsUri): FileMetadata {
        val node = nodes[uri.path] ?: throw VfsException.NotFound(uri)
        return FileMetadata(
            uri = uri,
            name = uri.name,
            isDirectory = node.isDirectory,
            size = if (node.isDirectory) -1 else node.size,
        )
    }

    override suspend fun mkdir(uri: VfsUri, parents: Boolean) {
        nodes[uri.path] = Node(true)
    }

    override suspend fun touch(uri: VfsUri) {
        nodes[uri.path] = Node(false, 0)
    }

    override suspend fun delete(uris: List<VfsUri>, onProgress: ProgressCallback?) {
        if (failDelete) throw VfsException.Permission("测试：删除目标失败")
        if (silentlyIgnoreDelete) return
        uris.forEach { u ->
            nodes.keys
                .filter { it == u.path || it.startsWith(u.path.trimEnd('/') + "/") }
                .toList()
                .forEach { nodes.remove(it) }
        }
    }

    override suspend fun rename(from: VfsUri, to: VfsUri): Boolean {
        if (failRename) return false
        val node = nodes.remove(from.path) ?: return false
        nodes[to.path] = node
        return true
    }

    override suspend fun serverSideCopy(from: VfsUri, to: VfsUri): Boolean {
        if (failServerSideCopy) return false
        val node = nodes[from.path] ?: return false
        val copy = Node(node.isDirectory, node.size)
        copy.content.write(node.content.toByteArray())
        nodes[to.path] = copy
        return true
    }

    override fun openRead(uri: VfsUri, offset: Long, length: Long): VfsReader {
        val delegate = ByteArrayReader(nodes[uri.path]?.content?.toByteArray() ?: ByteArray(0))
        openedReaders++
        if (offset > 0) {
            kotlinx.coroutines.runBlocking { delegate.seek(offset) }
        }
        val delayMs = readDelayMs
        return object : VfsReader by delegate {
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
                return delegate.read(buffer, offset, length)
            }

            override fun close() {
                closedReaders++
                delegate.close()
            }
        }
    }

    override suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long): VfsWriter {
        if (failOpenWrite) throw VfsException.Permission("测试：无法打开目标写入流")
        val node = nodes.getOrPut(uri.path) { Node(false, size ?: 0) }
        if (offset == 0L) {
            node.content.reset()
            node.size = 0
        }
        return object : VfsWriter {
            private var written = offset
            override val writtenBytes: Long get() = written
            override val resumable: Resumability get() = Resumability.RANGE

            override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
                node.content.write(buffer, offset, length)
                written += length
            }

            override suspend fun flush() = Unit
            override suspend fun commit() {
                node.size = written
            }

            override suspend fun abort() = Unit
        }
    }
}

class FakeLocator(private val map: Map<String, VirtualFileSystem>) : VfsLocator {
    override fun find(uri: VfsUri): VirtualFileSystem? = map[uri.authority]
}
