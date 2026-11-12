package com.vpnautoswitch.module.system

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleTraceTest {

    @Test
    fun `nothing excluded means the first condition decided`() {
        assertEquals("", ruleTraceText(emptyList()))
    }

    @Test
    fun `excluded conditions read as one line`() {
        assertEquals(
            "已排除：暂停 · Wi-Fi关 · 打开即关闭",
            ruleTraceText(listOf("暂停", "Wi-Fi关", "打开即关闭")),
        )
    }

    @Test
    fun `the trace never grows a trailing separator`() {
        val text = ruleTraceText(listOf("模块开关"))
        assertEquals("已排除：模块开关", text)
    }
}
