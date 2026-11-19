package com.vpnautoswitch.module.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LspManagerTest {

    @Test
    fun `official lsposed wins when both are installed`() {
        // 两个都装着（换过框架、旧管理器没删）：优先官方那个，它的入口最稳
        assertEquals(
            "org.lsposed.manager",
            LspManager.pick(
                hasLauncher = setOf("org.lsposed.manager", "org.matrix.vector.manager"),
                installed = setOf("org.lsposed.manager", "org.matrix.vector.manager"),
            ),
        )
    }

    @Test
    fun `vector is used when it is the only manager`() {
        // 只有 Vector：v2.0 起 LSPosed 改名，管理器包名换成 org.matrix.vector.manager。
        // 以前这里写死官方包名，用户会看到「没安装」，看起来像模块不支持这个框架（#26）
        assertEquals(
            "org.matrix.vector.manager",
            LspManager.pick(
                hasLauncher = setOf("org.matrix.vector.manager"),
                installed = setOf("org.matrix.vector.manager"),
            ),
        )
    }

    @Test
    fun `frozen manager falls back to the installed one`() {
        // 冻结 / 停用时查不到启动入口，但包还在，跳应用详情页还能让用户解开
        assertEquals(
            "org.lsposed.manager",
            LspManager.pick(hasLauncher = emptySet(), installed = setOf("org.lsposed.manager")),
        )
    }

    @Test
    fun `nothing installed means no target`() {
        assertNull(LspManager.pick(hasLauncher = emptySet(), installed = emptySet()))
    }
}
