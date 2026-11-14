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
}
