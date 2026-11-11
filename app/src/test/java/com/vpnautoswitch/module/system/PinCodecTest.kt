package com.vpnautoswitch.module.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinCodecTest {

    @Test
    fun `old two field entries still parse`() {
        val entries = PinCodec.parse("com.github.kr328.clash|activity")
        assertEquals(1, entries.size)
        assertEquals("com.github.kr328.clash", entries[0].pkg)
        assertEquals("activity", entries[0].mode)
        // 老格式没有版本和时间：不当作过期，否则升级后所有固定值都会被打回重新学习
        assertEquals(0, entries[0].version)
        assertEquals(0L, entries[0].at)
    }

    @Test
    fun `new entries keep the client version`() {
        val entries = PinCodec.parse("com.v2ray.ang|service|140|1730000000000")
        assertEquals(1, entries.size)
        assertEquals("service", entries[0].mode)
        assertEquals(140, entries[0].version)
        assertEquals(1730000000000L, entries[0].at)
    }

    @Test
    fun `unknown modes are dropped instead of trusted`() {
        // 旧版本可能写过别的值；留着会让引擎进到一个没有分支的 mode
        assertTrue(PinCodec.parse("com.a|ui").isEmpty())
        assertTrue(PinCodec.parse("com.a|").isEmpty())
    }

    @Test
    fun `broken input yields nothing`() {
        assertTrue(PinCodec.parse(null).isEmpty())
        assertTrue(PinCodec.parse("").isEmpty())
        assertTrue(PinCodec.parse("com.a").isEmpty())
        assertTrue(PinCodec.parse("|activity").isEmpty())
        assertTrue(PinCodec.parse("com.a|activity|notanumber").let { it.size == 1 })
    }

    @Test
    fun `a client upgrade makes the pin stale`() {
        val entry = PinCodec.Entry("com.a", "service", version = 133, at = 1L)
        assertTrue(PinCodec.isStale(entry, clientVersion = 140))
        assertFalse(PinCodec.isStale(entry, clientVersion = 133))
    }

    @Test
    fun `entries without a version are never stale`() {
        val old = PinCodec.Entry("com.a", "activity", version = 0, at = 0L)
        assertFalse(PinCodec.isStale(old, clientVersion = 140))
        // 查不到客户端版本时也不判过期，宁可继续用旧记录
        val known = PinCodec.Entry("com.a", "activity", version = 133, at = 1L)
        assertFalse(PinCodec.isStale(known, clientVersion = 0))
    }

    @Test
    fun `format round trips and upgrades old entries`() {
        val raw = "com.a|activity,com.b|service|133|99"
        val entries = PinCodec.parse(raw)
        assertEquals(2, entries.size)
        // 老条目重新写回时补上 0 版本，格式统一
        assertEquals("com.a|activity|0|0,com.b|service|133|99", PinCodec.format(entries))
    }

    @Test
    fun `upsert replaces the same package and keeps others`() {
        val entries = PinCodec.parse("com.a|activity|1|1,com.b|service|2|2")
        val next = PinCodec.upsert(entries, PinCodec.Entry("com.a", "service", 5, 6))
        assertEquals(2, next.size)
        assertEquals("service", next.first { it.pkg == "com.a" }.mode)
        assertEquals("service", next.first { it.pkg == "com.b" }.mode)
    }

    @Test
    fun `entryFor only matches the exact package`() {
        val raw = "com.a|activity|1|1"
        assertEquals("activity", PinCodec.entryFor(raw, "com.a")?.mode)
        assertNull(PinCodec.entryFor(raw, "com.ab"))
        assertNull(PinCodec.entryFor(raw, ""))
    }
}
