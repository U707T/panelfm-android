package com.u707t.panelfm.core.vfs.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbParentsTest {
    @Test
    fun `parents创建逐级确认并允许已有目录`() {
        val directories = mutableSetOf("already")
        val created = mutableListOf<String>()

        createSmbParentsChecked(
            path = "already\\child\\grandchild",
            createDirectory = { path -> created += path; directories.add(path); Unit },
            isDirectory = { it in directories },
            fail = { path, error -> throw AssertionError("unexpected failure at $path", error) },
        )

        assertEquals(listOf("already", "already\\child", "already\\child\\grandchild"), created)
        assertTrue(listOf("already", "already\\child", "already\\child\\grandchild").all { it in directories })
    }

    @Test
    fun `mkdir失败且最终stat未确认目录时传播失败`() {
        var failedPath: String? = null
        var failure: Exception? = null

        try {
            createSmbParentsChecked(
                path = "denied\\child",
                createDirectory = { throw IllegalStateException("access denied") },
                isDirectory = { false },
                fail = { path, error -> failedPath = path; failure = error; throw error },
            )
        } catch (_: Exception) {
            // Expected: the first level is neither created nor present.
        }

        assertEquals("denied", failedPath)
        assertTrue("失败原因应保留", failure != null)
    }

    @Test
    fun `只有已确认的文件夹能容忍mkdir冲突`() {
        val directories = mutableSetOf("exists")
        var attempts = 0

        createSmbParentsChecked(
            path = "exists",
            createDirectory = { attempts++; throw IllegalStateException("already exists") },
            isDirectory = { it in directories },
            fail = { path, error -> throw AssertionError("existing directory $path should be accepted", error) },
        )

        assertEquals(1, attempts)
    }
}
