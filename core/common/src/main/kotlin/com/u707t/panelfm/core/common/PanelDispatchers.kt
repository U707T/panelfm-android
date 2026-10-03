package com.u707t.panelfm.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * 调度器分组：UI 只组合状态，IO / 网络 / 解码严格分离，避免互相拖累。
 * session 调度器用于「控制通道」（如 FTP 控制连接必须串行），由 VFS 按会话创建。
 */
class PanelDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    @OptIn(ExperimentalCoroutinesApi::class)
    val vfs: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(24),
    val decode: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    fun session(name: String): CoroutineDispatcher = kotlinx.coroutines.newSingleThreadContext("vfs-$name")
}
