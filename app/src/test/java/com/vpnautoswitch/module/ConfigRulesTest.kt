package com.vpnautoswitch.module

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRulesTest {

    private val rule = VpnRule(
        id = "g1",
        label = "科学上网",
        vpnPackage = "com.github.metacubex.clash.meta",
        targets = setOf("com.android.chrome"),
        wifiOn = setOf("HomeWiFi"),
    )

    @Test
    fun `rules survive a json round trip`() {
        val cfg = Config(vpnPackage = "com.v2ray.ang", rules = listOf(rule))
        val back = Config.parse(cfg.toJson())
        assertEquals(1, back?.rules?.size)
        assertEquals(rule, back?.rules?.first())
    }

    @Test
    fun `no rules means no key at all`() {
        // 老用户升级上来配置里不该多出一个空数组，旧版模块也没必要看见这个键
        val o = JSONObject(Config().toJson())
        assertFalse(o.has("rules"))
    }

    @Test
    fun `old config without rules still parses`() {
        val old = Config(vpnPackage = "com.v2ray.ang", targets = setOf("com.android.chrome"))
        val o = JSONObject(old.toJson())
        o.remove("rules")
        val back = Config.parse(o.toString())
        assertTrue(back?.rules.isNullOrEmpty())
        assertEquals(setOf("com.android.chrome"), back?.targets)
    }

    @Test
    fun `half finished rules are dropped`() {
        // 没 id 没法被切换选中，没客户端拉不起来；留着只会让序号和界面对不上
        val raw = """
            {"rules":[
              {"id":"","vpn":"com.a","targets":["x"]},
              {"id":"g2","vpn":"","targets":["y"]},
              {"id":"g3","vpn":"com.c","on":false,"targets":["z"]}
            ]}
        """.trimIndent()
        val cfg = Config.parse(raw)
        assertEquals(1, cfg?.rules?.size)
        assertEquals("g3", cfg?.rules?.first()?.id)
        // 关掉的组仍然读进来：用户只是暂时停用，不是删了
        assertEquals(false, cfg?.rules?.first()?.enabled)
    }

    @Test
    fun `broken json still returns null`() {
        assertNull(Config.parse("{"))
    }

    @Test
    fun `defaultRule mirrors the top level config`() {
        val cfg = Config(
            vpnPackage = "com.v2ray.ang",
            targets = setOf("org.mozilla.firefox"),
            wifiOn = setOf("HomeWiFi"),
        )
        val d = cfg.defaultRule()
        assertEquals("", d.id)
        assertEquals(cfg.vpnPackage, d.vpnPackage)
        assertEquals(cfg.targets, d.targets)
        assertEquals(cfg.wifiOn, d.wifiOn)
    }

    @Test
    fun `forClient with the default group id keeps the top level targets`() {
        // 默认组和规则组共用同一个客户端时，空串必须只换包名。
        // 传 null 会落到「按包名猜组」的分支，把规则组的应用清单拿过来
        val cfg = Config(
            vpnPackage = "com.github.metacubex.clash.meta",
            targets = setOf("org.mozilla.firefox"),
            rules = listOf(rule),
        )
        val d = cfg.forClient("com.github.metacubex.clash.meta", "")
        assertEquals(setOf("org.mozilla.firefox"), d.targets)
        assertEquals(emptySet<String>(), d.wifiOn)
    }

    @Test
    fun `forClient with a null id guesses by package`() {
        // 这是 null 的既有语义，调用点别拿它表示「默认组」
        val cfg = Config(
            vpnPackage = "com.v2ray.ang",
            targets = setOf("org.mozilla.firefox"),
            rules = listOf(rule),
        )
        val guess = cfg.forClient(rule.vpnPackage, null)
        assertEquals(rule.targets, guess.targets)
    }

    @Test
    fun `forClient by rule id uses that group's list`() {
        val cfg = Config(vpnPackage = "com.v2ray.ang", rules = listOf(rule))
        val g = cfg.forClient(rule.vpnPackage, rule.id)
        assertEquals(rule.targets, g.targets)
        assertEquals(rule.wifiOn, g.wifiOn)
    }
}
