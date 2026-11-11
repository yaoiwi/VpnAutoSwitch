package com.vpnautoswitch.module.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientReadinessTest {

    private val healthy = ClientFacts(
        installed = true,
        enabled = true,
        hasVpnService = true,
        stopped = false,
        suspended = false,
    )

    @Test
    fun `a healthy client is ready`() {
        assertEquals(ClientReadiness.READY, ReadinessPolicy.assess(healthy))
    }

    @Test
    fun `missing or unknown installation is not treated as installed`() {
        // 查不到信息时不能断言「没装」，否则会给一条错误的建议
        assertEquals(
            ClientReadiness.UNKNOWN,
            ReadinessPolicy.assess(healthy.copy(installed = null)),
        )
        assertEquals(
            ClientReadiness.NOT_INSTALLED,
            ReadinessPolicy.assess(healthy.copy(installed = false)),
        )
    }

    @Test
    fun `no vpn service means the module can never start it`() {
        assertEquals(
            ClientReadiness.NO_VPN_SERVICE,
            ReadinessPolicy.assess(healthy.copy(hasVpnService = false)),
        )
        // 查不到服务名单时（PackageManager 抛异常）不当成「没有服务」
        assertEquals(
            ClientReadiness.READY,
            ReadinessPolicy.assess(healthy.copy(hasVpnService = null)),
        )
    }

    @Test
    fun `stopped is reported separately because the module can clear it`() {
        val state = ReadinessPolicy.assess(healthy.copy(stopped = true))
        assertEquals(ClientReadiness.STOPPED, state)
        // 模块每次启动前都会 unstop，所以这不算「要用户动手」
        assertFalse(ReadinessPolicy.needsUser(state))
        assertTrue(ReadinessPolicy.advice(state).isNotEmpty())
    }

    @Test
    fun `suspended needs the user and ranks below stopped`() {
        assertEquals(
            ClientReadiness.SUSPENDED,
            ReadinessPolicy.assess(healthy.copy(suspended = true)),
        )
        assertTrue(ReadinessPolicy.needsUser(ClientReadiness.SUSPENDED))
        // 两个同时成立时，先说模块能自己处理的那个
        assertEquals(
            ClientReadiness.STOPPED,
            ReadinessPolicy.assess(healthy.copy(stopped = true, suspended = true)),
        )
    }

    @Test
    fun `disabled outranks stopped`() {
        // 禁用的包连 unstop 都没意义，先说禁用
        assertEquals(
            ClientReadiness.DISABLED,
            ReadinessPolicy.assess(healthy.copy(enabled = false, stopped = true)),
        )
    }

    @Test
    fun `ready carries no advice and unknown only says it cannot tell`() {
        assertEquals("", ReadinessPolicy.advice(ClientReadiness.READY))
        assertFalse(ReadinessPolicy.needsUser(ClientReadiness.READY))
        // UNKNOWN 有一句解释（可能正在安装），但不算「要用户动手」
        assertTrue(ReadinessPolicy.advice(ClientReadiness.UNKNOWN).isNotEmpty())
        assertFalse(ReadinessPolicy.needsUser(ClientReadiness.UNKNOWN))
    }
}
