package com.vpnautoswitch.module.ui

import com.vpnautoswitch.module.system.HookHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsPolicyTest {

    private val now = 1_000_000L

    @Test
    fun `heartbeat freshness handles zero future and exact boundary`() {
        assertFalse(DiagnosticsPolicy.isHeartbeatFresh(0L, now))
        assertFalse(DiagnosticsPolicy.isHeartbeatFresh(now + 1, now))
        assertTrue(DiagnosticsPolicy.isHeartbeatFresh(now, now))
        assertTrue(DiagnosticsPolicy.isHeartbeatFresh(now - 90_000L, now))
        assertFalse(DiagnosticsPolicy.isHeartbeatFresh(now - 90_001L, now))
    }

    @Test
    fun `heartbeat age never goes negative`() {
        assertEquals(0L, DiagnosticsPolicy.heartbeatAge(0L, now))
        assertEquals(0L, DiagnosticsPolicy.heartbeatAge(now + 500, now))
        assertEquals(5_000L, DiagnosticsPolicy.heartbeatAge(now - 5_000, now))
    }

    @Test
    fun `module state distinguishes never stale old and current`() {
        assertEquals(
            DiagnosticsPolicy.ModuleState.NEVER,
            DiagnosticsPolicy.moduleState(0L, 0, 66, now),
        )
        assertEquals(
            DiagnosticsPolicy.ModuleState.STALE,
            DiagnosticsPolicy.moduleState(now - 120_000L, 66, 66, now),
        )
        assertEquals(
            DiagnosticsPolicy.ModuleState.OLD_CODE,
            DiagnosticsPolicy.moduleState(now - 1_000L, 65, 66, now),
        )
        assertEquals(
            DiagnosticsPolicy.ModuleState.CURRENT,
            DiagnosticsPolicy.moduleState(now - 1_000L, 66, 66, now),
        )
    }

    @Test
    fun `expected vpn follows active rule`() {
        assertEquals(
            DiagnosticsPolicy.ExpectedVpn.ON,
            DiagnosticsPolicy.expectedVpn("target"),
        )
        assertEquals(
            DiagnosticsPolicy.ExpectedVpn.ON,
            DiagnosticsPolicy.expectedVpn("wifiOn"),
        )
        assertEquals(
            DiagnosticsPolicy.ExpectedVpn.OFF,
            DiagnosticsPolicy.expectedVpn("offTarget"),
        )
        assertEquals(
            DiagnosticsPolicy.ExpectedVpn.OFF,
            DiagnosticsPolicy.expectedVpn("wifiOff"),
        )
        assertEquals(
            DiagnosticsPolicy.ExpectedVpn.OFF,
            DiagnosticsPolicy.expectedVpn("userOff"),
        )
        assertEquals(
            DiagnosticsPolicy.ExpectedVpn.UNKNOWN,
            DiagnosticsPolicy.expectedVpn("paused"),
        )
        assertEquals(
            DiagnosticsPolicy.ExpectedVpn.UNKNOWN,
            DiagnosticsPolicy.expectedVpn(""),
        )
    }

    @Test
    fun `vpn mismatch only reports when rule forces a state`() {
        assertTrue(DiagnosticsPolicy.vpnMismatch("target", false) == true)
        assertTrue(DiagnosticsPolicy.vpnMismatch("target", true) == false)
        assertTrue(DiagnosticsPolicy.vpnMismatch("offTarget", true) == true)
        assertTrue(DiagnosticsPolicy.vpnMismatch("offTarget", false) == false)
        assertEquals(null, DiagnosticsPolicy.vpnMismatch("paused", true))
        assertEquals(null, DiagnosticsPolicy.vpnMismatch("idle", false))
    }

    @Test
    fun `rule labels cover every reported rule`() {
        assertEquals("目标应用 → 应连接", DiagnosticsPolicy.ruleLabel("target"))
        assertEquals("打开即关闭 → 应断开", DiagnosticsPolicy.ruleLabel("offTarget"))
        assertEquals("已暂停 → 不干预", DiagnosticsPolicy.ruleLabel("paused"))
        assertEquals("尚未收到规则", DiagnosticsPolicy.ruleLabel(""))
        assertEquals("custom", DiagnosticsPolicy.ruleLabel("custom"))
    }

    @Test
    fun `last event picks the newest non blank line`() {
        val log = "12:01:00  a\n12:02:00  b\n\n"
        assertEquals("12:02:00  b", DiagnosticsPolicy.lastEvent(log))
        assertEquals("", DiagnosticsPolicy.lastEvent(""))
    }

    @Test
    fun `summary explains a connected target app`() {
        val s = DiagnosticsPolicy.summarize(
            heartbeat = now,
            moduleVersion = 72,
            appVersion = 72,
            paused = false,
            activeRule = "target",
            ruleDetail = "目标应用在前台：YouTube",
            foreground = "com.google.android.youtube",
            vpnConnected = true,
            network = "Wi-Fi",
            log = "12:04:00  com.google.android.youtube -> connect",
            now = now,
        )
        assertEquals("运行中", s.headline)
        assertEquals("com.google.android.youtube", s.foreground)
        assertTrue(s.rule.startsWith("目标应用 → 应连接"))
        assertEquals("已连接", s.actual)
        assertEquals(DiagnosticsPolicy.Tone.NORMAL, s.actualTone)
        assertTrue(s.lastAction.contains("connect"))
    }

    @Test
    fun `summary warns when rule wants vpn but nothing is connected`() {
        val s = DiagnosticsPolicy.summarize(
            heartbeat = now,
            moduleVersion = 72,
            appVersion = 72,
            paused = false,
            activeRule = "target",
            ruleDetail = "",
            foreground = "com.example.bank",
            vpnConnected = false,
            network = "Wi-Fi",
            log = "",
            now = now,
        )
        assertEquals("未连接", s.actual)
        assertEquals(DiagnosticsPolicy.Tone.WARN, s.actualTone)
        assertEquals("暂无记录", s.lastAction)
    }

    @Test
    fun `summary marks unknown when module never reported`() {
        val s = DiagnosticsPolicy.summarize(
            heartbeat = 0L,
            moduleVersion = 0,
            appVersion = 72,
            paused = false,
            activeRule = "target",
            ruleDetail = "",
            foreground = "com.example",
            vpnConnected = true,
            network = "Wi-Fi",
            log = "",
            now = now,
        )
        assertEquals("等待注入", s.headline)
        assertEquals("未知", s.foreground)
        assertEquals("未知", s.rule)
        assertEquals("未知", s.actual)
        assertEquals(DiagnosticsPolicy.Tone.MUTED, s.actualTone)
    }

    @Test
    fun `hook snapshot is usable only when written by the current module version`() {
        assertTrue(DiagnosticsPolicy.hookSnapshotUsable(128, 128))
        assertFalse(DiagnosticsPolicy.hookSnapshotUsable(0, 128))
        assertFalse(DiagnosticsPolicy.hookSnapshotUsable(127, 128))
        assertFalse(DiagnosticsPolicy.hookSnapshotUsable(129, 128))
    }

    @Test
    fun `config source distinguishes live mirror stale missing and unknown`() {
        assertEquals(DiagnosticsPolicy.ConfigSourceState.FRESH, DiagnosticsPolicy.configSourceStatus("provider", 0L, now))
        assertEquals(DiagnosticsPolicy.ConfigSourceState.MISSING, DiagnosticsPolicy.configSourceStatus("none", 0L, now))
        assertEquals(DiagnosticsPolicy.ConfigSourceState.UNKNOWN, DiagnosticsPolicy.configSourceStatus("", 0L, now))
        assertEquals(DiagnosticsPolicy.ConfigSourceState.UNKNOWN, DiagnosticsPolicy.configSourceStatus("something-else", 0L, now))
    }

    @Test
    fun `a recent config mirror is normal but an old one warns`() {
        val threshold = DiagnosticsPolicy.CONFIG_MIRROR_STALE_MS
        // 用一个足够大的基准：类里的 now 只有 1_000_000L，减 30 分钟会变成负时间戳，
        // 被当成无效值，测不到阈值边界。
        val base = 64 * 60_000L + 100_000L
        // 开机初期 provider 还没起来，退回刚写下的镜像属于正常
        assertEquals(
            DiagnosticsPolicy.ConfigSourceState.MIRROR_RECENT,
            DiagnosticsPolicy.configSourceStatus("mirror", base - 60_000L, base),
        )
        // 刚好到阈值仍算正常，超过才开始提示
        assertEquals(
            DiagnosticsPolicy.ConfigSourceState.MIRROR_RECENT,
            DiagnosticsPolicy.configSourceStatus("mirror", base - threshold, base),
        )
        assertEquals(
            DiagnosticsPolicy.ConfigSourceState.MIRROR_STALE,
            DiagnosticsPolicy.configSourceStatus("mirror", base - threshold - 1L, base),
        )
        // 64 分钟前的镜像：issue #10 里那条线索
        assertEquals(
            DiagnosticsPolicy.ConfigSourceState.MIRROR_STALE,
            DiagnosticsPolicy.configSourceStatus("mirror", base - 64 * 60_000L, base),
        )
    }

    @Test
    fun `config mirror with a bad timestamp is not treated as stale`() {
        // 没有写入时刻，或时钟回拨（写入时刻在未来）：宁可不报警，也不误报
        assertEquals(DiagnosticsPolicy.ConfigSourceState.MIRROR_RECENT, DiagnosticsPolicy.configSourceStatus("mirror", 0L, now))
        assertEquals(DiagnosticsPolicy.ConfigSourceState.MIRROR_RECENT, DiagnosticsPolicy.configSourceStatus("mirror", now + 5_000L, now))
    }

    // ------------------------------------------------------------ 一句话结论

    private fun verdict(
        moduleState: DiagnosticsPolicy.ModuleState = DiagnosticsPolicy.ModuleState.CURRENT,
        hookHealth: HookHealth? = HookHealth.OK,
        vpnInstalled: Boolean? = true,
        vpnHasService: Boolean? = true,
        vpnConnected: Boolean = true,
        alwaysOnApp: String? = null,
        vpnPackage: String = "com.v2ray.ang",
        failureStreak: Int = 0,
        paused: Boolean = false,
    ) = DiagnosticsPolicy.conclusion(
        DiagnosticsPolicy.VerdictInput(
            moduleState = moduleState,
            hookHealth = hookHealth,
            vpnInstalled = vpnInstalled,
            vpnHasService = vpnHasService,
            vpnConnected = vpnConnected,
            alwaysOnApp = alwaysOnApp,
            vpnPackage = vpnPackage,
            failureStreak = failureStreak,
            paused = paused,
        )
    )

    @Test
    fun `module not running is the first thing to say`() {
        val text = verdict(moduleState = DiagnosticsPolicy.ModuleState.NEVER)
        assertTrue(text.contains("LSPosed"))
    }

    @Test
    fun `an old module still in system_server asks for a reboot`() {
        assertTrue(verdict(moduleState = DiagnosticsPolicy.ModuleState.OLD_CODE).contains("重启"))
    }

    @Test
    fun `broken hooks are called out before anything about the tunnel`() {
        val text = verdict(hookHealth = HookHealth.BROKEN, vpnConnected = false)
        assertTrue(text.contains("hook"))
        assertTrue(text.contains("重启"))
    }

    @Test
    fun `degraded hooks explain what still works`() {
        val text = verdict(hookHealth = HookHealth.DEGRADED)
        assertTrue(text.contains("划掉最近任务"))
        assertTrue(text.contains("返回键"))
    }

    @Test
    fun `a missing or broken client outranks the tunnel state`() {
        assertTrue(verdict(vpnInstalled = false, vpnConnected = false).contains("没装"))
        assertTrue(verdict(vpnHasService = false, vpnConnected = false).contains("VpnService"))
    }

    @Test
    fun `repeated failures point at the package state line`() {
        val text = verdict(vpnConnected = false, failureStreak = 7)
        assertTrue(text.contains("7"))
        assertTrue(text.contains("包状态"))
    }

    @Test
    fun `always-on pointing at the client but no tunnel suggests checking the client`() {
        val text = verdict(vpnConnected = false, alwaysOnApp = "com.v2ray.ang", failureStreak = 0)
        assertTrue(text.contains("隧道"))
        assertTrue(text.contains("点一次连接"))
    }

    @Test
    fun `a short failure streak still reports the switch in progress`() {
        val text = verdict(vpnConnected = false, failureStreak = 1)
        assertTrue(text.contains("过几秒"))
    }

    @Test
    fun `everything fine says so`() {
        assertEquals("各项正常。", verdict())
    }

    @Test
    fun `paused automation is named instead of blamed on the client`() {
        assertTrue(verdict(paused = true, vpnConnected = false).contains("暂停"))
    }

    @Test
    fun `pinned service mode suggests external control after repeated start failures`() {
        assertTrue(
            DiagnosticsPolicy.shouldSuggestExternalControl(
                stage = "enable",
                streak = 2,
                clientMode = "service",
            )
        )
        assertTrue(
            DiagnosticsPolicy.shouldSuggestExternalControl(
                stage = "enable",
                streak = 7,
                clientMode = "service",
            )
        )
    }

    @Test
    fun `one swallow does not mean the mode is wrong`() {
        // 只失败一次可能只是网络慢、客户端还在初始化
        assertFalse(
            DiagnosticsPolicy.shouldSuggestExternalControl(
                stage = "enable",
                streak = 1,
                clientMode = "service",
            )
        )
    }

    @Test
    fun `no suggestion when the mode is not pinned to service`() {
        assertFalse(
            DiagnosticsPolicy.shouldSuggestExternalControl(
                stage = "enable",
                streak = 5,
                clientMode = "activity",
            )
        )
        // 还没记过方式：模块会自己按「外部控制 → 服务 → 界面」兜底，不用人操心
        assertFalse(
            DiagnosticsPolicy.shouldSuggestExternalControl(
                stage = "enable",
                streak = 5,
                clientMode = "",
            )
        )
    }

    @Test
    fun `suggestion only applies to the start stage`() {
        assertFalse(
            DiagnosticsPolicy.shouldSuggestExternalControl(
                stage = "stop",
                streak = 5,
                clientMode = "service",
            )
        )
        assertFalse(
            DiagnosticsPolicy.shouldSuggestExternalControl(
                stage = "forceStop",
                streak = 5,
                clientMode = "service",
            )
        )
    }
}
