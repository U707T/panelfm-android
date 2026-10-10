package com.u707t.panelfm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.core.view.WindowCompat
import com.u707t.panelfm.core.data.ThemeMode
import com.u707t.panelfm.core.ui.MtViewConfiguration
import com.u707t.panelfm.core.ui.PanelTheme
import com.u707t.panelfm.core.ui.fontScaleFactor
import com.u707t.panelfm.ui.AppRoot
class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as PanelApp).container

    override fun onStop() {
        super.onStop()
        // 退到后台（home / 最近任务）时兜底保存双列路径。
        // 默认退出方式是 moveTaskToBack（Activity 不销毁、composition 不 dispose），
        // 没有这个钩子就只能靠「导航后的防抖保存」——加上它双保险。
        runCatching { container.browser.persistPaths() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 全面屏：内容延伸到状态栏/导航栏，由 Compose 侧统一处理安全区
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as PanelApp).container
        setContent {
            val settings by container.settings.collectAsState()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            // 状态栏 / 导航栏图标颜色跟随**应用内**主题（enableEdgeToEdge 的 auto 只看系统深色，
            // 应用内切换浅色主题时会出现「白底白图标」）
            SideEffect {
                runCatching {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !dark
                    controller.isAppearanceLightNavigationBars = !dark
                }
            }
            PanelTheme(
                darkTheme = dark,
                dynamicColor = settings.dynamicColor,
                fontScale = fontScaleFactor(settings.fontScaleLevel),
            ) {
                // 长按口径统一（v2.0.14）：400ms（MT 口径）+ 全 App 同拍。
                // combinedClickable / detectTapGestures 都从 LocalViewConfiguration 读取长按超时，
                // 一处覆盖即生效（详见 MtViewConfiguration KDoc）。
                val baseViewConfiguration = LocalViewConfiguration.current
                CompositionLocalProvider(
                    LocalViewConfiguration provides remember(baseViewConfiguration) {
                        MtViewConfiguration(baseViewConfiguration)
                    },
                ) {
                    AppRoot(container)
                }
            }
        }
    }
}
