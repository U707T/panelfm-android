package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.vfs.VfsUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPickDirectoryTest {
    private val first = VfsUri.of("local", "root", "/source/a.txt")
    private val second = VfsUri.of("local", "root", "/source/b.txt")
    private val target = VfsUri.of("local", "root", "/destination")

    @Test
    fun `navigating after choosing operation preserves original source snapshot`() {
        val mutableSources = mutableListOf(first)
        val snapshot = capturePickDirSources(PaneSide.LEFT, mutableSources)
        mutableSources += second

        val result = pickDirSelection(PickDirPurpose.COPY_TO, snapshot.sourceSide, snapshot.sources, target)
        assertEquals(PaneSide.LEFT, result.sourceSide)
        assertEquals(listOf(first), result.sources)
        assertEquals(target, result.destDir)
    }

    @Test
    fun `archive up leaves to host only at the archive root`() {
        assertTrue(archiveUpLeavesToHost(innerPath = ""))
        assertFalse(archiveUpLeavesToHost(innerPath = "dir"))
        assertFalse(archiveUpLeavesToHost(innerPath = "parent/dir"))
    }
}
