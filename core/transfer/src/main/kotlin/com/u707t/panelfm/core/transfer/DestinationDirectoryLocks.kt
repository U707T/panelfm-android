package com.u707t.panelfm.core.transfer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** A path namespace plus normalized directory path; local paths share a namespace across URI aliases. */
data class DestinationIdentity(val namespace: String, val path: String)

/**
 * Serializes tasks whose destination directories are equal or ancestor/descendant paths, without
 * holding a global execution mutex. Disjoint trees can execute concurrently.
 *
 * One instance belongs to one [TransferEngine]; a process-global instance would serialize unrelated
 * engines and leak held locks across tests when a scope is cancelled.
 */
internal class DestinationDirectoryLocks {
    private data class Active(val id: Long, val identity: DestinationIdentity)

    private val monitor = Mutex()
    private val ids = AtomicLong()
    private val active = mutableListOf<Active>()
    private val waiters = linkedSetOf<CompletableDeferred<Unit>>()

    suspend fun <T> withLock(identity: DestinationIdentity, action: suspend () -> T): T {
        val token = acquire(identity)
        try {
            return action()
        } finally {
            withContext(NonCancellable) { release(token) }
        }
    }

    private suspend fun acquire(identity: DestinationIdentity): Active {
        while (true) {
            var acquired: Active? = null
            var signal: CompletableDeferred<Unit>? = null
            monitor.withLock {
                if (active.none { conflicts(identity, it.identity) }) {
                    Active(ids.incrementAndGet(), identity).also {
                        active += it
                        acquired = it
                    }
                } else {
                    CompletableDeferred<Unit>().also {
                        waiters += it
                        signal = it
                    }
                }
            }
            acquired?.let { return it }
            val waiting = signal ?: error("missing destination-lock signal")
            try {
                waiting.await()
            } catch (e: Exception) {
                withContext(NonCancellable) { monitor.withLock { waiters.remove(waiting) } }
                throw e
            }
        }
    }

    private suspend fun release(token: Active) {
        val wake = monitor.withLock {
            active.removeAll { it.id == token.id }
            waiters.toList().also { waiters.clear() }
        }
        wake.forEach { it.complete(Unit) }
    }

    private fun conflicts(left: DestinationIdentity, right: DestinationIdentity): Boolean {
        if (left.namespace != right.namespace) return false
        val a = normalize(left.path)
        val b = normalize(right.path)
        return a == b || a == "/" || b == "/" || a.startsWith("$b/") || b.startsWith("$a/")
    }

    private fun normalize(path: String): String {
        val clean = path.replace('\\', '/').trimEnd('/')
        return clean.ifEmpty { "/" }
    }
}
