package com.vpnautoswitch.module.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #16 那条「目标都退出了 VPN 却没关」的链路，按真实事件顺序把纯策略串起来验证。
 *
 * 这里测的是判定语义，不是 Engine 里带 Handler / token 的编排（那部分需要 instrumentation
 * 环境）。目的是锁住「哪些条件叠加时该不该收会话」，避免以后改策略时把 #16 又改回来。
 */
class EngineSessionFlowTest {

    private val github = "com.github.android"
    private val telegram = "org.telegram.messenger"

    /** handleTaskRemoved 的前置门禁：七个条件全过才继续 */
    private fun gates(
        enabled: Boolean = true,
        featureEnabled: Boolean = true,
        paused: Boolean = false,
        offAppForeground: Boolean = false,
        wifiSuppressed: Boolean = false,
        target: Boolean = true,
        aliveTarget: Boolean = true,
    ) = EnginePolicy.shouldHandleTaskRemoval(
        enabled, featureEnabled, paused, offAppForeground, wifiSuppressed, target, aliveTarget,
    )

    @Test
    fun `a real swipe to dismiss passes every gate and is not stale`() {
        assertTrue(gates())
        // 桌面的 resume 已经到了，被移除的包不再是前台 -> 是真划掉
        assertFalse(EnginePolicy.isStaleTaskRemoval(github, "com.android.launcher"))
        // 这一轮没拉起过客户端界面
        assertFalse(EnginePolicy.isUiInducedTaskRemoval(now = 10_000, uiShownAt = 0L, windowMs = 6_000))
    }

    @Test
    fun `stale task removal is deferred for a recheck instead of being dropped`() {
        // 浏览器开新窗口清理旧 task：包名对，应用还在前台，先挂 2 秒复查
        assertTrue(gates())
        assertTrue(EnginePolicy.isStaleTaskRemoval(github, github))
        // 复查时桌面已经接管 -> 这一笔按真划掉处理
        assertFalse(EnginePolicy.isStaleTaskRemoval(github, "com.android.launcher"))
    }

    @Test
    fun `opening the client UI swallows the removal even when all gates pass`() {
        assertTrue(gates())
        assertFalse(EnginePolicy.isStaleTaskRemoval(github, "com.flclash"))
        // 6 秒保护窗口内：这是我们把目标挤下去造成的，不是用户离开
        assertTrue(EnginePolicy.isUiInducedTaskRemoval(now = 10_000, uiShownAt = 8_000, windowMs = 6_000))
        // 窗口过了就恢复正常判定
        assertFalse(EnginePolicy.isUiInducedTaskRemoval(now = 20_000, uiShownAt = 8_000, windowMs = 6_000))
    }

    @Test
    fun `any failed gate silently stops the whole chain`() {
        // 逐个条件单独置否，结论都必须是「不处理」；这也解释了为什么日志里没有 task removed
        assertFalse(gates(enabled = false))
        assertFalse(gates(featureEnabled = false))
        assertFalse(gates(paused = true))
        assertFalse(gates(offAppForeground = true))
        assertFalse(gates(wifiSuppressed = true))
        assertFalse(gates(target = false))
        assertFalse(gates(aliveTarget = false))
        // 多个条件同时不满足也不能意外放行
        assertFalse(gates(featureEnabled = false, aliveTarget = false))
    }

    @Test
    fun `another target still in front keeps the session alive`() {
        // #16 日志 15:36:33：Telegram 死了，但同一秒 GitHub 回到前台
        assertTrue(EnginePolicy.anotherTargetInForeground(github, telegram, setOf(github, telegram)))
        // 即便开着「划掉任意目标即断开」，还有别的目标在前台就不能收会话
        assertFalse(
            EnginePolicy.shouldEndSessionOnLeave(
                removedWasForeground = false,
                remainingTargets = 1,
                dismissAny = true,
                anotherTargetInForeground = true,
            )
        )
        // 目标全在后台时，划掉任意一个才收
        assertTrue(
            EnginePolicy.shouldEndSessionOnLeave(
                removedWasForeground = false,
                remainingTargets = 1,
                dismissAny = true,
                anotherTargetInForeground = false,
            )
        )
    }

    @Test
    fun `only the last target leaving schedules off`() {
        assertTrue(EnginePolicy.shouldScheduleOffAfterDeath(removedTarget = true, remainingTargets = 0))
        assertFalse(EnginePolicy.shouldScheduleOffAfterDeath(removedTarget = true, remainingTargets = 1))
        assertFalse(EnginePolicy.shouldScheduleOffAfterDeath(removedTarget = false, remainingTargets = 0))
    }

    @Test
    fun `watchdog keeps vpn while a target process is listed and clears it when gone`() {
        // #16 的 6 分钟空白：进程名还在 runningAppProcesses 里（cached 也算），
        // 看门狗每拍都判 alive，继续 ensureVpn
        assertTrue(EnginePolicy.shouldEnsureForProcessState(true))
        // 进程列表被系统裁剪，无法判断时绝不能当成「全死了」
        assertTrue(EnginePolicy.shouldEnsureForProcessState(null))
        // 确认没有目标存活 -> reconcile: target process gone -> off
        assertFalse(EnginePolicy.shouldEnsureForProcessState(false))
    }

    @Test
    fun `death right after foreground is stale but a later death is real`() {
        // 15:42:44 重新打开 App 时系统清理旧 ProcessRecord，这条死亡被防抖忽略
        assertTrue(
            EnginePolicy.isStaleProcessDeath(
                pkg = github,
                lastForeground = github,
                lastForegroundAtMs = 10_000L,
                now = 10_050L,
                windowMs = 3_000L,
            )
        )
        assertFalse(
            EnginePolicy.isStaleProcessDeath(
                pkg = github,
                lastForeground = github,
                lastForegroundAtMs = 10_000L,
                now = 40_000L,
                windowMs = 3_000L,
            )
        )
    }

    @Test
    fun `repeated stale death logs are throttled`() {
        // 一次切应用里同一个包会连着回调好几次，只记第一条
        assertTrue(EnginePolicy.shouldLogStaleDeath(now = 10_000, lastAt = 0L))
        assertFalse(EnginePolicy.shouldLogStaleDeath(now = 10_500, lastAt = 10_000L))
        assertTrue(EnginePolicy.shouldLogStaleDeath(now = 11_000, lastAt = 10_000L))
    }
}
