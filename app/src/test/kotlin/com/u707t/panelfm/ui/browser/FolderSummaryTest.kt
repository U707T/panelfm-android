package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSummaryTest {
    private val root = VfsUri.of("local", "root", "/data")

    @Test
    fun `item cap is checked before adding an extra child`() {
        val stats = FolderSummaryAccumulator(itemLimit = 2)
        assertTrue(stats.add(FileMetadata(root.child("a"), "a", false, size = 7)))
        assertTrue(stats.add(FileMetadata.dir(root.child("folder"), "folder")))
        assertFalse(stats.add(FileMetadata(root.child("b"), "b", false, size = 9)))

        val summary = stats.snapshot()
        assertEquals(1, summary.files)
        assertEquals(1, summary.dirs)
        assertEquals(7L, summary.bytes)
        assertTrue(summary.truncated)
        assertTrue(summary.countIncomplete)
    }

    @Test
    fun `unknown sizes and unreadable subdirectories are not represented as exact statistics`() {
        val stats = FolderSummaryAccumulator()
        assertTrue(stats.add(FileMetadata(root.child("unknown"), "unknown", false, size = -1)))
        stats.markDirectoryFailure()

        val summary = stats.snapshot()
        assertEquals(1, summary.unknownSizeItems)
        assertEquals(0L, summary.bytes)
        assertEquals(1, summary.failedDirs)
        assertTrue(summary.countIncomplete)
    }

    @Test
    fun `complete known metadata remains exact`() {
        val stats = FolderSummaryAccumulator()
        stats.add(FileMetadata(root.child("a"), "a", false, size = 3))
        stats.add(FileMetadata(root.child("b"), "b", false, size = 5))

        val summary = stats.snapshot()
        assertEquals(8L, summary.bytes)
        assertEquals(2, summary.files)
        assertFalse(summary.truncated)
        assertFalse(summary.countIncomplete)
        assertEquals(0, summary.unknownSizeItems)
    }
}
