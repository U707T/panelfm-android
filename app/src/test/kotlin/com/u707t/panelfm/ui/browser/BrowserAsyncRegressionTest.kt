package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.connectionConfigForUri
import com.u707t.panelfm.core.model.ConflictInfo
import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.transfer.TaskState
import com.u707t.panelfm.core.vfs.VfsUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BrowserAsyncRegressionTest {
    @Test
    fun `an old conflict reply cannot target the next waiting item`() {
        val a = waiting(1)
        val b = waiting(2)
        assertSame(a, conflictWithToken(listOf(b, a), 1) { it })
        assertNull(conflictWithToken(listOf(TaskState.Cancelled, b), 1) { it })
        assertSame(b, conflictWithToken(listOf(b), 2) { it })
    }

    @Test
    fun `failed skipped and cancelled moves retain clipboard while full success removes only moved sources`() {
        val clipboard = BrowserClipboard()
        val a = VfsUri.parse("local://emulated/a.txt")
        val b = VfsUri.parse("local://emulated/b.txt")
        clipboard.set(listOf(a, b))
        val revision = clipboard.snapshot().revision
        val done = TaskState.Done(1, 0, 0, 10, 20)
        for (state in listOf(TaskState.Cancelled, TaskState.Failed("IO"), done.copy(skipped = 1), done.copy(failed = 1))) {
            clipboard.consumeMoved(revision, listOf(a), state)
            assertEquals(listOf(a, b), clipboard.sources.value)
        }
        clipboard.consumeMoved(revision, listOf(a), done)
        assertEquals(listOf(b), clipboard.sources.value)
    }

    @Test
    fun `move waiting for confirmation cannot double enqueue and cancellation permits retry`() {
        val clipboard = BrowserClipboard()
        val source = VfsUri.parse("local://emulated/a.txt")
        clipboard.set(listOf(source))
        val revision = clipboard.snapshot().revision
        assertTrue(clipboard.reserveMove(revision))
        assertFalse(clipboard.reserveMove(revision))
        clipboard.releaseMove(revision)
        assertEquals(listOf(source), clipboard.sources.value)
        assertTrue(clipboard.reserveMove(revision))
    }

    @Test
    fun `move completion does not consume an identical but newer clipboard selection`() {
        val clipboard = BrowserClipboard()
        val a = VfsUri.parse("local://emulated/a.txt")
        clipboard.set(listOf(a))
        val before = clipboard.snapshot()
        clipboard.set(listOf(a))
        clipboard.consumeMoved(before.revision, before.sources, TaskState.Done(1, 0, 0, 10, 20))
        assertEquals(listOf(a), clipboard.sources.value)
    }

    @Test
    fun `stored S3 bucket resolves without a stamp and bookmarks retain their saved identity`() {
        val a = ConnectionConfig(id = 1, type = ConnectionType.S3, name = "a", host = "a", options = mapOf("bucket" to "alpha"))
        val b = ConnectionConfig(id = 2, type = ConnectionType.S3, name = "b", host = "b", options = mapOf("bucket" to "beta"))
        val oldPath = VfsUri.parse("s3://beta/files")
        fun resolve(uri: VfsUri, savedId: Long?) = connectionConfigForUri(listOf(a, b), storedPathLookupUri(uri, savedId))
        assertEquals(b, resolve(oldPath, null))
        assertEquals(a, resolve(oldPath, 1))
        assertEquals(b, resolve(oldPath.copy(query = "c=2"), 1))
        assertNull(resolve(oldPath.copy(query = "c=bad"), 1))
    }

    @Test
    fun `restored legacy endpoint is normalized without rewriting a real S3 bucket`() {
        val config = ConnectionConfig(id = 2, type = ConnectionType.S3, name = "b", host = "nas.example", port = 9000)
        val broken = VfsUri("s3", "\${config.host}:\${config.port}", "/folder", "c=2")
        assertEquals(VfsUri.parse("s3://nas.example:9000/folder?c=2"), storedPathWithConnection(broken, config))
        val bucket = VfsUri.parse("s3://photos/folder")
        assertEquals(VfsUri.parse("s3://photos/folder?c=2"), storedPathWithConnection(bucket, config))
    }

    private fun waiting(token: Long) = TaskState.WaitingConflict(
        ConflictInfo(1, 1, "same", 0, 0, "same", 0, 0, false, token = token),
    )
}
