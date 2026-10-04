package com.u707t.panelfm.core.vfs

import kotlinx.coroutines.flow.StateFlow

/**
 * 统一虚拟文件系统：上层 UI 与传输引擎完全不感知「本地 / 网络」差异。
 * 实现必须：① 如实声明 [capabilities]；② 所有阻塞操作在自己的调度器上执行；③ 支持取消。
 */
interface VirtualFileSystem : AutoCloseable {

    /** 会话标识（连接配置派生） */
    val id: String

    val scheme: String

    /** 展示名，如 "NAS-SFTP" */
    val label: String

    val capabilities: VfsCapabilities

    val state: StateFlow<VfsState>

    /** 幂等懒连接（多窗格并发调用只连一次） */
    suspend fun connect()

    suspend fun list(uri: VfsUri, options: ListOptions = ListOptions()): List<FileMetadata>

    suspend fun stat(uri: VfsUri): FileMetadata

    suspend fun exists(uri: VfsUri): Boolean = runCatching { stat(uri) }.isSuccess

    suspend fun mkdir(uri: VfsUri, parents: Boolean = true)

    suspend fun touch(uri: VfsUri) {
        throw VfsException.Unsupported("$scheme 不支持创建空文件")
    }

    suspend fun delete(uris: List<VfsUri>, onProgress: ProgressCallback? = null)

    /** 重命名 / 同协议移动；返回是否成功 */
    suspend fun rename(from: VfsUri, to: VfsUri): Boolean

    /** 服务端复制；不支持返回 false（引擎降级为流式泵） */
    suspend fun serverSideCopy(from: VfsUri, to: VfsUri): Boolean = false

    fun openRead(uri: VfsUri, offset: Long = 0L, length: Long = -1L): VfsReader

    suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long = 0L): VfsWriter

    suspend fun space(uri: VfsUri): SpaceInfo? = null

    suspend fun setPermissions(uri: VfsUri, mode: Int) {
        throw VfsException.Unsupported("$scheme 不支持修改权限")
    }

    /**
     * 设置文件修改时间（MT 的「保留文件时间」）。
     *
     * 传输 / 解压 / 下载完成后调用，让目标文件保留源文件的 mtime；
     * 不支持时抛 [VfsException.Unsupported]，由调用方静默忽略（不应中断传输）。
     */
    suspend fun setModified(uri: VfsUri, epochMillis: Long) {
        throw VfsException.Unsupported("$scheme 不支持设置修改时间")
    }

    override fun close() {}
}
