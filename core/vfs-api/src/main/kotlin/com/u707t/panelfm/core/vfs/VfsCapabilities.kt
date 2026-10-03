package com.u707t.panelfm.core.vfs

/** 断点续传能力 */
enum class Resumability { NONE, RANGE, MULTIPART, CHUNKED }

/**
 * 能力位：各协议差异很大，UI 与传输引擎据此决定「服务端快路径」还是「降级为流式泵」。
 * 协议实现必须如实声明，不要乐观声明。
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
    /** 支持流式/分页列目录（大目录边收边渲染） */
    val streamingList: Boolean = false,
    /** 可写 */
    val writable: Boolean = true,
) {
    override fun toString(): String =
        "caps(rename=$rename, copy=$serverSideCopy, rRead=$rangeRead, rWrite=$rangeWrite, " +
            "resumable=$resumable, perms=$permissions, space=$space)"
}
