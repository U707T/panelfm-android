package com.u707t.panelfm.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * 调度器分组：UI 只组合状态，IO / 网络 / 解码严格分离，避免互相拖累。
 *
 * 说明（旧实现的坑，勿再引入）：这里曾提供 `session(name)`，内部用
 * `newSingleThreadContext` 为每个会话建独立线程。该 API 创建的线程**不会自动回收**，
 * 需要显式 `close()`；按连接名调用等于每建一个连接就泄漏一个线程。
 * 各协议实现现在统一用 [vfs]（`limitedParallelism` 的共享池）承担控制通道，
 * 并各自用 `Mutex` 保证「控制连接串行」，不再需要专用线程。
 */
class PanelDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    @OptIn(ExperimentalCoroutinesApi::class)
    val vfs: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(24),
    val decode: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main.immediate,
)
