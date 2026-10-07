package com.u707t.panelfm.core.vfs

/** 断点续传能力 */
enum class Resumability { NONE, RANGE, MULTIPART, CHUNKED }

/**
 * 能力位：各协议差异很大，UI 与传输引擎据此决定「服务端快路径」还是「降级为流式泵」。
 * 协议实现必须如实声明，不要乐观声明。
 *
 * **消费现状（第 6 批核实）**：当前被实际读取的只有 4 个位——[rename]、[serverSideCopy]
 * （FileOperationPlanner）、[resumable]（TransferTask）、[permissions]（浏览器 / 编辑器）。
 * 其余位（rangeRead / rangeWrite / space / symlinks / recursiveDelete / touch / setModified /
 * streamingList / writable）暂无消费方，属**预留能力位**：仍须如实填报，但不要指望它们
 * 驱动任何行为；各协议填报差异（如 recursiveDelete 的 true / false）当前也无人验证。
 * 对应方法本身（touch / space / setModified 等）由调用方直接调用，不支持时按 Unsupported 处理。
 */
data class VfsCapabilities(
    /** 服务端重命名 / 同协议移动 */
    val rename: Boolean = false,
    /** 服务端复制（不消耗本地流量） */
    val serverSideCopy: Boolean = false,
    /** 支持按偏移读（断点续传下载 / 流媒体播放） */
    val rangeRead: Boolean = true,
    /** 支持按偏移写（断点续传上传） */
    val rangeWrite: Boolean = false,
    val resumable: Resumability = Resumability.NONE,
    /** 可修改权限（chmod） */
    val permissions: Boolean = false,
    /** 能拿到容量信息 */
    val space: Boolean = false,
    val symlinks: Boolean = false,
    /** delete 自带递归 */
    val recursiveDelete: Boolean = false,
    /** 能创建空文件 */
    val touch: Boolean = false,
    /** 能设置文件修改时间（MT「保留文件时间」） */
    val setModified: Boolean = false,
    /** 支持流式/分页列目录（大目录边收边渲染） */
    val streamingList: Boolean = false,
    /** 可写 */
    val writable: Boolean = true,
) {
    override fun toString(): String =
        "caps(rename=$rename, copy=$serverSideCopy, rRead=$rangeRead, rWrite=$rangeWrite, " +
            "resumable=$resumable, perms=$permissions, space=$space)"
}
