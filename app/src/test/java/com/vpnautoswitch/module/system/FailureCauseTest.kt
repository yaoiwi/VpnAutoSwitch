package com.vpnautoswitch.module.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureCauseTest {

    private fun context(
        readiness: ClientReadiness = ClientReadiness.READY,
        everConnected: Boolean = true,
    ) = CauseContext(
        label = "v2rayNG",
        readiness = readiness,
        attempts = 5,
        clientMode = "service",
        outcome = "",
        everConnected = everConnected,
    )

    @Test
    fun `an unusable client explains everything else`() {
        // 客户端没就绪时，说「进程没留住」是在解释一个并不存在的问题
        val cause = FailureCausePolicy.classify(
            readiness = ClientReadiness.DISABLED,
            outcome = ExternalControl.OUTCOME_GONE,
            everConnected = false,
        )
        assertEquals(FailureCause.NOT_READY, cause)
        assertTrue(FailureCausePolicy.describe(cause, context(ClientReadiness.DISABLED)).contains("禁用"))
    }

    @Test
    fun `stopped does not block the rest of the diagnosis`() {
        // STOPPED 是模块自己会清的状态，不该霸占归因
        val cause = FailureCausePolicy.classify(
            readiness = ClientReadiness.STOPPED,
            outcome = ExternalControl.OUTCOME_GONE,
            everConnected = true,
        )
        assertEquals(FailureCause.PROCESS_GONE, cause)
    }

    @Test
    fun `a rejected start is reported before the process check`() {
        val cause = FailureCausePolicy.classify(
            readiness = ClientReadiness.READY,
            outcome = ExternalControl.OUTCOME_REJECTED,
            everConnected = true,
        )
        assertEquals(FailureCause.REJECTED, cause)
    }

    @Test
    fun `never connected is the answer even before the process died`() {
        // 第一次用必然「进程没留住」，但真正该说的是「还没授权」
        val cause = FailureCausePolicy.classify(
            readiness = ClientReadiness.READY,
            outcome = ExternalControl.OUTCOME_GONE,
            everConnected = false,
        )
        assertEquals(FailureCause.NEVER_CONNECTED, cause)
        val text = FailureCausePolicy.describe(cause, context(everConnected = false))
        assertTrue(text.contains("手动"))
    }

    @Test
    fun `a client that worked before gets a process-level cause`() {
        assertEquals(
            FailureCause.PROCESS_GONE,
            FailureCausePolicy.classify(
                ClientReadiness.READY,
                ExternalControl.OUTCOME_GONE,
                everConnected = true,
            ),
        )
        assertEquals(
            FailureCause.NO_TUNNEL,
            FailureCausePolicy.classify(
                ClientReadiness.READY,
                ExternalControl.OUTCOME_ALIVE,
                everConnected = true,
            ),
        )
    }

    @Test
    fun `no observation at all falls back to unknown`() {
        assertEquals(
            FailureCause.UNKNOWN,
            FailureCausePolicy.classify(
                ClientReadiness.READY,
                outcome = "",
                everConnected = true,
            ),
        )
    }

    @Test
    fun `every cause produces a sentence`() {
        FailureCause.values().forEach {
            val text = FailureCausePolicy.describe(it, context())
            assertTrue("$it 没有文案", text.isNotBlank())
        }
    }

    @Test
    fun `only a tunnel-less service setup suggests the adapter`() {
        // 隧道不建 + 只会启服务：换外部控制入口才可能通
        assertTrue(
            FailureCausePolicy.suggestsAdapter(FailureCause.NO_TUNNEL, "service"),
        )
        // 已经是外部控制了，再让人去测一次没意义
        assertFalse(
            FailureCausePolicy.suggestsAdapter(FailureCause.NO_TUNNEL, "activity"),
        )
        // 授权问题不是换启动方式能解决的
        assertFalse(
            FailureCausePolicy.suggestsAdapter(FailureCause.NEVER_CONNECTED, "service"),
        )
    }
}
