package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsUris

/**
 * 「后台」段的数据整理（纯逻辑，可单测）。
 *
 * MT 语义：**后台 = 网络挂载** —— 只收网络协议路径（本地 / 压缩包不属于这里），
 * 且每个挂载只显示一行（取该挂载最近访问的一条路径）。
 *
 * @param uris    最近访问（按时间倒序）
 * @param exclude 需要排除的路径（当前两个窗格正在浏览的 uri 字符串）
 * @param exists  路径是否仍有效（挂载可用的过滤）
 * @param limit   最多返回几条（MT 抽屉一屏内；默认 6）
 */
fun drawerNetworkMounts(
    uris: List<VfsUri>,
    exclude: Set<String>,
    exists: (VfsUri) -> Boolean,
    limit: Int = 6,
): List<VfsUri> = uris
    .filter { uri ->
        // 只放网络挂载：ConnectionType 里除 LOCAL 之外的协议（archive 等也不属于）
        ConnectionType.ofScheme(uri.scheme)?.let { it != ConnectionType.LOCAL } == true &&
            uri.toString() !in exclude &&
            exists(uri)
    }
    // 每个网络挂载只留一行（取该挂载最近访问的那条；无连接号时退回按路径去重）
    .distinctBy { VfsUris.connectionId(it) ?: it.toString() }
    .take(limit)
