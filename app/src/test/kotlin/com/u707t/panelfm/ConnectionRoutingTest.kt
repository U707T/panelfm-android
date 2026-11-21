package com.u707t.panelfm

import com.u707t.panelfm.core.model.ConnectionConfig
import com.u707t.panelfm.core.model.ConnectionType
import com.u707t.panelfm.core.vfs.VfsUri
import org.junit.Assert.*
import org.junit.Test

class ConnectionRoutingTest {
    private val first = ConnectionConfig(1, ConnectionType.SFTP, "A", "nas", user = "alice")
    private val second = first.copy(id = 2, name = "B", user = "bob")
    @Test fun `explicit missing or invalid identity cannot borrow another connection`() {
        val configs = listOf(first, second)
        assertNull(connectionConfigForUri(configs, VfsUri.parse("sftp://nas:22/a?c=99")))
        assertNull(connectionConfigForUri(configs, VfsUri.parse("sftp://nas:22/a?c=bad")))
        assertNull(connectionConfigForUri(configs, VfsUri.parse("sftp://nas:22/a?c=1&c=2")))
        assertNull(connectionConfigForUri(configs, VfsUri.parse("dav://nas:22/a?c=1")))
        assertEquals(second, connectionConfigForUri(configs, VfsUri.parse("sftp://nas:22/a?c=2")))
    }
    @Test fun `authority without identity must be unambiguous and host must match`() {
        assertNull(connectionConfigForUri(listOf(first, second), VfsUri.parse("sftp://nas:22/a")))
        assertNull(connectionConfigForUri(listOf(first), VfsUri.parse("sftp://other:22/a")))
        assertEquals(first, connectionConfigForUri(listOf(first), VfsUri.parse("sftp://nas:22/a")))
    }
    @Test fun `legacy S3 bucket can resolve but duplicate endpoints cannot be guessed`() {
        val s3 = ConnectionConfig(3, ConnectionType.S3, "S3", "minio", options = mapOf("bucket" to "photos"))
        assertEquals(s3, connectionConfigForUri(listOf(first, s3), VfsUri.parse("s3://photos/dir")))
        assertNull(connectionConfigForUri(listOf(s3, s3.copy(id = 4)), VfsUri.parse("s3://photos/dir")))
        assertEquals("minio:443", connectionUri(s3).authority)
        assertEquals("c=3", connectionUri(s3).query)
    }
    @Test fun `saved connections hold distinct leases even with identical configuration`() {
        assertNotEquals(heldConnectionKey(first), heldConnectionKey(first.copy(id = 2)))
    }
}
