package com.vpnautoswitch.module.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    @Test
    fun `blank query means no filtering`() {
        assertNull(SettingsSearch.matchedTitles(""))
        assertNull(SettingsSearch.matchedTitles("   "))
    }

    @Test
    fun `matches section title`() {
        assertEquals(setOf("外观"), SettingsSearch.matchedTitles("外观"))
    }

    @Test
    fun `matches keywords inside a section`() {
        assertTrue(SettingsSearch.matchedTitles("wifi")!!.contains("网络条件"))
        assertTrue(SettingsSearch.matchedTitles("深色")!!.contains("外观"))
        assertTrue(SettingsSearch.matchedTitles("锁定")!!.contains("高级"))
        assertTrue(SettingsSearch.matchedTitles("导出")!!.contains("备份与恢复"))
        assertTrue(SettingsSearch.matchedTitles("日志")!!.contains("诊断"))
    }

    @Test
    fun `diagnostics section answers both old names`() {
        // 自检和运行日志合并成「诊断」后，用户按旧叫法搜也得能找回来
        assertTrue(SettingsSearch.matchedTitles("自检")!!.contains("诊断"))
        assertTrue(SettingsSearch.matchedTitles("状态诊断")!!.contains("诊断"))
        assertTrue(SettingsSearch.matchedTitles("运行日志")!!.contains("诊断"))
        assertTrue(SettingsSearch.matchedTitles("异常")!!.contains("诊断"))
    }

    @Test
    fun `hide launcher icon setting is searchable`() {
        assertTrue(SettingsSearch.matchedTitles("图标")!!.contains("外观"))
        assertTrue(SettingsSearch.matchedTitles("桌面")!!.contains("外观"))
        assertTrue(SettingsSearch.matchedTitles("launcher")!!.contains("外观"))
    }

    @Test
    fun `matching ignores case and spaces`() {
        assertEquals(
            SettingsSearch.matchedTitles("WIFI"),
            SettingsSearch.matchedTitles(" wifi "),
        )
    }

    @Test
    fun `unknown query returns empty set`() {
        val result = SettingsSearch.matchedTitles("zzzz")
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `every section has at least one keyword`() {
        SettingsSearch.sections.forEach { section ->
            assertTrue(section.title, section.keywords.isNotEmpty())
        }
    }
}
