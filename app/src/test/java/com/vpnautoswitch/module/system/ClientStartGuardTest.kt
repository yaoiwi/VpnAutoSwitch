package com.vpnautoswitch.module.system

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClientStartGuardTest {

    @Before
    fun reset() = ClientStartGuard.release()

    @After
    fun cleanup() = ClientStartGuard.release()

    @Test
    fun `blocks the client that was just force stopped`() {
        ClientStartGuard.suppress("com.v2ray.ang", now = 1_000L, durationMs = 6_000L)

        assertTrue(ClientStartGuard.blocked("com.v2ray.ang", 1_000L))
        assertTrue(ClientStartGuard.blocked("com.v2ray.ang", 6_999L))
    }

    @Test
    fun `window closes on its own`() {
        ClientStartGuard.suppress("com.v2ray.ang", now = 1_000L, durationMs = 6_000L)

        assertFalse(ClientStartGuard.blocked("com.v2ray.ang", 7_000L))
        // 过期后应该顺手清空，下一次调用不再进同步块
        assertFalse(ClientStartGuard.active(20_000L))
    }

    @Test
    fun `other packages are never blocked`() {
        ClientStartGuard.suppress("com.v2ray.ang", now = 1_000L, durationMs = 6_000L)

        assertFalse(ClientStartGuard.blocked("com.twitter.android", 1_000L))
        assertFalse(ClientStartGuard.blocked(null, 1_000L))
    }

    @Test
    fun `release lets the client start again`() {
        ClientStartGuard.suppress("com.v2ray.ang", now = 1_000L, durationMs = 6_000L)
        assertTrue(ClientStartGuard.blocked("com.v2ray.ang", 1_500L))

        ClientStartGuard.release()

        assertFalse(ClientStartGuard.blocked("com.v2ray.ang", 1_600L))
    }

    @Test
    fun `empty package or zero duration does nothing`() {
        ClientStartGuard.suppress("", now = 1_000L, durationMs = 6_000L)
        assertFalse(ClientStartGuard.active(1_000L))

        ClientStartGuard.suppress("com.v2ray.ang", now = 1_000L, durationMs = 0L)
        assertFalse(ClientStartGuard.active(1_000L))
    }

    @Test
    fun `newer force stop replaces the previous target`() {
        ClientStartGuard.suppress("com.v2ray.ang", now = 1_000L, durationMs = 6_000L)
        ClientStartGuard.suppress("com.wireguard.android", now = 2_000L, durationMs = 6_000L)

        assertFalse(ClientStartGuard.blocked("com.v2ray.ang", 2_100L))
        assertTrue(ClientStartGuard.blocked("com.wireguard.android", 2_100L))
    }
}
