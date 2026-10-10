package com.u707t.panelfm.core.ui

import androidx.compose.ui.platform.ViewConfiguration
import org.junit.Assert.assertEquals
import org.junit.Test

/** MT 口径 ViewConfiguration：长按 400ms（覆盖），其余全量委托系统值（v2.0.14）。 */
class MtViewConfigurationTest {

    private val base = object : ViewConfiguration {
        override val longPressTimeoutMillis: Long = 500L
        override val doubleTapTimeoutMillis: Long = 300L
        override val doubleTapMinTimeMillis: Long = 40L
        override val touchSlop: Float = 8f
    }

    @Test
    fun longPressIsMtValueOthersDelegate() {
        val mt = MtViewConfiguration(base)
        assertEquals("长按必须覆盖为 MT 口径（400ms）", MtGesture.LongPressMs, mt.longPressTimeoutMillis)
        assertEquals("双击超时等必须原样委托系统值", 300L, mt.doubleTapTimeoutMillis)
        assertEquals(40L, mt.doubleTapMinTimeMillis)
        assertEquals(8f, mt.touchSlop)
    }
}
