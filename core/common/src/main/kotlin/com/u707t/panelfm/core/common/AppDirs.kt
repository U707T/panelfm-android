package com.u707t.panelfm.core.common

import java.io.File

/**
 * 应用私有目录集合。core 模块不持有 Context，统一由 :app 在启动时构造后注入。
 */
data class AppDirs(val filesDir: String, val cacheDir: String) {

    companion object {
        /** 「接收文件」子目录名（LocalVolumes 的「接收文件」卷也引用它）。 */
        const val INCOMING_SUBDIR = "incoming"
    }

    val tmpDir: String get() = "$cacheDir/tmp"
    val thumbsDir: String get() = "$cacheDir/thumbs"
    val relayDir: String get() = "$cacheDir/relay"
    val logsDir: String get() = "$cacheDir/logs"

    /** 「用其他应用打开」的落盘中转站；同时作为本地卷「接收文件」暴露给用户。 */
    val incomingDir: String get() = "$cacheDir/$INCOMING_SUBDIR"

    val trashDir: String get() = "$filesDir/trash"
    /** 导入的 SSH 私钥（应用私有，chmod 600 思路：仅本应用可读） */
    val keysDir: String get() = "$filesDir/keys"
    val dbPath: String get() = "$filesDir/panel.db"

    fun ensure() {
        listOf(filesDir, cacheDir, tmpDir, thumbsDir, relayDir, logsDir, incomingDir, trashDir, keysDir).forEach {
            runCatching { File(it).mkdirs() }
        }
    }

    fun tempFile(name: String): File = File(tmpDir, name)
}
