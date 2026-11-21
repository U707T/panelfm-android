package com.u707t.panelfm.ui.preview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPlaybackIntentTest {
    @Test
    fun `only newest preflight applies player side effects when completed out of order`() = runTest {
        val intents = MediaPlaybackIntent()
        val readyA = CompletableDeferred<String?>()
        val readyB = CompletableDeferred<String?>()
        val applied = mutableListOf<String>()
        val a = intents.next()
        val first = launch {
            assertNull(intents.prepare(a, { readyA.await() }) { applied += "A"; null })
        }
        runCurrent()
        val b = intents.next()
        val second = launch {
            assertNull(intents.prepare(b, { readyB.await() }) { applied += "B"; null })
        }
        runCurrent()
        readyB.complete(null)
        second.join()
        readyA.complete(null)
        first.join()
        assertEquals(listOf("B"), applied)
    }
}
