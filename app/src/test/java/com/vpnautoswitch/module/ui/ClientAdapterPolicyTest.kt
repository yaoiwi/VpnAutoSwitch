package com.vpnautoswitch.module.ui

import com.vpnautoswitch.module.apps.VpnClientInspection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientAdapterPolicyTest {

    @Test
    fun `empty and missing clients return setup guidance`() {
        assertEquals("先选择默认 VPN 客户端", recommendation("", null))
        assertTrue(recommendation("com.example.vpn", null).contains("未安装"))
    }

    @Test
    fun `disabled client is called out first`() {
        val inspection = VpnClientInspection(
            installed = true,
            enabled = false,
            hasVpnService = true,
            hasExternalStart = true,
        )
        assertTrue(recommendation("com.example.vpn", inspection).contains("禁用"))
    }

    @Test
    fun `external control is preferred over vpn service`() {
        val inspection = VpnClientInspection(
            installed = true,
            enabled = true,
            hasVpnService = true,
            hasExternalStart = true,
        )
        assertTrue(recommendation("com.example.vpn", inspection).contains("外部控制"))
    }

    @Test
    fun `vpn service is recommended when no external control exists`() {
        val inspection = VpnClientInspection(
            installed = true,
            enabled = true,
            hasVpnService = true,
            hasExternalStart = false,
        )
        assertTrue(recommendation("com.example.vpn", inspection).contains("always-on"))
    }

    @Test
    fun `client without vpn service cannot be automated`() {
        val inspection = VpnClientInspection(
            installed = true,
            enabled = true,
            hasVpnService = false,
            hasExternalStart = false,
        )
        assertTrue(recommendation("com.example.vpn", inspection).contains("没有 VpnService"))
    }
}
