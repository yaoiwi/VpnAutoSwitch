package com.vpnautoswitch.module.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnClientPresetsTest {

    @Test
    fun `only installed clients are offered`() {
        val installed = VpnClientPresets.installed(
            listOf("com.v2ray.ang", "com.unrelated.app"),
        )
        assertEquals(1, installed.size)
        assertEquals("v2rayNG", installed.first().name)
    }

    @Test
    fun `keeps the table order regardless of scan order`() {
        val installed = VpnClientPresets.installed(
            listOf("io.nekohasekai.sfa", "com.github.metacubex.clash.meta"),
        )
        assertEquals(
            listOf("Clash Meta for Android", "sing-box"),
            installed.map { it.name },
        )
    }

    @Test
    fun `nothing is offered when no known client is installed`() {
        assertTrue(VpnClientPresets.installed(listOf("com.example.other")).isEmpty())
    }

    @Test
    fun `name lookup falls back to null for unknown packages`() {
        assertEquals("NekoBox", VpnClientPresets.nameOf("moe.nb4a"))
        assertNull(VpnClientPresets.nameOf("com.example.other"))
    }

    @Test
    fun `preset package names are unique`() {
        val packages = VpnClientPresets.ALL.map { it.packageName }
        assertEquals(packages.size, packages.toSet().size)
    }
}
