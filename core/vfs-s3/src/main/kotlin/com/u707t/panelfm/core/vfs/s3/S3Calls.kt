package com.u707t.panelfm.core.vfs.s3

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Cancel the live call while waiting for response headers or sending its request body. */
internal suspend fun Call.awaitS3Response(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
/** Closing the active response interrupts a blocked socket read on coroutine cancellation. */
internal suspend fun <T> readS3Cancellable(close: () -> Unit, read: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { close() } }
        kotlinx.coroutines.Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable {
            if (!continuation.isActive) return@Runnable
            try {
                val result = read()
                continuation.resume(result) { _, _, _ -> runCatching { close() } }
            } catch (error: Throwable) {
                if (!continuation.isCancelled) continuation.resumeWithException(error)
            }
        })
    }
