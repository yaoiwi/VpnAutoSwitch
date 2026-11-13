package com.vpnautoswitch.module.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientRecipeTest {

    @Test
    fun `clash family prefers the exported control entry`() {
        // 这两个客户端导出了 action.START_CLASH，发它等价于用户点连接
        assertEquals(
            ClientRecipes.MODE_ACTIVITY,
            ClientRecipes.of("com.github.metacubex.clash.meta")?.preferredMode,
        )
        assertEquals(
            ClientRecipes.MODE_ACTIVITY,
            ClientRecipes.of("com.github.kr328.clash")?.preferredMode,
        )
    }

    @Test
    fun `clients without a control entry go straight to the service`() {
        // v2rayNG 从来没有 START_CLASH，先发一次只是多一条误导性的日志
        assertEquals(
            ClientRecipes.MODE_SERVICE,
            ClientRecipes.of("com.v2ray.ang")?.preferredMode,
        )
        assertEquals(
            ClientRecipes.MODE_SERVICE,
            ClientRecipes.of("moe.nb4a")?.preferredMode,
        )
    }

    @Test
    fun `unknown packages have no recipe and keep their own name`() {
        assertNull(ClientRecipes.of("com.example.unknown"))
        assertNull(ClientRecipes.of(""))
        // 认不出来时用包名本身，别在日志里留空
        assertEquals("com.example.unknown", ClientRecipes.labelOf("com.example.unknown"))
        assertEquals("v2rayNG", ClientRecipes.labelOf("com.v2ray.ang"))
    }

    @Test
    fun `every recipe declares a usable mode`() {
        // 写错成 "ui" 或空串会让引擎进到一个没有分支的 mode
        ClientRecipes.ALL.forEach {
            val mode = it.preferredMode
            assertEquals(
                "${it.packageName} 的方式写错了：$mode",
                true,
                mode == ClientRecipes.MODE_SERVICE || mode == ClientRecipes.MODE_ACTIVITY,
            )
        }
    }

    @Test
    fun `flclash uses action START without the clash suffix`() {
        // FlClash 的 QuickActionActivity 导出的是 com.follow.clash.action.START，
        // 少一个 _CLASH，按老后缀发永远 resolve 不到（#19）
        val recipe = ClientRecipes.of("com.follow.clash")
        assertNotNull(recipe)
        assertEquals(ClientRecipes.MODE_ACTIVITY, recipe!!.preferredMode)
        assertEquals(listOf("action.START"), recipe.startSuffixes)
        assertEquals(listOf("action.STOP"), recipe.stopSuffixes)
        assertEquals("com.follow.clash.action.START", "com.follow.clash.${recipe.startSuffixes.first()}")
    }

    @Test
    fun `repackaged builds fall back to the same recipe`() {
        // 改包版包名多一段，后缀跟着包名拼，行为一致
        val recipe = ClientRecipes.of("com.follow.clash.foss")
        assertNotNull(recipe)
        assertEquals("com.follow.clash.foss", recipe!!.packageName)
        assertEquals(listOf("action.START"), recipe.startSuffixes)
        // 界面和日志里仍叫它 FlClash，不必因为包名多一段就露出原始包名
        assertEquals("FlClash", ClientRecipes.labelOf("com.follow.clash.foss"))
    }

    @Test
    fun `clash family keeps the classic suffix order`() {
        // 别让 FlClash 的改动顺手把克劳德家的入口改掉
        assertEquals(
            listOf("action.START_CLASH"),
            ClientRecipes.of("com.github.metacubex.clash.meta")?.startSuffixes,
        )
        assertEquals(
            listOf("action.STOP_CLASH"),
            ClientRecipes.of("com.github.metacubex.clash.meta")?.stopSuffixes,
        )
        assertEquals(DEFAULT_START_SUFFIXES, ClientRecipes.of("com.v2ray.ang")?.startSuffixes)
    }

    @Test
    fun `every recipe has at least one entry for both directions`() {
        ClientRecipes.ALL.forEach {
            assertTrue("${it.packageName} 没有启动入口", it.startSuffixes.isNotEmpty())
            assertTrue("${it.packageName} 没有停止入口", it.stopSuffixes.isNotEmpty())
            // 后缀自己不以点开头，否则拼出来是 com.x..action.START
            assertTrue(
                "${it.packageName} 的后缀多了个点",
                it.startSuffixes.none { s -> s.startsWith(".") },
            )
        }
    }
}
