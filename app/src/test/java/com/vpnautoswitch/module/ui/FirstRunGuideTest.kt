package com.vpnautoswitch.module.ui

import com.vpnautoswitch.module.ui.DiagnosticsPolicy.ModuleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunGuideTest {

    private fun steps(
        state: ModuleState = ModuleState.CURRENT,
        pkg: String = "com.v2ray.ang",
        targets: Int = 2,
        wifi: Int = 0,
        connected: Boolean = true,
    ) = FirstRunGuide.steps(state, pkg, targets, wifi, connected)

    @Test
    fun `a fresh install has all four steps open`() {
        val list = steps(state = ModuleState.NEVER, pkg = "", targets = 0, connected = false)
        assertEquals(4, list.size)
        assertEquals(4, FirstRunGuide.remaining(list))
        assertFalse(FirstRunGuide.isComplete(list))
        // 每一步都带一个能照着做的动作，不是「未完成」三个字
        assertTrue(list.all { it.detail.isNotBlank() })
    }

    @Test
    fun `everything done hides the card`() {
        val list = steps()
        assertEquals(0, FirstRunGuide.remaining(list))
        assertTrue(FirstRunGuide.isComplete(list))
    }

    @Test
    fun `old module code still counts as running`() {
        // 升级后还没重启：模块确实在跑，要做的是重启，不是重新安装
        val list = steps(state = ModuleState.OLD_CODE)
        assertTrue(list.first { it.id == FirstRunGuide.ID_MODULE }.done)
        assertTrue(list.first { it.id == FirstRunGuide.ID_MODULE }.detail.contains("重启"))
    }

    @Test
    fun `stale module is not done`() {
        val list = steps(state = ModuleState.STALE)
        val module = list.first { it.id == FirstRunGuide.ID_MODULE }
        assertFalse(module.done)
        assertTrue(module.detail.contains("重启"))
    }

    @Test
    fun `wifi rule alone satisfies the trigger step`() {
        // 只用 Wi-Fi 规则、一个目标应用都没有，也算设置好了触发条件
        val list = steps(targets = 0, wifi = 1)
        assertTrue(list.first { it.id == FirstRunGuide.ID_TRIGGER }.done)
    }

    @Test
    fun `the connect step waits for a trigger instead of blaming the user`() {
        // 还没设触发条件时，「成功连接一次」不该写成「去打不开的应用试」——
        // 用户照做只会更困惑
        val list = steps(targets = 0, wifi = 0, connected = false)
        val connect = list.first { it.id == FirstRunGuide.ID_CONNECT }
        assertFalse(connect.done)
        assertTrue(connect.detail.contains("设置好触发条件"))
    }

    @Test
    fun `the connect step explains the first-time authorization`() {
        val list = steps(connected = false)
        val connect = list.first { it.id == FirstRunGuide.ID_CONNECT }
        assertTrue(connect.detail.contains("手动"))
        assertTrue(connect.detail.contains("授权"))
    }
}
