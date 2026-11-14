package com.vpnautoswitch.module.system

import com.vpnautoswitch.module.Config
import com.vpnautoswitch.module.VpnRule
import org.junit.Assert.assertEquals
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
}
