package com.u707t.panelfm.core.common

import java.io.File

/**
 * 应用私有目录集合。core 模块不持有 Context，统一由 :app 在启动时构造后注入。
 */
data class AppDirs(val filesDir: String, val cacheDir: String) {

    val tmpDir: String get() = "$cacheDir/tmp"
    val thumbsDir: String get() = "$cacheDir/thumbs"
    val relayDir: String get() = "$cacheDir/relay"
    val logsDir: String get() = "$cacheDir/logs"
    val trashDir: String get() = "$filesDir/trash"
    val dbPath: String get() = "$filesDir/panel.db"

    fun ensure() {
        listOf(filesDir, cacheDir, tmpDir, thumbsDir, relayDir, logsDir, trashDir).forEach {
            runCatching { File(it).mkdirs() }
        }
    }

    fun tempFile(name: String): File = File(tmpDir, name)
}
