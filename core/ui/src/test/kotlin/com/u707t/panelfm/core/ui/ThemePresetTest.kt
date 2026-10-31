package com.u707t.panelfm.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配色预设（参考 cfbrowser 的纸墨主题）：
 *  - 每套都有完整明暗方案，顶栏始终深色；
 *  - MT 预设保持原来的强调蓝；
 *  - surfaceDim / surfaceBright 方向正确（深色不反）；
 *  - errorContainer 不拿中性 surfaceVariant 顶替。
 */
class ThemePresetTest {

    @Test
    fun `未知预设回落 MT`() {
        assertEquals(ThemePreset.MT, ThemePreset.byId(null))
        assertEquals(ThemePreset.MT, ThemePreset.byId("not-a-theme"))
        assertEquals(ThemePreset.MOJIAN, ThemePreset.byId("mojian"))
    }

    @Test
    fun `预设 id 与名称唯一`() {
        val presets = ThemePreset.entries
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
        assertEquals(presets.size, presets.map { it.label }.toSet().size)
        assertTrue(presets.size >= 5)
    }

    @Test
    fun `MT 保持原来的强调蓝`() {
        assertEquals(Color(0xFF1976D2), ThemePreset.MT.scheme(false).primary)
        assertEquals(Color(0xFF42A5F5), ThemePreset.MT.scheme(true).primary)
    }

    @Test
    fun `每套都有明暗且顶栏始终深色`() {
        ThemePreset.entries.forEach { preset ->
            val light = preset.scheme(false)
            val dark = preset.scheme(true)
            assertTrue("${preset.id} 浅色背景应亮于深色", light.background.luminance() > dark.background.luminance())
            assertTrue("${preset.id} 浅色顶栏应深色", preset.topBar(false).luminance() < 0.1f)
            assertTrue("${preset.id} 深色顶栏应深色", preset.topBar(true).luminance() < 0.1f)
        }
    }

    @Test
    fun `surfaceDim 与 surfaceBright 方向正确`() {
        ThemePreset.entries.forEach { preset ->
            listOf(false, true).forEach { dark ->
                val scheme = preset.scheme(dark)
                assertTrue(
                    "${preset.id} dark=$dark surfaceBright 应亮于 surfaceDim",
                    scheme.surfaceBright.luminance() > scheme.surfaceDim.luminance(),
                )
            }
        }
    }

    @Test
    fun `errorContainer 保持错误语义`() {
        ThemePreset.entries.forEach { preset ->
            listOf(false, true).forEach { dark ->
                val scheme = preset.scheme(dark)
                assertNotEquals("${preset.id} dark=$dark", scheme.surfaceVariant, scheme.errorContainer)
                assertTrue("${preset.id} dark=$dark 错误容器应有对比", scheme.errorContainer != scheme.error)
            }
        }
    }

    @Test
    fun `MT 分割线保持原灰色 其它主题跟随 outlineVariant`() {
        assertEquals(MtSpec.DividerLight, ThemePreset.MT.divider(false))
        assertEquals(MtSpec.DividerDark, ThemePreset.MT.divider(true))
        ThemePreset.entries.filter { it != ThemePreset.MT }.forEach { preset ->
            assertEquals(preset.scheme(false).outlineVariant, preset.divider(false))
            assertEquals(preset.scheme(true).outlineVariant, preset.divider(true))
        }
    }
}
