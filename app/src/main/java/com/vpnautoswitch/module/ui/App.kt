package com.vpnautoswitch.module.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 当前实际是否处于深色：darkMode 为 system 时跟随系统 */
@Composable
fun isDarkTheme(mode: String): Boolean {
    val systemDark = isSystemInDarkTheme()
    return when (mode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
}

@Composable
fun App(state: AppState) {
    val mode = when (state.config.darkMode) {
        "dark" -> ColorSchemeMode.Dark
        "light" -> ColorSchemeMode.Light
        else -> ColorSchemeMode.System
    }
    // 切换深浅色时重建 controller，MiuixTheme 会立即换成对应的 Colors
    val controller = remember(mode) { ThemeController(mode) }
    MiuixTheme(controller = controller) {
        when (state.screen) {
            Screen.Main -> MainScreen(state)
            Screen.Picker -> AppPickerScreen(state)
        }
    }
}
