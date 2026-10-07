package com.u707t.panelfm.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** U18 回归：主题快捷按钮三态循环（跟随系统 → 浅色 → 深色 → 跟随系统）。 */
class ThemeModeCycleTest {

    @Test
    fun `循环顺序`() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.SYSTEM.next())
        assertEquals(ThemeMode.DARK, ThemeMode.LIGHT.next())
        assertEquals(ThemeMode.SYSTEM, ThemeMode.DARK.next())
    }

    @Test
    fun `三轮回到原点`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.SYSTEM.next().next().next())
    }
}
