package com.u707t.panelfm.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** 关闭标签页后的激活下标（BrowserController.closeTab 的纯函数核心，回归旧公式「关末位跳两个」的错）。 */
class CloseTabTest {

    @Test
    fun `关闭当前标签：右邻居顶上，末位则回退前一个`() {
        // [A,B,C] active=C(2) 关 C → 剩 [A,B]，激活 B(1)
        assertEquals(1, closeTabNewActive(activeTab = 2, closeIndex = 2, tabCountAfter = 2))
        // [A,B,C] active=B(1) 关 B → 剩 [A,C]，激活 C(1)
        assertEquals(1, closeTabNewActive(activeTab = 1, closeIndex = 1, tabCountAfter = 2))
    }

    @Test
    fun `关闭左侧标签：当前下标左移`() {
        // [A,B,C] active=C(2) 关 A → 剩 [B,C]，仍激活 C(1)
        assertEquals(1, closeTabNewActive(activeTab = 2, closeIndex = 0, tabCountAfter = 2))
        // [A,B,C,D] active=D(3) 关 B(1) → 剩 [A,C,D]，仍激活 D(2)
        assertEquals(2, closeTabNewActive(activeTab = 3, closeIndex = 1, tabCountAfter = 3))
    }

    @Test
    fun `关闭右侧标签：下标不变`() {
        assertEquals(0, closeTabNewActive(activeTab = 0, closeIndex = 2, tabCountAfter = 2))
    }

    @Test
    fun `两标签与夹紧边界`() {
        assertEquals(0, closeTabNewActive(activeTab = 1, closeIndex = 1, tabCountAfter = 1))
        assertEquals(0, closeTabNewActive(activeTab = 0, closeIndex = 0, tabCountAfter = 1))
    }
}
