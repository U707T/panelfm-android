package com.u707t.panelfm.core.vfs.local

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `local://incoming/...` 是「用其他应用打开」的落盘卷（见 app 的 IncomingFileReceiver）。
 * 它必须映射到 `cacheDir/incoming`，而不是整个 cacheDir；否则「接收文件」卷会把缩略图、
 * 日志、临时文件一起暴露出来。
 */
class LocalVfsIncomingAuthorityTest {

    private fun fixture(): Pair<LocalVfs, AppDirs> {
        val dirs = AppDirs(
            Files.createTempDirectory("panelfm-files").toString(),
            Files.createTempDirectory("panelfm-cache").toString(),
        )
        val env = VfsEnv(
            appDirs = dirs,
            dispatchers = PanelDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.Default, Dispatchers.Default),
        )
        return LocalVfs(env) to dirs
    }

    @Test
    fun `incoming 卷只映射到 cache-incoming 子目录`() {
        val (vfs, dirs) = fixture()
        val uri = VfsUri.of("local", LocalVolumes.AUTHORITY_INCOMING, "/a.txt")

        assertEquals(File(dirs.incomingDir, "a.txt").absolutePath, vfs.absolutePath(uri))
        assertEquals(uri, vfs.uriOf(LocalVolumes.AUTHORITY_INCOMING, File(dirs.incomingDir, "a.txt").absolutePath))
    }

    @Test
    fun `incoming 卷根目录等于 incomingDir`() {
        val (vfs, dirs) = fixture()
        val root = VfsUri.of("local", LocalVolumes.AUTHORITY_INCOMING, "/")

        assertEquals(dirs.incomingDir, vfs.absolutePath(root))
        assertEquals(root, vfs.uriOf(LocalVolumes.AUTHORITY_INCOMING, dirs.incomingDir))
    }

    @Test
    fun `incoming 卷不能反解到整个 cacheDir`() {
        val (vfs, dirs) = fixture()
        // 关键回归：旧实现曾用整个 cacheDir 做卷映射；「接收文件」卷必须是 cacheDir/incoming 子目录。
        assertEquals(
            VfsUri.of("local", LocalVolumes.AUTHORITY_INCOMING, "/thumbs/x.png"),
            vfs.uriOf(LocalVolumes.AUTHORITY_INCOMING, File(dirs.incomingDir, "thumbs/x.png").absolutePath),
        )
        assertEquals(
            File(dirs.incomingDir, "thumbs/x.png").absolutePath,
            vfs.absolutePath(VfsUri.of("local", LocalVolumes.AUTHORITY_INCOMING, "/thumbs/x.png")),
        )
    }
}
