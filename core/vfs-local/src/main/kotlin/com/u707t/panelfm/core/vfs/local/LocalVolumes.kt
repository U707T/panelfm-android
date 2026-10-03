package com.u707t.panelfm.core.vfs.local

import android.content.Context
import android.os.Build
import com.u707t.panelfm.core.vfs.VfsUri
import java.io.File

/** 本机可浏览的卷（authority 标识 → 真实绝对路径）。 */
data class LocalVolume(val authority: String, val label: String, val path: String)

object LocalVolumes {

    const val AUTHORITY_ROOT = "root"
    const val AUTHORITY_EMULATED = "emulated"
    const val AUTHORITY_APP = "app"

    /** 内部存储绝对路径 */
    val emulatedPath: String = "/storage/emulated/0"

    fun volumes(context: Context): List<LocalVolume> {
        val list = mutableListOf<LocalVolume>()
        list += LocalVolume(AUTHORITY_EMULATED, "内部存储", emulatedPath)
        list += LocalVolume(AUTHORITY_APP, "应用私有目录", context.filesDir.absolutePath)
        // 外置卡：从 getExternalFilesDirs 反推卷根
        val dirs = runCatching { context.getExternalFilesDirs(null) }.getOrNull() ?: emptyArray()
        dirs.filterNotNull().forEach { dir ->
            val root = extractVolumeRoot(dir.absolutePath)
            if (root != null && root != emulatedPath && File(root).canRead() && list.none { it.path == root }) {
                list += LocalVolume("vol:" + root.trim('/').replace('/', '_'), "外置存储 ${File(root).name}", root)
            }
        }
        if (Build.VERSION.SDK_INT < 30 || File("/").canRead()) {
            list += LocalVolume(AUTHORITY_ROOT, "根目录 /", "/")
        }
        return list
    }

    private fun extractVolumeRoot(absolute: String): String? {
        // /storage/XXXX-XXXX/Android/data/<pkg>/files → /storage/XXXX-XXXX
        val marker = "/Android/"
        val idx = absolute.indexOf(marker)
        return if (idx > 0) absolute.substring(0, idx) else null
    }

    fun uri(volume: LocalVolume, path: String = "/"): VfsUri =
        VfsUri.of("local", volume.authority, path)
}
