package com.u707t.panelfm.core.data

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 「自动后台播放」的默认值（v2.1.3）。
 *
 * 需求口径：*在视频播放设置页加一个后台播放功能并**默认关闭***。
 * 「默认关」是产品语义的一部分，不是随手选的缺省值 —— 本应用的播放器是
 * 「打开看一眼」的预览场景，老用户升级上来不该突然出现「退出应用还在响」。
 *
 * 读取端在 `PrefsStore.settings`：`p[Keys.backgroundPlay] ?: false`，
 * 所以「从未写过这个键」与「显式关掉」等价。行为执行在 `MediaScreen` 的
 * `Lifecycle.Event.ON_STOP` 观察者里（关闭 → 暂停；开启 → 不干预）。
 */
class BackgroundPlayDefaultTest {

    @Test
    fun `自动后台播放默认关闭`() {
        assertFalse(AppSettings().backgroundPlay)
    }
}
