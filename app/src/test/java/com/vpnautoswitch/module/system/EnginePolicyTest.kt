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
    fun `the enabled toast is skipped right after turning off`() {
        // 刚断开就又被连上：客户端自己重连或系统拉回来的，不该提示「已开启」
        assertTrue(EnginePolicy.isFreshOff(0))
        assertTrue(EnginePolicy.isFreshOff(1_200))
        assertFalse(EnginePolicy.isFreshOff(3_000))
        assertFalse(EnginePolicy.isFreshOff(30_000))
        // 从没关过（lastOffAt 为 0）时相减是开机时长，正常提示
        assertFalse(EnginePolicy.isFreshOff(100_000_000L))
        // 时钟回拨：时间差为负，不当作「刚关掉」
        assertFalse(EnginePolicy.isFreshOff(-50))
    }

    @Test
    fun `logs are cleared only when module version changes`() {
        assertTrue(EnginePolicy.shouldClearLogsOnBoot(0, 66))
        assertTrue(EnginePolicy.shouldClearLogsOnBoot(65, 66))
        assertFalse(EnginePolicy.shouldClearLogsOnBoot(66, 66))
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
    fun `leaving the app in use closes the whole session`() {
        // 划掉 / 结束的正好是刚在用的那个，后台还挂着别的目标 -> 一起收掉，别让它们拽住 VPN
        assertTrue(EnginePolicy.shouldEndSessionOnLeave(removedWasForeground = true, remainingTargets = 2))
        // 被回收的只是后台目标，前台那个还在用 -> 不动
        assertFalse(EnginePolicy.shouldEndSessionOnLeave(removedWasForeground = false, remainingTargets = 1))
        // 没有别的目标，交给「最后一个」逻辑处理
        assertFalse(EnginePolicy.shouldEndSessionOnLeave(removedWasForeground = true, remainingTargets = 0))
    }

    @Test
    fun `dismissing any target closes the session when all are in background`() {
        // 已开启：目标全在后台，划掉的是后台那个 -> 也要断开
        assertTrue(
            EnginePolicy.shouldEndSessionOnLeave(
                removedWasForeground = false,
                remainingTargets = 1,
                dismissAny = true,
                anotherTargetInForeground = false,
            )
        )
        // 已开启，但另一个目标正停在前面用着 -> 保留会话
        assertFalse(
            EnginePolicy.shouldEndSessionOnLeave(
                removedWasForeground = false,
                remainingTargets = 1,
                dismissAny = true,
                anotherTargetInForeground = true,
            )
        )
        // 已关闭：回到只认「正在用的那个」的旧行为
        assertFalse(
            EnginePolicy.shouldEndSessionOnLeave(
                removedWasForeground = false,
                remainingTargets = 1,
                dismissAny = false,
                anotherTargetInForeground = false,
            )
        )
        // 没有别的目标，交给「最后一个」逻辑处理
        assertFalse(
            EnginePolicy.shouldEndSessionOnLeave(
                removedWasForeground = false,
                remainingTargets = 0,
                dismissAny = true,
            )
        )
    }

    @Test
    fun `another target in front is only the one still alive`() {
        assertEquals(
            true,
            EnginePolicy.anotherTargetInForeground("com.b", "com.a", setOf("com.a", "com.b")),
        )
        // 前台的正是要离开的那个
        assertEquals(
            false,
            EnginePolicy.anotherTargetInForeground("com.a", "com.a", setOf("com.a")),
        )
        // 前台是桌面等非目标应用
        assertEquals(
            false,
            EnginePolicy.anotherTargetInForeground("com.miui.home", "com.a", setOf("com.a")),
        )
        // 还不知道前台是谁
        assertEquals(false, EnginePolicy.anotherTargetInForeground(null, "com.a", setOf("com.a")))
    }

    @Test
    fun `task removal requires enabled target alive and not suppressed`() {
        assertTrue(EnginePolicy.shouldHandleTaskRemoval(true, true, false, false, false, true, true))
        assertFalse(EnginePolicy.shouldHandleTaskRemoval(false, true, false, false, false, true, true))
        assertFalse(EnginePolicy.shouldHandleTaskRemoval(true, false, false, false, false, true, true))
        assertFalse(EnginePolicy.shouldHandleTaskRemoval(true, true, true, false, false, true, true))
        assertFalse(EnginePolicy.shouldHandleTaskRemoval(true, true, false, true, false, true, true))
        assertFalse(EnginePolicy.shouldHandleTaskRemoval(true, true, false, false, true, true, true))
        assertFalse(EnginePolicy.shouldHandleTaskRemoval(true, true, false, false, false, false, true))
        assertFalse(EnginePolicy.shouldHandleTaskRemoval(true, true, false, false, false, true, false))
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
    fun `first reapply only waits for the client to build the tunnel`() {
        assertEquals(6_000L, EnginePolicy.reapplyInterval(0, firstMs = 6_000L))
        assertEquals(12_000L, EnginePolicy.reapplyInterval(1, firstMs = 6_000L))
        assertEquals(12_000L, EnginePolicy.reapplyInterval(3, firstMs = 6_000L))
        assertEquals(60_000L, EnginePolicy.reapplyInterval(4, firstMs = 6_000L))
    }

    @Test
    fun `reconcile polls fast while connecting and then falls back`() {
        assertEquals(900L, EnginePolicy.reconcileDelay(connected = false, connectingTicks = 0))
        assertEquals(900L, EnginePolicy.reconcileDelay(connected = false, connectingTicks = 7))
        assertEquals(5_000L, EnginePolicy.reconcileDelay(connected = false, connectingTicks = 8))
        assertEquals(5_000L, EnginePolicy.reconcileDelay(connected = true, connectingTicks = 0))
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

    @Test
    fun `guard only runs when settings were not taken over`() {
        assertTrue(EnginePolicy.shouldGuard(true, false, 1, 3, true))
        assertFalse(EnginePolicy.shouldGuard(false, false, 1, 3, true))
        assertFalse(EnginePolicy.shouldGuard(true, true, 1, 3, true))
        assertFalse(EnginePolicy.shouldGuard(true, false, 4, 3, true))
        assertFalse(EnginePolicy.shouldGuard(true, false, 1, 3, false))
    }

    @Test
    fun `guard interval grows and caps`() {
        assertEquals(960L, EnginePolicy.nextGuardInterval(600L))
        assertEquals(1_536L, EnginePolicy.nextGuardInterval(960L))
        assertEquals(2_400L, EnginePolicy.nextGuardInterval(1_536L))
        assertEquals(2_400L, EnginePolicy.nextGuardInterval(2_400L))
    }

    @Test
    fun `stop order follows capability flags`() {
        assertEquals(
            listOf(
                EnginePolicy.StopStep.BROADCAST,
                EnginePolicy.StopStep.ACTIVITY,
                EnginePolicy.StopStep.TILE,
                EnginePolicy.StopStep.FORCE_STOP,
            ),
            EnginePolicy.stopOrder(true, true, true, true),
        )
        assertEquals(
            listOf(
                EnginePolicy.StopStep.BROADCAST,
                EnginePolicy.StopStep.TILE,
                EnginePolicy.StopStep.FORCE_STOP,
            ),
            EnginePolicy.stopOrder(true, false, true, true),
        )
        assertEquals(
            listOf(EnginePolicy.StopStep.BROADCAST, EnginePolicy.StopStep.FORCE_STOP),
            EnginePolicy.stopOrder(true, false, false, true),
        )
        assertEquals(
            listOf(EnginePolicy.StopStep.BROADCAST, EnginePolicy.StopStep.TILE),
            EnginePolicy.stopOrder(true, false, true, false),
        )
    }

    @Test
    fun `keeping the client alive delays the force stop`() {
        // 关掉「保留进程」时照旧允许强停；打开时先只走温和手段
        assertTrue(EnginePolicy.allowForceStopOnClose(forceStopEnabled = true, keepClientAlive = false))
        assertFalse(EnginePolicy.allowForceStopOnClose(forceStopEnabled = true, keepClientAlive = true))
        // 用户本来就不允许强停，两种情况下都不强停
        assertFalse(EnginePolicy.allowForceStopOnClose(forceStopEnabled = false, keepClientAlive = false))
        assertFalse(EnginePolicy.allowForceStopOnClose(forceStopEnabled = false, keepClientAlive = true))
    }

    @Test
    fun `stop order without package only allows force stop`() {
        assertEquals(
            listOf(EnginePolicy.StopStep.FORCE_STOP),
            EnginePolicy.stopOrder(false, true, true, true),
        )
        assertEquals(emptyList<EnginePolicy.StopStep>(), EnginePolicy.stopOrder(false, true, true, false))
    }

    @Test
    fun `service clients get a shorter broadcast wait`() {
        assertEquals(700L, EnginePolicy.broadcastWaitMs(true))
        assertEquals(1_200L, EnginePolicy.broadcastWaitMs(false))
    }
}
