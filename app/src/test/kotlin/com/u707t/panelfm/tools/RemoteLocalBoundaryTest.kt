package com.u707t.panelfm.tools

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.local.LocalVfs
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RemoteLocalBoundaryTest {
    private fun vfs(base: File) = LocalVfs(VfsEnv(
        appDirs = AppDirs(File(base, "app").absolutePath, File(base, "cache").absolutePath),
        dispatchers = PanelDispatchers(),
    ))
    private fun uri(file: File) = VfsUri.of("local", "root", file.absolutePath)

    @Test fun `ordinary file is allowed but a protected descendant is rejected`() {
        val base = Files.createTempDirectory("remote-boundary").toFile()
        val root = File(base, "share").apply { mkdirs() }
        val file = File(root, "plain.txt").apply { writeText("plain") }
        val privateDir = File(root, "private").apply { mkdirs() }
        val secret = File(privateDir, "key").apply { writeText("fixture") }
        val vfs = vfs(base)
        checkedLocalSharePath(vfs, uri(root), uri(file), listOf(privateDir))
        assertThrows(IllegalArgumentException::class.java) {
            checkedLocalSharePath(vfs, uri(root), uri(secret), listOf(privateDir))
        }
    }

    @Test fun `symlink cannot escape the selected directory`() {
        val base = Files.createTempDirectory("remote-link").toFile()
        val root = File(base, "share").apply { mkdirs() }
        val outside = File(base, "outside.txt").apply { writeText("outside") }
        val link = File(root, "link")
        try { Files.createSymbolicLink(link.toPath(), outside.toPath()) }
        catch (e: Exception) { assumeNoException("Host does not permit symlinks", e); return }
        assertThrows(IllegalArgumentException::class.java) {
            checkedLocalSharePath(vfs(base), uri(root), uri(link), emptyList())
        }
    }
}
