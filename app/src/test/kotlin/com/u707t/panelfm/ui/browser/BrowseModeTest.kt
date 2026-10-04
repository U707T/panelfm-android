package com.u707t.panelfm.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** 浏览模式三档（MT 0x7f1101fb/1fc/1fd/1fe：单列 / 双列 / 自动切换） */
class BrowseModeTest {

    @Test
    fun `自动切换按屏宽展开`() {
        assertEquals(BrowseMode.DUAL, BrowseMode.autoFor(wideEnough = true))
        assertEquals(BrowseMode.SINGLE, BrowseMode.autoFor(wideEnough = false))
    }

    @Test
    fun `强制模式不受屏宽影响`() {
        val single = BrowserUiState(browseMode = BrowseMode.SINGLE, wideEnough = true)
        assertEquals(BrowseMode.SINGLE, single.effectiveBrowseMode)
        assertEquals(true, single.singlePane)

        val dual = BrowserUiState(browseMode = BrowseMode.DUAL, wideEnough = false)
        assertEquals(BrowseMode.DUAL, dual.effectiveBrowseMode)
        assertEquals(false, dual.singlePane)
    }

    @Test
    fun `自动模式跟随屏宽变化`() {
        val narrow = BrowserUiState(browseMode = BrowseMode.AUTO, wideEnough = false)
        assertEquals(true, narrow.singlePane)
        val wide = BrowserUiState(browseMode = BrowseMode.AUTO, wideEnough = true)
        assertEquals(false, wide.singlePane)
    }
}
