package com.vpnautoswitch.module.ui

import com.vpnautoswitch.module.BuildConfig
import com.vpnautoswitch.module.Config
import com.vpnautoswitch.module.system.HookCounts
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

    @Test
    fun `target rule without tunnel transport is a warning`() {
        val checks = DiagnosticsReport.buildChecks(
            diagnostics = Diagnostics(
                activeRule = "target",
                vpnConnected = false,
            ),
            config = Config(vpnPackage = "com.example.vpn"),
        )
        val tunnel = checks.first { it.title == "隧道状态" }
        assertEquals(CheckStatus.WARN, tunnel.status)
        assertTrue(tunnel.detail.contains("没有 VPN transport"))
    }

    @Test
    fun `off target rule with tunnel transport is a warning`() {
        val checks = DiagnosticsReport.buildChecks(
            diagnostics = Diagnostics(
                activeRule = "offTarget",
                vpnConnected = true,
            ),
            config = Config(vpnPackage = "com.example.vpn"),
        )
        val tunnel = checks.first { it.title == "隧道状态" }
        assertEquals(CheckStatus.WARN, tunnel.status)
        assertTrue(tunnel.detail.contains("仍有 VPN transport"))
    }

    private fun healthyHooks() = HookCounts(
        startActivity = 1,
        activityResumed = 1,
        setState = 1,
        death = 1,
        taskRemoved = 1,
        notif = 1,
        blockClientStart = 1,
    )

    private fun hookCheck(hooks: HookCounts?, version: Int = BuildConfig.VERSION_CODE) =
        DiagnosticsReport.buildChecks(
            diagnostics = Diagnostics(hooks = hooks, hookSnapshotVersion = version),
            config = Config(vpnPackage = "com.example.vpn"),
        ).first { it.title == "Hook 挂载" }

    @Test
    fun `missing hook snapshot is unknown, not a failure`() {
        // 刚升级没重启：不能把「还没上报」写成关键 hook 全丢
        val check = hookCheck(null)
        assertEquals(CheckStatus.UNKNOWN, check.status)
        assertTrue(check.detail.contains("重启"))
    }

    @Test
    fun `hook snapshot from an older module version is unknown`() {
        val check = hookCheck(healthyHooks(), version = BuildConfig.VERSION_CODE - 1)
        assertEquals(CheckStatus.UNKNOWN, check.status)
        assertTrue(check.detail.contains("重启"))
    }

    @Test
    fun `fully hooked module passes the hook check`() {
        assertEquals(CheckStatus.OK, hookCheck(healthyHooks()).status)
    }

    @Test
    fun `missing death hook is a red failure`() {
        val check = hookCheck(healthyHooks().copy(death = 0))
        assertEquals(CheckStatus.FAIL, check.status)
        assertTrue(check.detail.contains("进程死亡回调"))
    }

    @Test
    fun `missing task removal is a warning not a failure`() {
        // issue #16：这条链丢了还能靠进程死亡兜底，标黄就够
        val check = hookCheck(healthyHooks().copy(taskRemoved = 0))
        assertEquals(CheckStatus.WARN, check.status)
        assertTrue(check.detail.contains("划掉最近任务"))
    }

    @Test
    fun `optional hook gaps do not fail the check`() {
        val check = hookCheck(healthyHooks().copy(notif = 0, blockClientStart = 0))
        assertEquals(CheckStatus.OK, check.status)
        assertTrue(check.detail.contains("可选"))
    }

    private fun sourceCheck(source: String, mirrorAt: Long = 0L) =
        DiagnosticsReport.buildChecks(
            diagnostics = Diagnostics(configSource = source, configMirrorAt = mirrorAt),
            config = Config(vpnPackage = "com.example.vpn"),
        ).first { it.title == "配置来源" }

    @Test
    fun `live config source passes`() {
        assertEquals(CheckStatus.OK, sourceCheck("provider").status)
    }

    @Test
    fun `a fresh mirror is normal but an old one warns`() {
        assertEquals(CheckStatus.OK, sourceCheck("mirror", now - 60_000L).status)
        val stale = sourceCheck("mirror", now - 64 * 60_000L)
        assertEquals(CheckStatus.WARN, stale.status)
        assertTrue(stale.detail.contains("打开一次本应用"))
    }

    @Test
    fun `no config at all is a red failure`() {
        assertEquals(CheckStatus.FAIL, sourceCheck("none").status)
    }

    @Test
    fun `unreported config source is unknown`() {
        assertEquals(CheckStatus.UNKNOWN, sourceCheck("").status)
    }

    @Test
    fun `copied report includes hook counts and config source`() {
        val text = DiagnosticsReport.buildText(
            diagnostics = Diagnostics(
                hooks = healthyHooks(),
                hookSnapshotVersion = BuildConfig.VERSION_CODE,
                configSource = "mirror",
                configMirrorAt = now - 64 * 60_000L,
            ),
            config = Config(vpnPackage = "com.example.vpn"),
        )
        assertTrue(text.contains("Hook：startActivity=1"))
        assertTrue(text.contains("配置来源：镜像"))
        assertTrue(text.contains("[WARN] 配置来源"))
    }
}
