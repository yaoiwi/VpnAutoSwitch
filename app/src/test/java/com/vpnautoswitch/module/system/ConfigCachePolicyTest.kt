package com.vpnautoswitch.module.system

import com.vpnautoswitch.module.Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCachePolicyTest {

    @Test
    fun `a mirror written just now is fresh`() {
        assertTrue(ConfigCachePolicy.isFresh(savedAt = 1_000_000L, now = 1_060_000L))
    }

    @Test
    fun `a mirror older than the limit is dropped`() {
        val now = 100L * ConfigCachePolicy.MAX_AGE_MS
        assertFalse(ConfigCachePolicy.isFresh(savedAt = now - ConfigCachePolicy.MAX_AGE_MS - 1, now = now))
    }

    @Test
    fun `a mirror exactly at the limit is still accepted`() {
        val now = 100L * ConfigCachePolicy.MAX_AGE_MS
        assertTrue(ConfigCachePolicy.isFresh(savedAt = now - ConfigCachePolicy.MAX_AGE_MS, now = now))
    }

    @Test
    fun `a mirror without a timestamp is rejected`() {
        assertFalse(ConfigCachePolicy.isFresh(savedAt = 0L, now = 1_000_000L))
    }

    @Test
    fun `a mirror from the future is rejected`() {
        // 时钟被改过之后写下的时间戳不能信，否则一个月后「永远新鲜」
        assertFalse(ConfigCachePolicy.isFresh(savedAt = 2_000_000L, now = 1_000_000L))
    }

    @Test
    fun `an empty config is not usable as a fallback`() {
        // 镜像里是「用户什么都没配」，那和读不到没区别，不能拿来当依据
        assertFalse(ConfigCachePolicy.isUsable(Config()))
        assertFalse(ConfigCachePolicy.isUsable(null))
    }

    @Test
    fun `a config with a vpn package is usable`() {
        assertTrue(ConfigCachePolicy.isUsable(Config(vpnPackage = "com.v2ray.ang")))
    }

    @Test
    fun `a config with only targets is usable`() {
        assertTrue(ConfigCachePolicy.isUsable(Config(targets = setOf("com.twitter.android"))))
    }
}
