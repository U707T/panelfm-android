package com.u707t.panelfm.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MT 动作菜单的箭头方向（截图复刻）：
 *  - 选中项在**左**窗格 → 目标在右 → `复制 ->` / `移动 ->`
 *  - 选中项在**右**窗格 → 目标在左 → `<- 复制` / `<- 移动`
 */
class CrossPaneLabelTest {

    @Test
    fun leftPaneArrowsPointRight() {
        assertEquals("复制 ->", crossPaneLabel("复制", PaneSide.LEFT))
        assertEquals("移动 ->", crossPaneLabel("移动", PaneSide.LEFT))
    }

    @Test
    fun rightPaneArrowsPointLeft() {
        assertEquals("<- 复制", crossPaneLabel("复制", PaneSide.RIGHT))
        assertEquals("<- 移动", crossPaneLabel("移动", PaneSide.RIGHT))
    }
}
