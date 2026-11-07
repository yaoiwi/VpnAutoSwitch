package com.vpnautoswitch.module.ui

import android.os.Build
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
    // M3 用 Monet* 模式；Hyper 用固定的浅/深两套
    val mode = when (state.config.darkMode) {
        "dark" -> if (md3) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
        "light" -> if (md3) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
        else -> if (md3) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
    }
    val dark = isDarkTheme(state.config.darkMode)
    // 跟随壁纸时把 keyColor 留空：Miuix 会自己去读系统调色板（Android 13 读壁纸种子色，
    // 12 读系统的 Monet 色彩角色），读不到再退回内置色板。给了种子色反而会把这条路堵死。
    val wallpaper = useSystemPalette(md3, state.config.md3Wallpaper, Build.VERSION.SDK_INT)
    // 切换深浅色 / 换风格 / 开关取色时重建 controller，MiuixTheme 会立即换成对应的 Colors
    val controller = remember(mode, md3, dark, wallpaper) {
        when {
            // 系统取色：种子色由系统给，spec 与 style 也以系统为准
            wallpaper -> ThemeController(colorSchemeMode = mode, isDark = dark)
            // 固定基线紫：Spec2021 + TonalSpot 是 Material You 最初的取色方式，
            // 也就是 Google 自家应用那套观感。Miuix 默认走 2025 规范，彩度更低、灰阶偏冷。
            md3 -> ThemeController(
                colorSchemeMode = mode,
                keyColor = Md3Seed,
                colorSpec = ThemeColorSpec.Spec2021,
                paletteStyle = ThemePaletteStyle.TonalSpot,
                isDark = dark,
            )
            else -> ThemeController(mode)
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
