package com.vpnautoswitch.module.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartBlockTest {

    private fun input(
        expectedOn: Boolean = true,
        connected: Boolean = false,
        readiness: ClientReadiness = ClientReadiness.READY,
        attempts: Int = 0,
        cooldownMs: Long = 0L,
        waitingClient: Boolean = false,
        everConnected: Boolean = true,
    ) = BlockInput(
        expectedOn = expectedOn,
        vpnConnected = connected,
        readiness = readiness,
        attempts = attempts,
        cooldownMs = cooldownMs,
        waitingClient = waitingClient,
        everConnected = everConnected,
    )

    @Test
    fun `nothing to say when the rule does not want it on`() {
        assertNull(StartBlockPolicy.of(input(expectedOn = false)))
    }

    @Test
    fun `nothing to say once connected`() {
        assertNull(StartBlockPolicy.of(input(connected = true)))
    }

    @Test
    fun `an unusable client comes first and needs the user`() {
        val message = StartBlockPolicy.of(input(readiness = ClientReadiness.DISABLED))
        assertTrue(message != null)
        assertTrue(message!!.needsUser)
        assertTrue(message.text.contains("禁用"))
    }

    @Test
    fun `waiting for the client is not an error`() {
        val message = StartBlockPolicy.of(
            input(attempts = 1, waitingClient = true, cooldownMs = 3_000L),
        )
        assertTrue(message != null)
        // 模块自己在跑，标黄会让用户以为出错了
        assertFalse(message!!.needsUser)
        assertTrue(message.text.contains("初始化"))
    }

    @Test
    fun `exhausted attempts promise a different path`() {
        val message = StartBlockPolicy.of(input(attempts = Engine.MAX_EXTERNAL_ATTEMPTS))
        assertTrue(message != null)
        assertTrue(message!!.text.contains("换一条启动路径"))
        assertFalse(message.needsUser)
    }

    @Test
    fun `cooldown does not leak seconds into the snapshot`() {
        val message = StartBlockPolicy.of(input(cooldownMs = 4_200L))
        assertTrue(message != null)
        // 带秒数会让这句话每拍都变，进而让运行时快照每 5 秒重写一次
        assertFalse(message!!.text.contains("4"))
        assertTrue(message.text.contains("冷却"))
    }

    @Test
    fun `an in-flight request just says it is waiting for the tunnel`() {
        val message = StartBlockPolicy.of(input(attempts = 2))
        assertTrue(message != null)
        assertTrue(message!!.text.contains("等隧道"))
    }

    @Test
    fun `never connected tells the user to authorize manually`() {
        val message = StartBlockPolicy.of(input(everConnected = false))
        assertTrue(message != null)
        assertTrue(message!!.needsUser)
        assertTrue(message.text.contains("手动"))
    }

    @Test
    fun `a known-good client with nothing in flight is quiet`() {
        // 没有卡点就不要占着状态卡那一行
        assertNull(StartBlockPolicy.of(input(everConnected = true)))
        assertEquals(0, StartBlockPolicy.of(input())?.text?.length ?: 0)
    }
}
