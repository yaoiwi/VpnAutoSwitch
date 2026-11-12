package com.vpnautoswitch.module.system

/**
 * 把 Wi-Fi 名称遮一遮，用在日志和诊断报告里。
 *
 * SSID 常常带着姓名、房号、公司名（`302-张伟`、`ACME-Guest`），而排障的标准动作是
 * 把日志贴到 issue —— 一旦贴出去就收不回来。可日志又不能没有它：判断「Wi-Fi 规则
 * 为什么没命中」时，第一条要看的就是当前连的是哪个网。
 *
 * 所以遮成 `Ho***Fi(8)`：保留首尾各两个字，同一个名字任何时候遮出来都一样，
 * 照样能对比「切没切网」「和配置里是不是同一个」；长度单列，用来区分同前缀的网。
 *
 * 只影响展示。规则匹配用的是 [Engine.currentSsid] 原值，一个字都没动。
 */
internal object SsidMask {

    /** 开关在设置里（默认关）。关掉脱敏的用户是明确知道自己在干什么的。 */
    fun of(ssid: String?, full: Boolean = false): String {
        if (ssid.isNullOrEmpty()) return ""
        if (full) return ssid
        val len = ssid.length
        return when {
            // 两个字以内全遮：留一个首字母等于没遮
            len <= 2 -> "*".repeat(len)
            // 短名字只留首字，硬留尾字反而更容易猜
            len <= 5 -> ssid.take(1) + "***"
            else -> ssid.take(2) + "***" + ssid.takeLast(2) + "($len)"
        }
    }
}
