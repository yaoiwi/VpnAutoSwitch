package com.vpnautoswitch.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigNetworkOverrideTest {

    @Test
    fun `app without override follows the global switches`() {
        val config = Config(networkOverride = mapOf("a" to Config.NET_MOBILE))
        assertEquals(Config.NET_FOLLOW, config.networkModeOf("b"))
        assertEquals(Config.NET_MOBILE, config.networkModeOf("a"))
    }

    @Test
    fun `empty value in the map is treated as follow global`() {
        // 手改配置可能留下空串，语义要和「不在表里」一致
        val config = Config(networkOverride = mapOf("a" to ""))
        assertEquals(Config.NET_FOLLOW, config.networkModeOf("a"))
    }

    @Test
    fun `uninstalled package takes its override with it`() {
        val config = Config(
            targets = linkedSetOf("a", "b"),
            networkOverride = mapOf("a" to Config.NET_ANY, "b" to Config.NET_WIFI),
        )
        val next = config.withoutPackages(setOf("a"))
        assertEquals(mapOf("b" to Config.NET_WIFI), next.networkOverride)
    }

    @Test
    fun `moving an app to the off list clears its override`() {
        // 「打开时关闭」优先，留着覆盖没有意义，还会在导出配置里变成孤儿字段
        val config = Config(
            targets = linkedSetOf("a"),
            networkOverride = mapOf("a" to Config.NET_WIFI),
        )
        val (next, _) = RuleConflicts.keepOff(config, setOf("a"))
        assertTrue(next.networkOverride.isEmpty())
    }

    @Test
    fun `new installs keep both switches on and no overrides`() {
        // 升级上来的默认行为必须和 1.8.5 完全一致：两个网络都允许、没有单应用覆盖
        val config = Config()
        assertTrue(config.allowWifi)
        assertTrue(config.allowMobile)
        assertTrue(config.networkOverride.isEmpty())
    }

    @Test
    fun `schema is bumped for the new fields`() {
        // 3 = 日志里的 SSID 脱敏开关。旧版模块读到新配置只会忽略这个键
        assertEquals(3, Config.SCHEMA)
    }
}
