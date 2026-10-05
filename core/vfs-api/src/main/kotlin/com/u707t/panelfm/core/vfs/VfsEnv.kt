package com.u707t.panelfm.core.vfs

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.model.ConnectionConfig

/** VFS 实现所需的运行环境（core 模块不依赖 Context）。 */
data class VfsEnv(
    val appDirs: AppDirs,
    val dispatchers: PanelDispatchers,
    /** 全局 User-Agent（WebDAV 等协议使用）；用 lambda 桥接，设置里改完即时生效 */
    val userAgent: () -> String = { "PanelFM/0.1 (Android)" },
    val timeoutMs: Long = 30_000L,
    /** 是否允许访问局域网（Android 17 运行期授权状态）—— 未授权时网络协议给出明确错误 */
    val localNetworkAllowed: () -> Boolean = { true },
    /**
     * 全局「默认信任自签证书」开关（设置页写入）。
     *
     * 用 lambda 桥接的原因与 [userAgent] 相同：设置改完必须即时生效，
     * 不能在构造 VfsEnv 时把值定死。**仅**作为「连接自己没写该选项」时的兜底默认值，
     * 连接级显式设置（`OPT_TRUST_SELF_SIGNED`）永远优先。
     */
    val trustSelfSignedDefault: () -> Boolean = { false },
)

interface VfsFactory {
    val scheme: String
    fun create(config: ConnectionConfig, secret: String?, env: VfsEnv): VirtualFileSystem
}
