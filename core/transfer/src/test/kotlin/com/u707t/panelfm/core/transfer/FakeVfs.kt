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

    override val id: String = "fake:${hashCode()}"
    private val _state = MutableStateFlow<VfsState>(VfsState.Ready)
    override val state: StateFlow<VfsState> = _state

    fun dir(path: String): FakeVfs {
        nodes[path] = Node(true)
        return this
    }

    fun file(path: String, size: Long): FakeVfs {
        nodes[path] = Node(false, size)
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
        uris.forEach { u ->
            nodes.keys
                .filter { it == u.path || it.startsWith(u.path.trimEnd('/') + "/") }
                .toList()
                .forEach { nodes.remove(it) }
        }
    }

    override suspend fun rename(from: VfsUri, to: VfsUri): Boolean {
        val node = nodes.remove(from.path) ?: return false
        nodes[to.path] = node
        return true
    }

    override suspend fun serverSideCopy(from: VfsUri, to: VfsUri): Boolean {
        val node = nodes[from.path] ?: return false
        nodes[to.path] = Node(node.isDirectory, node.size)
        return true
    }

    override fun openRead(uri: VfsUri, offset: Long, length: Long): VfsReader =
        ByteArrayReader(nodes[uri.path]?.content?.toByteArray() ?: ByteArray(0))

    override suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long): VfsWriter {
        val node = nodes.getOrPut(uri.path) { Node(false, size ?: 0) }
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
