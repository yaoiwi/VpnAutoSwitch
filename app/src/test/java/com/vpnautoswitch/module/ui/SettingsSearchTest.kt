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
        assertTrue(SettingsSearch.matchedTitles("日志")!!.contains("运行日志"))
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
