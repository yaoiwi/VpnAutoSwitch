package com.vpnautoswitch.module.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogPolicyTest {

    @Test
    fun `short logs are left alone`() {
        val lines = mutableListOf("a", "b", "c")
        LogPolicy.trim(lines, 5)
        assertEquals(listOf("a", "b", "c"), lines)
    }

    @Test
    fun `the header survives while old entries are dropped`() {
        val lines = mutableListOf("device: OnePlus PKR110", "1", "2", "3", "4")
        LogPolicy.trim(lines, 3)
        assertEquals(listOf("device: OnePlus PKR110", "3", "4"), lines)
    }

    @Test
    fun `newest line is always kept when the header is protected`() {
        val lines = mutableListOf("device: x")
        repeat(500) { lines.add("line $it") }
        LogPolicy.trim(lines, 500)
        assertEquals(500, lines.size)
        assertEquals("device: x", lines.first())
        assertEquals("line 499", lines.last())
    }

    @Test
    fun `without a header it behaves as plain fifo`() {
        val lines = mutableListOf("1", "2", "3", "4")
        LogPolicy.trim(lines, 2, keepHead = 0)
        assertEquals(listOf("3", "4"), lines)
    }

    @Test
    fun `keep head larger than the limit does not loop forever`() {
        val lines = mutableListOf("h1", "h2", "3", "4", "5")
        LogPolicy.trim(lines, 3, keepHead = 5)
        assertEquals(3, lines.size)
        assertEquals(listOf("h1", "h2", "3"), lines)
    }

    @Test
    fun `a zero limit clears everything`() {
        val lines = mutableListOf("device: x", "1")
        LogPolicy.trim(lines, 0)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `device lines are recognised with and without a timestamp`() {
        assertTrue(LogPolicy.isDeviceLine("11-10 02:41:07  device: OnePlus PKR110"))
        assertTrue(LogPolicy.isDeviceLine("device: OnePlus PKR110"))
    }

    @Test
    fun `ordinary entries are not mistaken for the header`() {
        assertFalse(LogPolicy.isDeviceLine("11-10 02:41:07  module ready v1.9.9 (131)"))
        assertFalse(LogPolicy.isDeviceLine("11-10 02:41:07  vpn OFF (no target alive)"))
        // 正文里出现 device 字样也不算
        assertFalse(LogPolicy.isDeviceLine("11-10 02:41:07  start device 拉不起来"))
    }

    @Test
    fun `truncating to the latest lines keeps the header`() {
        val lines = mutableListOf("11-10 02:41:07  device: OnePlus PKR110")
        repeat(100) { lines.add("11-10 02:45:00  line $it") }
        val shown = LogPolicy.tailWithHeader(lines, 20)
        assertEquals(21, shown.size)
        assertEquals(lines.first(), shown.first())
        assertEquals("11-10 02:45:00  line 99", shown.last())
    }

    @Test
    fun `header is not duplicated when it already falls inside the tail`() {
        val lines = listOf(
            "11-10 02:41:07  device: OnePlus PKR110",
            "11-10 02:41:08  a",
            "11-10 02:41:09  b",
        )
        assertEquals(lines, LogPolicy.tailWithHeader(lines, 20))
    }

    @Test
    fun `a log without a header behaves as plain tail`() {
        val lines = listOf("1", "2", "3")
        assertEquals(listOf("2", "3"), LogPolicy.tailWithHeader(lines, 2))
        assertTrue(LogPolicy.tailWithHeader(emptyList(), 5).isEmpty())
    }
}
