package com.vpnautoswitch.module.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
