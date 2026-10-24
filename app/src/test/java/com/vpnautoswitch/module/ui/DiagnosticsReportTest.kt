package com.vpnautoswitch.module.ui

import com.vpnautoswitch.module.BuildConfig
import com.vpnautoswitch.module.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {

    private val now = System.currentTimeMillis()

    @Test
    fun `empty config reports missing client and triggers`() {
        val checks = DiagnosticsReport.buildChecks(
            diagnostics = Diagnostics(
                heartbeat = now,
                heartbeatFresh = true,
                moduleState = DiagnosticsPolicy.ModuleState.CURRENT,
                moduleVersion = BuildConfig.VERSION_CODE,
            ),
            config = Config(),
        )
        assertEquals(CheckStatus.FAIL, checks.first { it.title == "VPN 客户端" }.status)
        assertEquals(CheckStatus.WARN, checks.first { it.title == "触发条件" }.status)
    }

    @Test
    fun `healthy client and current module pass`() {
        val checks = DiagnosticsReport.buildChecks(
            diagnostics = Diagnostics(
                heartbeat = now,
                heartbeatFresh = true,
                moduleState = DiagnosticsPolicy.ModuleState.CURRENT,
                moduleVersion = BuildConfig.VERSION_CODE,
                vpnPackage = "com.example.vpn",
                vpnInstalled = true,
                vpnEnabled = true,
                vpnHasService = true,
                activeRule = "target",
                ruleDetail = "目标应用在前台",
                ruleUpdatedAt = now,
                alwaysOnApp = "com.example.vpn",
            ),
            config = Config(vpnPackage = "com.example.vpn", targets = setOf("com.example.app")),
        )
        assertEquals(CheckStatus.OK, checks.first { it.title == "VPN 客户端" }.status)
        assertEquals(CheckStatus.OK, checks.first { it.title == "模块运行" }.status)
        assertEquals(CheckStatus.OK, checks.first { it.title == "始终开启的 VPN" }.status)
    }

    @Test
    fun `old module code is a warning`() {
        val checks = DiagnosticsReport.buildChecks(
            diagnostics = Diagnostics(
                heartbeat = now,
                moduleState = DiagnosticsPolicy.ModuleState.OLD_CODE,
                moduleVersion = 65,
            ),
            config = Config(vpnPackage = "com.example.vpn"),
        )
        val module = checks.first { it.title == "模块运行" }
        assertEquals(CheckStatus.WARN, module.status)
        assertTrue(module.detail.contains("重启"))
    }
}
