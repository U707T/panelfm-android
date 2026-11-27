package com.u707t.panelfm.core.transfer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationDirectoryLocksTest {
    private val locks = DestinationDirectoryLocks()
    @Test
    fun `ancestor and descendant destinations serialize while disjoint paths proceed`() = runBlocking {
        val parentEntered = CompletableDeferred<Unit>()
        val releaseParent = CompletableDeferred<Unit>()
        val childEntered = CompletableDeferred<Unit>()

        val parent = async {
            locks.withLock(DestinationIdentity("remote://one", "/root/tree")) {
                parentEntered.complete(Unit)
                releaseParent.await()
            }
        }
        parentEntered.await()

        val child = async {
            locks.withLock(DestinationIdentity("remote://one", "/root/tree/child")) {
                childEntered.complete(Unit)
            }
        }
        val disjoint = async {
            locks.withLock(DestinationIdentity("remote://one", "/root/other")) { Unit }
        }

        withTimeout(1_000) { disjoint.await() }
        assertFalse("descendant write must wait for its ancestor tree", childEntered.isCompleted)
        releaseParent.complete(Unit)
        withTimeout(1_000) {
            parent.await()
            child.await()
        }
        assertTrue(childEntered.isCompleted)
    }

    @Test
    fun `different VFS namespaces do not conflict`() = runBlocking {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            locks.withLock(DestinationIdentity("remote://a", "/same")) {
                held.complete(Unit)
                release.await()
            }
        }
        held.await()
        val second = async {
            locks.withLock(DestinationIdentity("remote://b", "/same")) { Unit }
        }
        withTimeout(1_000) { second.await() }
        release.complete(Unit)
        first.await()
    }
}
