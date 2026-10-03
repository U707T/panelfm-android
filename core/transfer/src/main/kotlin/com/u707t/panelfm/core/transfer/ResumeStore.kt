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
    val validator: String?,
    val updatedAt: Long,
)

interface ResumeStore {
    suspend fun save(entry: ResumeEntry)
    suspend fun find(taskId: String, itemIndex: Int): ResumeEntry?
    suspend fun clear(taskId: String, itemIndex: Int)
    suspend fun clearTask(taskId: String)
    suspend fun all(): List<ResumeEntry>
}

class InMemoryResumeStore : ResumeStore {
    private val map = mutableMapOf<String, ResumeEntry>()
    private fun key(taskId: String, index: Int) = "$taskId#$index"

    override suspend fun save(entry: ResumeEntry) { map[key(entry.taskId, entry.itemIndex)] = entry }
    override suspend fun find(taskId: String, itemIndex: Int): ResumeEntry? = map[key(taskId, itemIndex)]
    override suspend fun clear(taskId: String, itemIndex: Int) { map.remove(key(taskId, itemIndex)) }
    override suspend fun clearTask(taskId: String) { map.keys.filter { it.startsWith("$taskId#") }.forEach { map.remove(it) } }
    override suspend fun all(): List<ResumeEntry> = map.values.toList()
}
