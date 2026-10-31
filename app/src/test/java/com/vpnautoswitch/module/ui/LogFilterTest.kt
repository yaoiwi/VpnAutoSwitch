package com.vpnautoswitch.module.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFilterTest {

    @Test
    fun `picks the lines worth looking at`() {
        assertTrue(LogFilter.isAbnormal("10:04:57  verify: 温和手段没关掉，改为强制停止（进程会在关闭后重新预热）"))
        assertTrue(LogFilter.isAbnormal("10:05:01  guard: 客户端已 3 次重新拉起 VPN，停止抑制"))
        assertTrue(LogFilter.isAbnormal("08:22:29  vpn dropped -> stay off until a target app is reopened"))
        assertTrue(LogFilter.isAbnormal("09:23:38  prewarm skipped: client was force stopped just now"))
        assertTrue(LogFilter.isAbnormal("10:04:59  force stopped, stopped-state kept"))
        assertTrue(LogFilter.isAbnormal("08:34:01  external-only: 无法在后台拉起 com.v2ray.ang.fdroid"))
        // 补发之后才连上：说明前面失败过，留着有用
        assertTrue(LogFilter.isAbnormal("11:02:28  vpn connected (after 1 failed attempts)"))
    }

    @Test
    fun `keeps quiet about the normal flow`() {
        assertFalse(LogFilter.isAbnormal("09:23:38  com.twitter.android -> foreground"))
        assertFalse(LogFilter.isAbnormal("09:23:38  started VPN service com.v2ray.ang.service.CoreVpnService"))
        assertFalse(
            LogFilter.isAbnormal(
                "09:23:38  external mode 'service' works for com.v2ray.ang.fdroid, remembered"
            )
        )
    }

    @Test
    fun `select respects the toggle`() {
        val lines = listOf(
            "09:23:38  com.twitter.android -> foreground",
            "09:23:38  started VPN service com.v2ray.ang.service.CoreVpnService",
            "10:05:01  guard: 客户端已 3 次重新拉起 VPN，停止抑制",
        )
        assertEquals(lines, LogFilter.select(lines, onlyAbnormal = false))
        assertEquals(listOf(lines[2]), LogFilter.select(lines, onlyAbnormal = true))
    }
}
