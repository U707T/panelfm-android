package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.vfs.VfsUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F7/F8 回归：「显式目标必须端到端」。
 *
 *  - [explicitTargets]：override 非 null（含空列表）一律以 override 为准，只有 null 才回退；
 *  - [planCompressTargets]：长按「这一项压缩」时，选择集 / 整个目录**不得**顶掉显式目标
 *    （v1.9.1 只加了参数、函数体没用 → 静默压错内容；本测试锁死该语义）。
 */
class ExplicitTargetsTest {

    private val a = VfsUri.of("local", "emulated", "/A.txt")
    private val b = VfsUri.of("local", "emulated", "/B.txt")
    private val c = VfsUri.of("local", "emulated", "/C.txt")

    @Test
    fun `显式目标优先，只有 null 才回退`() {
        assertEquals(listOf(a), explicitTargets(listOf(a)) { listOf(b, c) })
        assertEquals(listOf(b, c), explicitTargets(null) { listOf(b, c) })
    }

    @Test
    fun `显式目标为空列表时不得回退成整个目录`() {
        assertEquals(emptyList<VfsUri>(), explicitTargets(emptyList()) { listOf(b, c) })
    }

    @Test
    fun `压缩：长按这一项时取源与命名都用显式目标（F7）`() {
        val plan = planCompressTargets(listOf(a), listOf(b, c), fileName = null, formatExt = "zip")!!
        assertEquals("取源必须是显式目标，而不是回退的选择集", listOf(a), plan.sources)
        // 旧实现会对 b/c 命名出 archive.zip（压错的内容 + 错的名字）
        assertEquals("A.zip", plan.name)
    }

    @Test
    fun `压缩：没有显式目标时回退选择集，多项命名用 archive`() {
        val plan = planCompressTargets(null, listOf(b, c), null, "zip")!!
        assertEquals(listOf(b, c), plan.sources)
        assertEquals("archive.zip", plan.name)
    }

    @Test
    fun `压缩：用户填的名字优先，并补齐格式后缀`() {
        assertEquals("mine.7z", planCompressTargets(null, listOf(a), "mine", "7z")!!.name)
        assertEquals("mine.7z", planCompressTargets(null, listOf(a), "mine.7z", "7z")!!.name)
        assertEquals("mine.tar.gz", planCompressTargets(null, listOf(a), "mine", "tar.gz")!!.name)
    }

    @Test
    fun `压缩：没有任何源时返回 null（给提示，而不是静默整目录）`() {
        assertNull(planCompressTargets(emptyList(), listOf(b), null, "zip"))
        assertNull(planCompressTargets(null, emptyList(), null, "zip"))
    }
}
