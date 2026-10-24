package com.vpnautoswitch.module.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginePolicyTest {

    @Test
    fun `off target has priority over target`() {
        assertEquals(
            EnginePolicy.ForegroundKind.OFF_TARGET,
            EnginePolicy.foregroundKind("app", setOf("app"), setOf("app")),
        )
        assertEquals(
            EnginePolicy.ForegroundKind.TARGET,
            EnginePolicy.foregroundKind("app", setOf("app"), emptySet()),
        )
        assertEquals(
            EnginePolicy.ForegroundKind.IGNORE,
            EnginePolicy.foregroundKind("other", setOf("app"), emptySet()),
        )
    }

    @Test
    fun `fresh open keeps vpn client return semantics`() {
        assertTrue(EnginePolicy.isFreshOpen(null, "app", "vpn"))
        assertFalse(EnginePolicy.isFreshOpen("app", "app", "vpn"))
        assertTrue(EnginePolicy.isFreshOpen("other", "app", "vpn"))
        assertFalse(EnginePolicy.isFreshOpen("vpn", "app", "vpn"))
    }

    @Test
    fun `user off resumes only for fresh open`() {
        assertFalse(EnginePolicy.shouldResumeAfterUserOff(false))
        assertTrue(EnginePolicy.shouldResumeAfterUserOff(true))
    }

    @Test
    fun `network gates preserve unknown and other transports`() {
        assertTrue(EnginePolicy.isNetworkAllowed(1, allowWifi = true, allowMobile = false))
        assertFalse(EnginePolicy.isNetworkAllowed(1, allowWifi = false, allowMobile = true))
        assertTrue(EnginePolicy.isNetworkAllowed(2, allowWifi = false, allowMobile = true))
        assertFalse(EnginePolicy.isNetworkAllowed(2, allowWifi = true, allowMobile = false))
        assertTrue(EnginePolicy.isNetworkAllowed(0, allowWifi = false, allowMobile = false))
        assertTrue(EnginePolicy.isNetworkAllowed(3, allowWifi = false, allowMobile = false))
    }

    @Test
    fun `only last removed target schedules off`() {
        assertFalse(EnginePolicy.shouldScheduleOffAfterDeath(true, 1))
        assertTrue(EnginePolicy.shouldScheduleOffAfterDeath(true, 0))
        assertFalse(EnginePolicy.shouldScheduleOffAfterDeath(false, 0))
    }

    @Test
    fun `unknown process state never means dead`() {
        assertTrue(EnginePolicy.shouldEnsureForProcessState(true))
        assertTrue(EnginePolicy.shouldEnsureForProcessState(null))
        assertFalse(EnginePolicy.shouldEnsureForProcessState(false))
    }

    @Test
    fun `reapply switches to slow interval at configured count`() {
        assertEquals(12_000L, EnginePolicy.reapplyInterval(0))
        assertEquals(12_000L, EnginePolicy.reapplyInterval(3))
        assertEquals(60_000L, EnginePolicy.reapplyInterval(4))
    }

    @Test
    fun `cooldown boundaries allow exact expiry`() {
        listOf(6_000L, 12_000L, 20_000L, 30_000L, 60_000L).forEach { cooldown ->
            assertFalse(EnginePolicy.cooldownReady(cooldown - 1, 0, cooldown))
            assertTrue(EnginePolicy.cooldownReady(cooldown, 0, cooldown))
        }
    }

    @Test
    fun `delayed start accepts live target or wifi on`() {
        assertTrue(delayedStart())
        assertTrue(delayedStart(hasAliveTarget = false, wifiOnActive = true))
        assertFalse(delayedStart(hasAliveTarget = false, wifiOnActive = false))
    }

    @Test
    fun `delayed start rejects every stale or suppressed state`() {
        assertFalse(delayedStart(currentToken = 2))
        assertFalse(delayedStart(enabled = false))
        assertFalse(delayedStart(currentVpnPackage = ""))
        assertFalse(delayedStart(currentVpnPackage = "new.vpn"))
        assertFalse(delayedStart(offAppForeground = true))
        assertFalse(delayedStart(userOff = true))
        assertFalse(delayedStart(wifiSuppressed = true))
        assertFalse(delayedStart(networkAllowed = false))
    }

    @Test
    fun `delayed off is cancelled by token target or wifi on`() {
        assertTrue(EnginePolicy.shouldRunDelayedOff(1, 1, false, false))
        assertFalse(EnginePolicy.shouldRunDelayedOff(1, 2, false, false))
        assertFalse(EnginePolicy.shouldRunDelayedOff(1, 1, true, false))
        assertFalse(EnginePolicy.shouldRunDelayedOff(1, 1, false, true))
    }

    @Test
    fun `close chain requires current generation package and ownership`() {
        assertTrue(EnginePolicy.isCloseChainValid(3, 3, "vpn", "vpn", true))
        assertFalse(EnginePolicy.isCloseChainValid(2, 3, "vpn", "vpn", true))
        assertFalse(EnginePolicy.isCloseChainValid(3, 3, "vpn", "new.vpn", true))
        assertFalse(EnginePolicy.isCloseChainValid(3, 3, "vpn", "vpn", false))
    }

    private fun delayedStart(
        token: Int = 1,
        currentToken: Int = 1,
        scheduledVpnPackage: String = "vpn",
        enabled: Boolean = true,
        currentVpnPackage: String = "vpn",
        offAppForeground: Boolean = false,
        userOff: Boolean = false,
        wifiSuppressed: Boolean = false,
        networkAllowed: Boolean = true,
        hasAliveTarget: Boolean = true,
        wifiOnActive: Boolean = false,
    ): Boolean = EnginePolicy.shouldRunDelayedStart(
        token = token,
        currentToken = currentToken,
        scheduledVpnPackage = scheduledVpnPackage,
        enabled = enabled,
        currentVpnPackage = currentVpnPackage,
        offAppForeground = offAppForeground,
        userOff = userOff,
        wifiSuppressed = wifiSuppressed,
        networkAllowed = networkAllowed,
        hasAliveTarget = hasAliveTarget,
        wifiOnActive = wifiOnActive,
    )
}
