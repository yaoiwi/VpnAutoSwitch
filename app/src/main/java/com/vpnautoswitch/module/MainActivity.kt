package com.vpnautoswitch.module

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.vpnautoswitch.module.ui.App
import com.vpnautoswitch.module.ui.AppState
import com.vpnautoswitch.module.ui.Screen
import com.vpnautoswitch.module.ui.isDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {

    private var appState: AppState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state = remember {
                AppState(this@MainActivity).also { appState = it }
            }

            // 停留在本界面时每 5 秒刷新一次运行状态（状态来自 Settings.Secure，随时读都有效）
            LaunchedEffect(Unit) {
                while (isActive) {
                    if (state.screen is Screen.Main) {
                        state.refreshDiagnostics()
                    }
                    delay(5_000)
                }
            }

            // 应用内切换深浅色时，状态栏 / 导航栏图标也要跟着反色
            val dark = isDarkTheme(state.config.darkMode)
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                )
            }

            App(state)
        }
    }

    override fun onDestroy() {
        appState?.dispose()
        appState = null
        super.onDestroy()
    }
}
