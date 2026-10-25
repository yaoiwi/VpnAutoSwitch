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
}
