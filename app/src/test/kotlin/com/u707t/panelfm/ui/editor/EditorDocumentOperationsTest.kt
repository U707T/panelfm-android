package com.u707t.panelfm.ui.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EditorDocumentOperationsTest {
    @Test
    fun `a save cannot clear newer edits or overlap another disk operation`() = runTest {
        val operations = EditorDocumentOperations()
        val disk = CompletableDeferred<Unit>()
        val snapshot = requireNotNull(operations.begin())
        var mayLeave = false
        val save = launch {
            disk.await()
            mayLeave = operations.isCurrent(snapshot)
            operations.end()
        }
        assertNull(operations.begin())
        operations.changed()
        disk.complete(Unit)
        save.join()
        assertFalse(mayLeave)
        val retry = requireNotNull(operations.begin())
        assertTrue(operations.isCurrent(retry))
    }

    @Test
    fun `confirmed reload still cannot replace input arriving during read`() = runTest {
        val operations = EditorDocumentOperations()
        val disk = CompletableDeferred<String>()
        val snapshot = requireNotNull(operations.begin())
        var text = "before reload"
        val reload = launch {
            val read = disk.await()
            if (operations.isCurrent(snapshot)) text = read
            operations.end()
        }
        text = "new input"
        operations.changed()
        disk.complete("disk copy")
        reload.join()
        assertEquals("new input", text)
        assertFalse(operations.inProgress)
    }
}
