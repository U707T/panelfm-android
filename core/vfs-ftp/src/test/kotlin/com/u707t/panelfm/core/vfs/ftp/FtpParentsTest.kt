package com.u707t.panelfm.core.vfs.ftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FtpParentsTest {
    @Test
    fun `parents创建逐级确认并可容忍已经存在的目录`() {
        val directories = mutableSetOf("/already")
        val created = mutableListOf<String>()

        createFtpParentsChecked(
            path = "/already/child/grandchild",
            createDirectory = { path -> created += path; directories.add(path) },
            isDirectory = { it in directories },
            fail = { path, error -> throw AssertionError("unexpected failure at $path", error) },
        )

        assertEquals(listOf("/already", "/already/child", "/already/child/grandchild"), created)
        assertTrue("每一级最终都必须被stat确认", listOf("/already", "/already/child", "/already/child/grandchild").all { it in directories })
    }

    @Test
    fun `mkdir失败且不存在时向调用方传播失败`() {
        var failedPath: String? = null
        var failure: Exception? = null

        try {
            createFtpParentsChecked(
                path = "/denied/child",
                createDirectory = { false },
                isDirectory = { false },
                fail = { path, error -> failedPath = path; failure = error; throw error },
            )
        } catch (_: Exception) {
            // Expected: the first level is neither created nor present.
        }

        assertEquals("/denied", failedPath)
        assertTrue("失败原因应保留", failure != null)
    }
}
