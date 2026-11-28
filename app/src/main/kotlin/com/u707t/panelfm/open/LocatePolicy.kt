package com.u707t.panelfm.open

/**
 * 「反查结果 → 是否定位」的判定，独立成纯函数以便 JVM 单测。
 *
 * 为什么单独抽：`ContentPathResolver` 需要 ContentResolver（Android），但
 * 「什么情况下该定位、什么情况下该降级复制」是策略问题，必须能在没有设备的机器上钉住。
 * 一旦这里的判定出错，用户会遇到「分享进来的文件点开一片空白」这种最难排查的问题。
 */
internal sealed interface LocateDecision {
    /** 定位到这个 VfsUri（卷 authority + 卷内相对路径）。 */
    data class Locate(val authority: String, val relativePath: String) : LocateDecision

    /** 只能用接收目录副本。 */
    data object Copy : LocateDecision
}

/**
 * @param enabled 设置里的「外部打开时定位原文件」
 * @param absolutePath 反查到的真实路径（反查失败传 null）
 */
internal fun decideLocate(enabled: Boolean, absolutePath: String?): LocateDecision {
    if (!enabled) return LocateDecision.Copy
    // Protected / Unknown 都走复制：路径存在但我们读不到时，定位过去只会看到空目录。
    if (localPathVisibility(absolutePath) != LocalPathVisibility.BROWSABLE) return LocateDecision.Copy
    val volume = absolutePathToVolume(absolutePath) ?: return LocateDecision.Copy
    return LocateDecision.Locate(volume.authority, volume.relativePath)
}

/**
 * 批量接收的最终状态决策。
 *
 * 规则：一个都没成功 → 显示第一条失败原因；否则进「收到 N 个文件」列表。
 * 失败的项不吞掉，跟成功项一起交给 UI 展示。
 */
internal fun batchResultOf(
    items: List<IncomingState.BatchItem>,
    failed: List<String>,
): IncomingState = if (items.isEmpty()) {
    IncomingState.Error(failed.firstOrNull() ?: "接收失败")
} else {
    IncomingState.Batch(items, failed)
}
