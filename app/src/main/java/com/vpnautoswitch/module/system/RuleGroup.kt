package com.vpnautoswitch.module.system

import com.vpnautoswitch.module.Config
import com.vpnautoswitch.module.VpnRule

// 多组规则：从「一个客户端 + 一堆应用」里挑出这一拍该用哪一组
//
// 纯函数，不碰 Context、不读配置，单测可以直接构造 Config 跑。

/** 判定这一刻的现场。SSID 为 null 表示不在 Wi-Fi 上，或者系统不给名字 */
internal data class RuleProbe(
    val ssid: String?,
    val isWifi: Boolean,
    val aliveTargets: Set<String>,
    /**
     * 当前前台的应用。
     *
     * 少了它就会「谁先建组谁赢」：B 组的应用退到后台但进程还活着，打开 A 组的应用，
     * 两个组都在 aliveTargets 里，按列表顺序可能选中 B。前台是用户当下真正在用的那个。
     */
    val foreground: String? = null,
)

/**
 * 命中的组。
 *
 * @param matched 应用命中时命中的那些包（按顺序），网络命中时为空。
 *   文案留给调用方拼：SSID 脱不脱敏是调用方才知道的。
 */
internal data class GroupHit(
    val rule: VpnRule,
    val reason: String,
    val matched: List<String> = emptyList(),
)

internal const val REASON_WIFI = "wifiOn"
internal const val REASON_TARGET = "target"

/**
 * 挑出这一拍该用哪个组。
 *
 * 两轮扫描，而不是「按顺序挨个组比对」：网络命中要压过应用命中。这个顺序和
 * [Engine.judge] 里「指定 Wi-Fi」排在目标应用之前是一致的——Wi-Fi 是用户自己
 * 配的环境约束，比「打开了哪个应用」更强。真要按列表顺序比，连上家里 Wi-Fi
 * 却还连着上一个组的客户端，就解释不通了。
 *
 * 组内顺序就是列表顺序（先建的优先），同一个应用被两个组同时选中时取前者；
 * 界面上的冲突提示负责提前把这个情况告诉用户。
 *
 * @return null 表示没有组命中，调用方应当走默认组（顶层 targets / wifiOn）
 */
internal fun Config.hitGroup(probe: RuleProbe): GroupHit? {
    val candidates = rules.filter { it.enabled && it.vpnPackage.isNotEmpty() }
    if (candidates.isEmpty()) return null

    if (probe.isWifi) {
        val ssid = probe.ssid
        if (!ssid.isNullOrEmpty()) {
            candidates.firstOrNull { ssid in it.wifiOn }?.let {
                return GroupHit(it, REASON_WIFI)
            }
        }
    }

    // 前台应用说了算。别的组退到后台、进程还活着时，不能因为它先建组就抢走这次命中
    val fg = probe.foreground
    if (!fg.isNullOrEmpty()) {
        candidates.firstOrNull { fg in it.targets }?.let {
            return GroupHit(it, REASON_TARGET, listOf(fg))
        }
    }

    if (probe.aliveTargets.isNotEmpty()) {
        candidates.firstOrNull { rule -> probe.aliveTargets.any { it in rule.targets } }?.let { rule ->
            val matched = probe.aliveTargets.filter { it in rule.targets }
            return GroupHit(rule, REASON_TARGET, matched)
        }
    }

    return null
}

/**
 * 某一组在界面和日志里的简称。
 *
 * 名字没起时退到包名，但包名太长，日志里一行放不下三个，所以只留最后两段：
 * `com.github.metacubex.clash.meta` → `clash.meta`。只留最后一段不行，
 * `...clash.meta` 和 `...tailscale.ipn` 会变成 `meta` / `ipn`，等于没名字。
 */
internal fun groupTag(rule: VpnRule): String {
    val name = rule.label.ifBlank { shortPackage(rule.vpnPackage) }
    return "组「${name.ifBlank { "未命名" }}」"
}

private fun shortPackage(pkg: String): String {
    val parts = pkg.split('.')
    return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else pkg
}

/**
 * 排队中的切换是否还该继续。
 *
 * 判断依据是「现在命中哪一组」，而不是临场再拼一套条件（前台在不在、目标还活着吗、
 * SSID 还是不是那条）。判定顺序只在 [hitGroup] 里维护，别处重写一遍迟早走岔。
 *
 * @param pendingRuleId 排队时记下的组 id；空串表示默认组
 */
internal fun switchStillWanted(pendingRuleId: String, hit: GroupHit?): Boolean =
    if (pendingRuleId.isEmpty()) hit == null else hit?.rule?.id == pendingRuleId

/**
 * 一个包属于哪个组；不属于任何组返回 null（= 默认组）。
 *
 * 只给界面用：编辑某一组的应用清单时，要先知道「这个客户端现在被谁用着」。
 */
internal fun Config.ruleOfPackage(packageName: String): VpnRule? =
    rules.firstOrNull { it.vpnPackage == packageName && it.enabled }
