package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserPaneAsyncTest {
    @Test
    fun `listing completed after a filter change publishes only newly visible items`() = runTest {
        val listing = CompletableDeferred<List<FileMetadata>>()
        val cat = file("cat.jpg")
        val dog = file("dog.jpg")
        var pane = PaneState(loading = true, selection = setOf(cat.uri.toString(), dog.uri.toString()))
        val load = launch {
            val loaded = listing.await()
            pane = pane.withLoadedListing(loaded, null)
        }
        runCurrent()
        pane = pane.copy(search = "cat", filterKind = "IMAGE")
        listing.complete(listOf(cat, dog, file("cat.txt")))
        load.join()
        assertEquals(listOf(cat), pane.items)
        assertEquals(setOf(cat.uri.toString()), pane.selection)
        assertEquals(3, pane.unfilteredItems.size)
        assertFalse(pane.loading)
    }

    @Test
    fun `unavailable startup path survives refresh while the other pane may restore and persist`() {
        val savedLeft = "sftp://offline:22/old?c=3"
        val root = PaneState()
        val readyRight = PaneTab(VfsUri.parse("local://emulated/Download"))
        val left = root.restoreStartupTab(null).withLoadedListing(emptyList(), null)
        val right = root.restoreStartupTab(readyRight)
        assertEquals(savedLeft, left.pathToPersist(savedLeft))
        assertEquals(readyRight.uri.toString(), right.pathToPersist(null))
        val navigated = root.copy(hasNavigated = true, tabs = listOf(PaneTab(VfsUri.parse("local://emulated/Other"))))
        assertSame(navigated, navigated.restoreStartupTab(readyRight))
        assertEquals(navigated.uri.toString(), navigated.pathToPersist(savedLeft))
    }

    private fun file(name: String) = FileMetadata(VfsUri.parse("local://emulated/$name"), name, isDirectory = false)
}
