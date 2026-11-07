package com.vpnautoswitch.module.ui

import com.vpnautoswitch.module.Config
import org.junit.Assert.assertEquals
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
}
