package com.vpnautoswitch.module.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HookStatusTest {

    private fun allHooks() = HookCounts(
        startActivity = 1,
        activityResumed = 1,
        setState = 1,
        death = 1,
        taskRemoved = 1,
        notif = 1,
        blockClientStart = 1,
    )

    @Test
    fun `hook counts survive a map round trip`() {
        val counts = HookCounts(
            startActivity = 3,
            activityResumed = 1,
            setState = 2,
            death = 4,
            taskRemoved = 2,
            notif = 1,
            blockClientStart = 3,
        )
        assertEquals(counts, hookCountsFromMap(counts.asMap()))
        // 字段名固定且齐全，App 侧按同一份 key 反解才不会漏
        assertEquals(7, counts.asMap().size)
        assertTrue(counts.asMap().keys.containsAll(listOf("death", "taskRemoved", "startActivity")))
    }

    @Test
    fun `missing fields become zero instead of throwing`() {
        val counts = hookCountsFromMap(mapOf("death" to 2))
        assertEquals(2, counts.death)
        assertEquals(0, counts.startActivity)
        assertEquals(0, counts.taskRemoved)
    }

    @Test
    fun `foreground hooks are counted as one capability`() {
        // 三条路互相兜底，任何一条单独挂上都不算「前台检测全丢」
        assertEquals(HookHealth.OK, hookReport(allHooks().copy(
            startActivity = 0, activityResumed = 0, setState = 1,
        )).health)
        assertEquals(HookHealth.OK, hookReport(allHooks().copy(
            startActivity = 1, activityResumed = 0, setState = 0,
        )).health)
        assertEquals(HookHealth.OK, hookReport(allHooks().copy(
            startActivity = 0, activityResumed = 1, setState = 0,
        )).health)
    }

    @Test
    fun `no snapshot is unknown rather than broken`() {
        // 模块还没上报（刚升级没重启）不能吓唬用户说关键 hook 全丢了
        assertEquals(HookHealth.UNKNOWN, hookReport(null).health)
    }

    @Test
    fun `all three foreground paths missing is broken`() {
        val report = hookReport(allHooks().copy(
            startActivity = 0, activityResumed = 0, setState = 0,
        ))
        assertEquals(HookHealth.BROKEN, report.health)
        assertEquals(listOf("前台应用检测"), report.missingCritical)
    }

    @Test
    fun `missing death hook is broken`() {
        val report = hookReport(allHooks().copy(death = 0))
        assertEquals(HookHealth.BROKEN, report.health)
        assertEquals(listOf("进程死亡回调"), report.missingCritical)
    }

    @Test
    fun `missing task removal degrades but is not fatal`() {
        // issue #16 的场景：removeTask 没挂上，还能靠进程死亡 / 看门狗兜底
        val report = hookReport(allHooks().copy(taskRemoved = 0))
        assertEquals(HookHealth.DEGRADED, report.health)
        assertEquals(listOf("划掉最近任务"), report.degraded)
        assertTrue(report.missingCritical.isEmpty())
    }

    @Test
    fun `optional hooks are only noted, never an alarm`() {
        val report = hookReport(allHooks().copy(notif = 0, blockClientStart = 0))
        assertEquals(HookHealth.OK, report.health)
        assertEquals(listOf("系统 VPN 通知隐藏", "客户端自启闸门"), report.cosmetic)
    }

    @Test
    fun `critical failure wins over degraded ones`() {
        val report = hookReport(allHooks().copy(death = 0, taskRemoved = 0, notif = 0))
        assertEquals(HookHealth.BROKEN, report.health)
        assertEquals(listOf("进程死亡回调"), report.missingCritical)
        // 次要 / 可选项仍要带出来，排障时能一次看全
        assertEquals(listOf("划掉最近任务"), report.degraded)
        assertEquals(listOf("系统 VPN 通知隐藏"), report.cosmetic)
    }

    @Test
    fun `everything healthy reports ok with no missing items`() {
        val report = hookReport(allHooks())
        assertEquals(HookHealth.OK, report.health)
        assertTrue(report.missingCritical.isEmpty())
        assertTrue(report.degraded.isEmpty())
        assertTrue(report.cosmetic.isEmpty())
    }
}
