package com.u707t.panelfm.core.transfer

import com.u707t.panelfm.core.vfs.VfsUri

/** 断点续传状态（持久化实现在 :core:data）。 */
data class ResumeEntry(
    val taskId: String,
    val itemIndex: Int,
    val source: VfsUri,
    val dest: VfsUri,
    val tempUri: VfsUri?,
    val offset: Long,
    val total: Long,
    /** 源文件修改时间（秒级字符串）——用于校验「断点属于同一个源文件」，源被替换时不误续 */
    val validator: String?,
    val updatedAt: Long,
)

/**
 * 断点续传状态存储。
 *
 * 关键语义（修复「取消后再传不会从断点继续」）：
 *  - 按 **(source, dest) 路径** 查找，而不是按任务 id —— 任务每次入队都是新的 UUID，
 *    按任务 id 查找会让续传永远命中不了（旧实现的隐蔽 bug）。
 *  - 取消任务时**不清空**记录（本地 / SFTP / SMB 目标会保留 `.part` 供下次续传）；
 *    单文件完成后用 [clearFor] 清理；过期记录由 [purgeStale] 定期清理。
 */
interface ResumeStore {
    suspend fun save(entry: ResumeEntry)

    /** 查找与 (source → dest) 匹配的最近一条断点记录 */
    suspend fun findFor(source: VfsUri, dest: VfsUri): ResumeEntry?

    /** 单文件完成后清理对应断点记录 */
    suspend fun clearFor(source: VfsUri, dest: VfsUri)

    /** 清理早于 [before] 的过期记录（防止取消/失败留下的记录无限累积） */
    suspend fun purgeStale(before: Long)
}

class InMemoryResumeStore : ResumeStore {
    private val map = mutableMapOf<String, ResumeEntry>()

    private fun key(entry: ResumeEntry) = "${entry.source}→${entry.dest}"

    override suspend fun save(entry: ResumeEntry) {
        map[key(entry)] = entry
    }

    override suspend fun findFor(source: VfsUri, dest: VfsUri): ResumeEntry? =
        map.values.filter { it.source == source && it.dest == dest }.maxByOrNull { it.updatedAt }

    override suspend fun clearFor(source: VfsUri, dest: VfsUri) {
        map.values.removeAll { it.source == source && it.dest == dest }
    }

    override suspend fun purgeStale(before: Long) {
        map.values.removeAll { it.updatedAt < before }
    }
}
