package com.vpnautoswitch.module.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsPolicyTest {

    private val now = 1_000_000L

    @Test
    fun `heartbeat freshness handles zero future and exact boundary`() {
        assertFalse(DiagnosticsPolicy.isHeartbeatFresh(0L, now))
        assertFalse(DiagnosticsPolicy.isHeartbeatFresh(now + 1, now))
        assertTrue(DiagnosticsPolicy.isHeartbeatFresh(now, now))
        assertTrue(DiagnosticsPolicy.isHeartbeatFresh(now - 60_000L, now))
        assertFalse(DiagnosticsPolicy.isHeartbeatFresh(now - 60_001L, now))
    }

    @Test
    fun `heartbeat age never goes negative`() {
        assertEquals(0L, DiagnosticsPolicy.heartbeatAge(0L, now))
        assertEquals(0L, DiagnosticsPolicy.heartbeatAge(now + 500, now))
        assertEquals(5_000L, DiagnosticsPolicy.heartbeatAge(now - 5_000, now))
    }

    @Test
    fun `module state distinguishes never stale old and current`() {
        assertEquals(
            DiagnosticsPolicy.ModuleState.NEVER,
            DiagnosticsPolicy.moduleState(0L, 0, 66, now),
        )
        assertEquals(
            DiagnosticsPolicy.ModuleState.STALE,
            DiagnosticsPolicy.moduleState(now - 120_000L, 66, 66, now),
        )
        assertEquals(
            DiagnosticsPolicy.ModuleState.OLD_CODE,
            DiagnosticsPolicy.moduleState(now - 1_000L, 65, 66, now),
        )
        assertEquals(
            DiagnosticsPolicy.ModuleState.CURRENT,
            DiagnosticsPolicy.moduleState(now - 1_000L, 66, 66, now),
        )
    }

    @Test
    fun `expected vpn follows active rule`() {
        assertEquals(
            DiagnosticsPolicy.ExpectedVpn.ON,
            DiagnosticsPolicy.expectedVpn("target"),
        )
        assertEquals(
            DiagnosticsPolicy.ExpectedVpn.ON,
            DiagnosticsPolicy.expectedVpn("wifiOn"),
        )
        assertEquals(
            DiagnosticsPolicy.ExpectedVpn.OFF,
            DiagnosticsPolicy.expectedVpn("offTarget"),
        )
        assertEquals(
            DiagnosticsPolicy.ExpectedVpn.OFF,
            DiagnosticsPolicy.expectedVpn("wifiOff"),
        )
        assertEquals(
            DiagnosticsPolicy.ExpectedVpn.OFF,
            DiagnosticsPolicy.expectedVpn("userOff"),
        )
        assertEquals(
            DiagnosticsPolicy.ExpectedVpn.UNKNOWN,
            DiagnosticsPolicy.expectedVpn("paused"),
        )
        assertEquals(
            DiagnosticsPolicy.ExpectedVpn.UNKNOWN,
            DiagnosticsPolicy.expectedVpn(""),
        )
    }

    @Test
    fun `vpn mismatch only reports when rule forces a state`() {
        assertTrue(DiagnosticsPolicy.vpnMismatch("target", false) == true)
        assertTrue(DiagnosticsPolicy.vpnMismatch("target", true) == false)
        assertTrue(DiagnosticsPolicy.vpnMismatch("offTarget", true) == true)
        assertTrue(DiagnosticsPolicy.vpnMismatch("offTarget", false) == false)
        assertEquals(null, DiagnosticsPolicy.vpnMismatch("paused", true))
        assertEquals(null, DiagnosticsPolicy.vpnMismatch("idle", false))
    }

    @Test
    fun `rule labels cover every reported rule`() {
        assertEquals("目标应用 → 应连接", DiagnosticsPolicy.ruleLabel("target"))
        assertEquals("打开即关闭 → 应断开", DiagnosticsPolicy.ruleLabel("offTarget"))
        assertEquals("已暂停 → 不干预", DiagnosticsPolicy.ruleLabel("paused"))
        assertEquals("尚未收到规则", DiagnosticsPolicy.ruleLabel(""))
        assertEquals("custom", DiagnosticsPolicy.ruleLabel("custom"))
    }

    @Test
    fun `last event picks the newest non blank line`() {
        val log = "12:01:00  a\n12:02:00  b\n\n"
        assertEquals("12:02:00  b", DiagnosticsPolicy.lastEvent(log))
        assertEquals("", DiagnosticsPolicy.lastEvent(""))
    }

    @Test
    fun `summary explains a connected target app`() {
        val s = DiagnosticsPolicy.summarize(
            heartbeat = now,
            moduleVersion = 72,
            appVersion = 72,
            paused = false,
            activeRule = "target",
            ruleDetail = "目标应用在前台：YouTube",
            foreground = "com.google.android.youtube",
            vpnConnected = true,
            network = "Wi-Fi",
            log = "12:04:00  com.google.android.youtube -> connect",
            now = now,
        )
        assertEquals("运行中", s.headline)
        assertEquals("com.google.android.youtube", s.foreground)
        assertTrue(s.rule.startsWith("目标应用 → 应连接"))
        assertEquals("已连接", s.actual)
        assertEquals(DiagnosticsPolicy.Tone.NORMAL, s.actualTone)
        assertTrue(s.lastAction.contains("connect"))
    }

    @Test
    fun `summary warns when rule wants vpn but nothing is connected`() {
        val s = DiagnosticsPolicy.summarize(
            heartbeat = now,
            moduleVersion = 72,
            appVersion = 72,
            paused = false,
            activeRule = "target",
            ruleDetail = "",
            foreground = "com.example.bank",
            vpnConnected = false,
            network = "Wi-Fi",
            log = "",
            now = now,
        )
        assertEquals("未连接", s.actual)
        assertEquals(DiagnosticsPolicy.Tone.WARN, s.actualTone)
        assertEquals("暂无记录", s.lastAction)
    }

    @Test
    fun `summary marks unknown when module never reported`() {
        val s = DiagnosticsPolicy.summarize(
            heartbeat = 0L,
            moduleVersion = 0,
            appVersion = 72,
            paused = false,
            activeRule = "target",
            ruleDetail = "",
            foreground = "com.example",
            vpnConnected = true,
            network = "Wi-Fi",
            log = "",
            now = now,
        )
        assertEquals("等待注入", s.headline)
        assertEquals("未知", s.foreground)
        assertEquals("未知", s.rule)
        assertEquals("未知", s.actual)
        assertEquals(DiagnosticsPolicy.Tone.MUTED, s.actualTone)
    }
}
