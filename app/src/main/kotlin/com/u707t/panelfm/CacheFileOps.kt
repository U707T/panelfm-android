package com.u707t.panelfm

import com.u707t.panelfm.core.common.sha256Hex
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * A source version suitable for cache reuse only when the source can be identified reliably.
 * Size alone is insufficient: a same-length replacement must invalidate old materializations.
 */
internal data class CacheSourceVersion(
    val size: Long = -1L,
    val lastModified: Long = -1L,
    val etag: String? = null,
) {
    val isReliable: Boolean
        get() = !etag.isNullOrBlank() || (size >= 0L && lastModified > 0L)

    fun matches(file: File): Boolean =
        isReliable && file.isFile && (size < 0L || file.length() == size)

    /** Stable for known versions; deliberately unique for unknown versions so they cannot be reused. */
    fun cacheKey(identity: String): String {
        val version = if (isReliable) "$size:$lastModified:${etag.orEmpty()}" else UUID.randomUUID().toString()
        return sha256Hex("$identity\u0000$version".toByteArray(), takeBytes = 12)
    }
}

internal fun archiveVersionsCompatible(
    requested: CacheSourceVersion,
    inFlight: CacheSourceVersion,
): Boolean = requested.isReliable && inFlight.isReliable && requested == inFlight

internal fun secretFingerprint(secret: String): String =
    sha256Hex(secret.toByteArray(), takeBytes = 12)

internal fun archivePasswordsCompatible(
    requested: String?,
    inFlightFingerprint: String?,
): Boolean = requested == null || inFlightFingerprint == secretFingerprint(requested)

/**
 * Write beside the final cache file and publish by an atomic rename only. Each writer owns a unique
 * temporary file, so cancellation or a concurrent request cannot expose/truncate another writer's
 * partial output. If the filesystem cannot provide an atomic rename, fail rather than copy over the
 * final path and weaken the cache's commit guarantee.
 */
internal suspend fun writeAtomically(
    target: File,
    expectedLength: Long? = null,
    block: suspend (OutputStream) -> Unit,
) {
    val parent = target.parentFile ?: throw IllegalArgumentException("Cache target needs a parent directory")
    if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
        throw IllegalStateException("Cannot create cache directory: $parent")
    }
    val tmp = File(parent, "${target.name}.part-${UUID.randomUUID()}")
    try {
        tmp.outputStream().use { block(it) }
        if (expectedLength != null && tmp.length() != expectedLength) {
            throw IllegalStateException("Incomplete cache write: expected $expectedLength bytes, got ${tmp.length()}")
        }
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (t: Throwable) {
            // Two complete writers for the same version may race. The winner's atomically
            // published file is a valid result; never copy our temporary bytes over it.
            val winnerIsComplete = target.isFile &&
                (expectedLength?.let { target.length() == it } ?: (target.length() > 0L))
            if (!winnerIsComplete) throw t
        }
    } catch (t: Throwable) {
        throw t
    } finally {
        if (tmp.exists()) runCatching { tmp.delete() }
    }
}
