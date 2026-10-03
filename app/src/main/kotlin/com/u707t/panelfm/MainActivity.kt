package com.u707t.panelfm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.u707t.panelfm.core.data.ThemeMode
import com.u707t.panelfm.core.ui.PanelTheme
import com.u707t.panelfm.ui.AppRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PanelApp).container
        setContent {
            val settings by container.settings.collectAsState()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            PanelTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                AppRoot(container)
            }
        }
    }
}
