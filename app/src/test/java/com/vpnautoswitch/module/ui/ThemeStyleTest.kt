package com.vpnautoswitch.module.ui

import com.vpnautoswitch.module.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeStyleTest {

    @Test
    fun `md3 key maps to the md3 style`() {
        assertEquals(ThemeStyle.Md3, themeStyleOf(Config.THEME_MD3))
    }

    @Test
    fun `unknown or empty key falls back to hyper`() {
        // 老配置里没有这个字段，读出来是空串；写坏的配置也不能把界面弄成白屏
        assertEquals(ThemeStyle.Hyper, themeStyleOf(""))
        assertEquals(ThemeStyle.Hyper, themeStyleOf(Config.THEME_HYPER))
        assertEquals(ThemeStyle.Hyper, themeStyleOf("material"))
        assertEquals(ThemeStyle.Hyper, themeStyleOf("MD3"))
    }

    @Test
    fun `new configs default to hyper`() {
        // 默认值必须是老的 HyperOS 风格：升级上来第一眼不能变样
        assertEquals(Config.THEME_HYPER, Config().themeStyle)
    }

    @Test
    fun `wallpaper colours are on by default and only for md3 on android 12 plus`() {
        assertTrue(Config().md3Wallpaper)
        // 三件都满足才走系统取色
        assertTrue(useSystemPalette(md3 = true, wallpaper = true, sdkInt = 31))
        assertTrue(useSystemPalette(md3 = true, wallpaper = true, sdkInt = 34))
        // 少了任何一件都回到基线紫
        assertFalse(useSystemPalette(md3 = false, wallpaper = true, sdkInt = 34))
        assertFalse(useSystemPalette(md3 = true, wallpaper = false, sdkInt = 34))
        assertFalse(useSystemPalette(md3 = true, wallpaper = true, sdkInt = 30))
    }

    @Test
    fun `the wallpaper switch defaults on and can be turned off`() {
        assertEquals(true, Config().md3Wallpaper)
        assertEquals(false, Config(md3Wallpaper = false).md3Wallpaper)
    }

    @Test
    fun `each style keeps its own floating bar choice`() {
        // 在 M3 里开了悬浮，不该把 HyperOS 那边的「贴底」改掉
        val mixed = Config(floatingBottomBar = false, md3FloatingBottomBar = true)
        assertTrue(floatingBarOf(mixed, md3 = true))
        assertFalse(floatingBarOf(mixed, md3 = false))
    }

    @Test
    fun `md3 docks the bar by default while hyper floats`() {
        val defaults = Config()
        assertTrue(floatingBarOf(defaults, md3 = false))
        assertFalse(floatingBarOf(defaults, md3 = true))
    }
}
