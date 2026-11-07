package com.vpnautoswitch.module.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

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
    val style = themeStyleOf(state.config.themeStyle)
    val md3 = style == ThemeStyle.Md3
    // M3 用 Monet* 模式：Miuix 会拿 keyColor 现推一套调色板；Hyper 用固定的浅/深两套
    val mode = when (state.config.darkMode) {
        "dark" -> if (md3) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
        "light" -> if (md3) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
        else -> if (md3) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
    }
    val dark = isDarkTheme(state.config.darkMode)
    // 切换深浅色 / 换风格时重建 controller，MiuixTheme 会立即换成对应的 Colors
    val controller = remember(mode, md3, dark) {
        if (md3) {
            // Spec2021 + TonalSpot 是 Material You 最初的取色方式，也就是 Google 自家应用那套观感。
            // 只换种子色不够：Miuix 默认走 2025 规范，彩度更低、灰阶偏冷，圆角和观感都不像 M3。
            ThemeController(
                colorSchemeMode = mode,
                keyColor = Md3Seed,
                colorSpec = ThemeColorSpec.Spec2021,
                paletteStyle = ThemePaletteStyle.TonalSpot,
                isDark = dark,
            )
        } else {
            ThemeController(mode)
        }
    }
    MiuixTheme(controller = controller) {
        ProvideThemeStyle(style) {
            when (state.screen) {
                Screen.Main -> MainScreen(state)
                Screen.Picker -> AppPickerScreen(state)
            }
        }
    }
}
