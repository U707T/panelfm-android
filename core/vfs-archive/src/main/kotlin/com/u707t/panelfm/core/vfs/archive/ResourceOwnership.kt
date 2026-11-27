package com.u707t.panelfm.core.vfs.archive

/** Transfer ownership only after construction / positioning has succeeded. */
internal inline fun <T : AutoCloseable, R> closeOnFailure(resource: T, block: () -> R): R = try {
    block()
} catch (failure: Throwable) {
    try { resource.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
    throw failure
}
