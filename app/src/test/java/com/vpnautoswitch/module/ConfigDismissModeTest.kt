package com.vpnautoswitch.module

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 划掉任务的三档口径。
 *
 * 1.9.11 及以前只有一个 anyDismiss 布尔，这次换成了 dismissMode，
 * 老配置读进来必须还落在原来那一档上，不能悄悄变回默认值。
 */
class ConfigDismissModeTest {

    @Test
    fun `new field wins over the old boolean`() {
        assertEquals(Config.DISMISS_ALL, Config.dismissModeOf("all", legacyAny = true))
        assertEquals(Config.DISMISS_CURRENT, Config.dismissModeOf("current", legacyAny = true))
        assertEquals(Config.DISMISS_ANY, Config.dismissModeOf("any", legacyAny = false))
    }

    @Test
    fun `old boolean is migrated instead of lost`() {
        assertEquals(Config.DISMISS_ANY, Config.dismissModeOf("", legacyAny = true))
        assertEquals(Config.DISMISS_CURRENT, Config.dismissModeOf("", legacyAny = false))
        // 键不存在时解析出来的也是空串，走同一条路
        assertEquals(Config.DISMISS_CURRENT, Config.dismissModeOf(null, legacyAny = false))
    }

    @Test
    fun `dirty value falls back to the old boolean`() {
        // 手改配置或跨版本混用留下的未知值，按老布尔决定，不要直接吞成默认
        assertEquals(Config.DISMISS_CURRENT, Config.dismissModeOf("wat", legacyAny = false))
        assertEquals(Config.DISMISS_ANY, Config.dismissModeOf("", legacyAny = true))
    }

    @Test
    fun `new installs still disconnect on any dismiss`() {
        // 默认值和 1.9.11 保持一致，升级上来的人不会因为换字段而改了行为
        assertEquals(Config.DISMISS_ANY, Config().dismissMode)
    }
}
