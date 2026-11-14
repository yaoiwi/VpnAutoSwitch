package com.vpnautoswitch.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ConfigCleanerTest {

    @Test
    fun `removes package from targets`() {
        val config = Config(targets = linkedSetOf("a", "b"))
        val next = config.withoutPackages(setOf("a"))
        assertEquals(setOf("b"), next.targets)
    }

    @Test
    fun `removes package from off targets`() {
        val config = Config(offTargets = linkedSetOf("bank", "pay"))
        val next = config.withoutPackages(setOf("bank"))
        assertEquals(setOf("pay"), next.offTargets)
    }

    @Test
    fun `package listed in both sets is removed from both`() {
        val config = Config(
            targets = linkedSetOf("x", "y"),
            offTargets = linkedSetOf("x"),
        )
        val next = config.withoutPackages(setOf("x"))
        assertEquals(setOf("y"), next.targets)
        assertEquals(emptySet<String>(), next.offTargets)
    }

    @Test
    fun `other settings are preserved`() {
        val config = Config(
            targets = linkedSetOf("a"),
            vpnPackage = "com.vpn.client",
            allowWifi = false,
            wifiOn = linkedSetOf("Home"),
            enabled = false,
        )
        val next = config.withoutPackages(setOf("a"))
        assertEquals("com.vpn.client", next.vpnPackage)
        assertEquals(false, next.allowWifi)
        assertEquals(setOf("Home"), next.wifiOn)
        assertEquals(false, next.enabled)
        assertEquals(emptySet<String>(), next.targets)
    }

    @Test
    fun `empty removal keeps the same instance`() {
        val config = Config(targets = linkedSetOf("a"))
        assertSame(config, config.withoutPackages(emptySet()))
    }

    @Test
    fun `packages not in config are left untouched`() {
        val config = Config(
            targets = linkedSetOf("a", "b"),
            offTargets = linkedSetOf("c"),
        )
        val next = config.withoutPackages(setOf("zzz"))
        assertEquals(setOf("a", "b"), next.targets)
        assertEquals(setOf("c"), next.offTargets)
    }

    @Test
    fun `removes package from group targets`() {
        val rule = VpnRule(id = "g1", vpnPackage = "com.clash", targets = setOf("a", "b"))
        val next = Config(rules = listOf(rule)).withoutPackages(setOf("a"))
        assertEquals(setOf("b"), next.rules.first().targets)
    }

    @Test
    fun `uninstalled client disables its group but keeps the app list`() {
        // 删掉整组等于让用户把清单重配一遍；停用之后重装回来只要把开关打开
        val rule = VpnRule(id = "g1", vpnPackage = "com.clash", targets = setOf("a", "b"))
        val next = Config(rules = listOf(rule)).withoutPackages(setOf("com.clash"))
        assertEquals(false, next.rules.first().enabled)
        assertEquals(setOf("a", "b"), next.rules.first().targets)
    }

    @Test
    fun `empty set keeps the same instance`() {
        val c = Config(targets = setOf("a"), rules = listOf(VpnRule(id = "g1", vpnPackage = "x")))
        assertSame(c, c.withoutPackages(emptySet()))
    }
}
