package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.model.TransferOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空间预检的判定（v2.4.1）。
 *
 * 回归点：同卷 MOVE 是 rename，不占额外空间 —— 之前对它也按总字节预检，
 * 「剩 2 GB 移 3 GB 目录」会弹「空间不足，需要 3 GB」，默认按钮还是取消
 * （`docs/ROADMAP.md` §1.2 的验收条目明确要求「同卷 move 不弹」）。
 */
class SpaceCheckTest {

    @Test
    fun `同卷 MOVE 跳过预检`() {
        assertTrue(skipsSpacePrecheck(TransferOp.MOVE, sameVfsMove = true))
        assertEquals(SpaceCheck.Enqueue, spaceCheckOutcome(TransferOp.MOVE, true, 3_000, 1_000))
    }

    @Test
    fun `跨卷 MOVE 与 COPY 仍然预检`() {
        assertFalse(skipsSpacePrecheck(TransferOp.MOVE, sameVfsMove = false))
        assertFalse(skipsSpacePrecheck(TransferOp.COPY, sameVfsMove = true))
        assertEquals(SpaceCheck.Warn, spaceCheckOutcome(TransferOp.MOVE, false, 3_000, 1_000))
        assertEquals(SpaceCheck.Warn, spaceCheckOutcome(TransferOp.COPY, true, 3_000, 1_000))
    }

    @Test
    fun `未知大小或拿不到可用空间时不拦`() {
        assertEquals(SpaceCheck.Enqueue, spaceCheckOutcome(TransferOp.COPY, false, -1, 1_000))
        assertEquals(SpaceCheck.Enqueue, spaceCheckOutcome(TransferOp.COPY, false, 3_000, -1))
        assertEquals(SpaceCheck.Enqueue, spaceCheckOutcome(TransferOp.COPY, false, 3_000, 0))
        assertEquals(SpaceCheck.Enqueue, spaceCheckOutcome(TransferOp.COPY, false, 1_000, 1_000))
    }
}
