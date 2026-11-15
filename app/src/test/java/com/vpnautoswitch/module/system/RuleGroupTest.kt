package com.vpnautoswitch.module.system

import com.vpnautoswitch.module.Config
import com.vpnautoswitch.module.VpnRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleGroupTest {

    private val science = VpnRule(
        id = "g1",
        label = "科学上网",
        vpnPackage = "com.github.metacubex.clash.meta",
        targets = setOf("com.android.chrome", "com.google.android.youtube"),
    )

    private val nas = VpnRule(
        id = "g2",
        label = "内网穿透",
        vpnPackage = "com.tailscale.ipn",
        targets = setOf("com.synology.dsfile"),
    )

    private val homeProxy = VpnRule(
        id = "g3",
        label = "旁路由",
        vpnPackage = "com.super.proxy",
        wifiOn = setOf("HomeWiFi"),
    )

    private fun cfg(vararg rules: VpnRule) = Config(
        vpnPackage = "com.v2ray.ang",
        targets = setOf("org.mozilla.firefox"),
        rules = rules.toList(),
    )

    @Test
    fun `no rules falls back to the default group`() {
        // 老配置就是这个样子：一个客户端 + 一张应用表，行为不能有一点变化
        val c = cfg()
        assertNull(
            c.hitGroup(RuleProbe("HomeWiFi", true, setOf("com.android.chrome"))),
        )
    }

    @Test
    fun `wifi match beats app match`() {
        // 连上家里 Wi-Fi 就全局走旁路由，哪怕前台的 Chrome 属于科学上网组。
        // 用户不会预期「打开了 Clash 的应用，连上家里 Wi-Fi，结果连的还是 Clash」
        val c = cfg(science, homeProxy)
        val hit = c.hitGroup(RuleProbe("HomeWiFi", true, setOf("com.android.chrome")))
        assertEquals("g3", hit?.rule?.id)
        assertEquals(REASON_WIFI, hit?.reason)
    }

    @Test
    fun `wifi match never fires off wifi`() {
        val c = cfg(homeProxy)
        assertNull(c.hitGroup(RuleProbe("HomeWiFi", false, emptySet())))
    }

    @Test
    fun `app match picks the group that owns the app`() {
        val c = cfg(science, nas)
        val hit = c.hitGroup(RuleProbe(null, false, setOf("com.synology.dsfile")))
        assertEquals("g2", hit?.rule?.id)
        assertEquals(listOf("com.synology.dsfile"), hit?.matched)
    }

    @Test
    fun `alive apps outside every group do not pick one`() {
        // 探针里可能带着别的组的残留目标，只要不在这个组里就不算命中
        val c = cfg(science)
        assertNull(c.hitGroup(RuleProbe(null, false, setOf("com.synology.dsfile"))))
    }

    @Test
    fun `disabled rule is skipped`() {
        val c = cfg(science.copy(enabled = false), nas)
        val hit = c.hitGroup(RuleProbe(null, false, setOf("com.android.chrome")))
        assertNull(hit)
    }

    @Test
    fun `rule without a client is skipped`() {
        // 半成品配置（只填了应用没选客户端）不能把别的组挤出匹配
        val c = cfg(science.copy(vpnPackage = ""), nas)
        val hit = c.hitGroup(RuleProbe(null, false, setOf("com.android.chrome", "com.synology.dsfile")))
        assertEquals("g2", hit?.rule?.id)
    }

    @Test
    fun `first rule wins when two groups claim the same app`() {
        val dup = science.copy(id = "g9", label = "重复")
        val c = cfg(science, dup)
        assertEquals("g1", c.hitGroup(RuleProbe(null, false, setOf("com.android.chrome")))?.rule?.id)
    }

    @Test
    fun `forClient swaps in the group targets`() {
        val c = cfg(science, nas)
        val resolved = c.forClient("com.tailscale.ipn")
        assertEquals("com.tailscale.ipn", resolved.vpnPackage)
        assertEquals(nas.targets, resolved.targets)
        // 组不带的字段仍然是全局的
        assertEquals(c.toastEnabled, resolved.toastEnabled)
        assertEquals(c.allowWifi, resolved.allowWifi)
    }

    @Test
    fun `forClient falls back to the default group`() {
        val c = cfg(science)
        val resolved = c.forClient("com.v2ray.ang")
        assertEquals("com.v2ray.ang", resolved.vpnPackage)
        assertEquals(cfg().targets, resolved.targets)
    }

    @Test
    fun `forClient prefers the rule id when two groups share a client`() {
        // 同一个客户端被两个组用：只按包名找会拿到第一组的应用清单，切过去就算错目标
        val second = VpnRule(
            id = "g4",
            label = "备用",
            vpnPackage = science.vpnPackage,
            targets = setOf("com.synology.dsfile"),
        )
        val c = cfg(science, second)
        assertEquals(
            setOf("com.synology.dsfile"),
            c.forClient(science.vpnPackage, ruleId = "g4").targets,
        )
        assertEquals(science.targets, c.forClient(science.vpnPackage).targets)
    }

    @Test
    fun `knownClients covers the default group and every rule`() {
        val c = cfg(science, nas, homeProxy)
        assertEquals(
            setOf("com.v2ray.ang", science.vpnPackage, nas.vpnPackage, homeProxy.vpnPackage),
            c.knownClients(),
        )
    }

    @Test
    fun `groupTag falls back to the last part of the package`() {
        val unnamed = science.copy(label = "")
        assertTrue(groupTag(unnamed).contains("clash"))
        assertEquals("组「科学上网」", groupTag(science))
    }

    @Test
    fun `a pending switch to a group survives only while that group still hits`() {
        val hit = GroupHit(science, REASON_TARGET, listOf("com.android.chrome"))
        assertTrue(switchStillWanted("g1", hit))
        assertFalse(switchStillWanted("g2", hit))
        assertFalse(switchStillWanted("g1", null))
    }

    @Test
    fun `a pending switch back to the default group needs nothing to hit`() {
        // 默认组不在 rules 里：没有组命中才算「回到默认组」
        assertTrue(switchStillWanted("", null))
        assertFalse(switchStillWanted("", GroupHit(nas, REASON_TARGET, listOf("com.synology.dsfile"))))
    }

    @Test
    fun `wifi hit wins over an app hit from another group`() {
        // 连上家里的网络时，哪怕正在用科学上网组的应用，也该走旁路由那一组
        val hit = cfg(science, homeProxy).hitGroup(
            RuleProbe(
                ssid = "HomeWiFi",
                isWifi = true,
                aliveTargets = setOf("com.android.chrome"),
                foreground = "com.android.chrome",
            ),
        )
        assertEquals("g3", hit?.rule?.id)
        assertEquals(REASON_WIFI, hit?.reason)
    }

    @Test
    fun `foreground beats a background group listed first`() {
        // 前一组的应用还在后台（进程没死），用户切到了后一组的应用：
        // 按列表顺序会选 g1，实际该跟着前台走，否则用户点了没反应
        val hit = cfg(science, nas).hitGroup(
            RuleProbe(
                ssid = null,
                isWifi = false,
                aliveTargets = setOf("com.android.chrome", "com.synology.dsfile"),
                foreground = "com.synology.dsfile",
            ),
        )
        assertEquals("g2", hit?.rule?.id)
        assertEquals(listOf("com.synology.dsfile"), hit?.matched)
    }

    @Test
    fun `without a foreground the earlier group keeps the hit`() {
        // 没有前台信息（刚解锁、回调没来）时退回列表顺序，行为与 1.10.0 一致
        val hit = cfg(science, nas).hitGroup(
            RuleProbe(
                ssid = null,
                isWifi = false,
                aliveTargets = setOf("com.android.chrome", "com.synology.dsfile"),
            ),
        )
        assertEquals("g1", hit?.rule?.id)
    }

    // ------------------------------------------------------------ 切换失败退避

    @Test
    fun `three attempts in a row park the switch for a while`() {
        val t0 = 1_000_000L
        var trouble = SwitchTrouble()
        // 冷却 10 秒，所以每次重来都落在 30 秒的重试窗口里
        trouble = trouble.noteAttempt("g2", t0)
        trouble = trouble.noteAttempt("g2", t0 + 11_000)
        assertFalse(trouble.blocked("g2", t0 + 11_001))
        trouble = trouble.noteAttempt("g2", t0 + 22_000)
        assertEquals(MAX_SWITCH_FAILS, trouble.fails)
        // 第三次发起时还允许（当次已经发了），失败后才开始压
        assertTrue(trouble.blocked("g2", t0 + 22_001))
        assertTrue(trouble.blocked("g2", t0 + 22_000 + SWITCH_BACKOFF_MS - 1))
        assertFalse(trouble.blocked("g2", t0 + 22_000 + SWITCH_BACKOFF_MS))
    }

    @Test
    fun `switching to another group is not blocked by the parked one`() {
        // 用户打开另一个组的应用：换目标就是新情况，不该被上一组的退避连坐
        val t0 = 1_000_000L
        var trouble = SwitchTrouble()
        repeat(MAX_SWITCH_FAILS) { i -> trouble = trouble.noteAttempt("g2", t0 + i * 11_000L) }
        assertTrue(trouble.blocked("g2", t0 + 30_000))
        assertFalse(trouble.blocked("g3", t0 + 30_000))
    }

    @Test
    fun `attempts spaced beyond the retry window start over`() {
        // 每次间隔都超过 30 秒：不是「连续失败」，只是不时用一下，不该攒到阈值
        val t0 = 1_000_000L
        var trouble = SwitchTrouble()
        repeat(MAX_SWITCH_FAILS + 2) { i ->
            trouble = trouble.noteAttempt("g1", t0 + i * (SWITCH_RETRY_WINDOW_MS + 1_000))
        }
        assertEquals(1, trouble.fails)
        assertFalse(trouble.blocked("g1", t0 + 600_000))
    }
}
