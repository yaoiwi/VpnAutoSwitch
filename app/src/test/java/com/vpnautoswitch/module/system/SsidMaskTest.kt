package com.vpnautoswitch.module.system

import org.junit.Assert.assertEquals
import org.junit.Test

class SsidMaskTest {

    @Test
    fun `long names keep the ends and the length`() {
        // 首尾各留两个字，长度单列：同前缀的两个网也分得清
        assertEquals("Ho***Fi(8)", SsidMask.of("HomeWiFi"))
        assertEquals("AC***st(10)", SsidMask.of("ACME-Guest"))
    }

    @Test
    fun `medium names keep only the first character`() {
        // 五个字以下硬留尾字反而更容易猜，所以只留首字
        assertEquals("H***", SsidMask.of("Home"))
        assertEquals("C***", SsidMask.of("CU_5G"))
    }

    @Test
    fun `short names are hidden completely`() {
        assertEquals("**", SsidMask.of("ab"))
        assertEquals("*", SsidMask.of("x"))
    }

    @Test
    fun `chinese names are treated the same way`() {
        // 按字符算，不按字节：四个汉字走「只剩首字」，七个字符走「首尾各两个」
        assertEquals("张***", SsidMask.of("张三公司"))
        assertEquals("家里***Fi(7)", SsidMask.of("家里的WiFi"))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", SsidMask.of(null))
        assertEquals("", SsidMask.of(""))
    }

    @Test
    fun `the switch turns masking off`() {
        assertEquals("HomeWiFi", SsidMask.of("HomeWiFi", full = true))
        // 打开开关时连空值的行为也不变
        assertEquals("", SsidMask.of("", full = true))
    }

    @Test
    fun `masking is stable for the same name`() {
        // 日志里同一个网任何时候遮出来都一样，才能对比「切没切网」
        val a = SsidMask.of("Office-5G")
        val b = SsidMask.of("Office-5G")
        assertEquals(a, b)
        // 前缀相同、长度不同的两个网不会遮成同一个样子
        assertEquals(false, SsidMask.of("Office-5G") == SsidMask.of("Office-5G-2"))
    }
}
