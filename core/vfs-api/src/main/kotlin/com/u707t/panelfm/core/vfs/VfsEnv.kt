package com.u707t.panelfm.core.vfs

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.model.ConnectionConfig

/** VFS 实现所需的运行环境（core 模块不依赖 Context）。 */
data class VfsEnv(
    val appDirs: AppDirs,
    val dispatchers: PanelDispatchers,
    val userAgent: String = "PanelFM/0.1 (Android)",
    val timeoutMs: Long = 30_000L,
    /** 是否允许访问局域网（Android 17 运行期授权状态）—— 未授权时网络协议给出明确错误 */
    val localNetworkAllowed: () -> Boolean = { true },
)

interface VfsFactory {
    val scheme: String
    fun create(config: ConnectionConfig, secret: String?, env: VfsEnv): VirtualFileSystem
}
