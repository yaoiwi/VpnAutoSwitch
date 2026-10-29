package com.vpnautoswitch.module

/**
 * 同一个包 / 同一个 Wi-Fi 同时命中「开」和「关」两组规则。
 *
 * 运行时的处理是「关」优先（见 EnginePolicy.foregroundKind），所以冲突不会让模块出错，
 * 只会让用户觉得「明明选了却没反应」。这里把它变成可见的提示，并在保存时顺手消解。
 */
data class RuleConflict(
    val apps: Set<String> = emptySet(),
    val wifi: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = apps.isEmpty() && wifi.isEmpty()
    val count: Int get() = apps.size + wifi.size
}

object RuleConflicts {

    fun find(config: Config): RuleConflict = RuleConflict(
        apps = config.targets intersect config.offTargets,
        wifi = config.wifiOn intersect config.wifiOff,
    )

    /**
     * 保存「目标应用」列表：新选中的应用从「打开时关闭」里摘掉，避免交叉。
     *
     * @return 消解后的配置，以及被摘掉的包名数量（用于提示）
     */
    fun keepOn(config: Config, targets: Set<String>): Pair<Config, Int> {
        val removed = targets intersect config.offTargets
        return config.copy(
            targets = LinkedHashSet(targets),
            offTargets = LinkedHashSet(config.offTargets - targets),
        ) to removed.size
    }

    /**
     * 保存「打开时关闭」列表。「关」的优先级更高，所以反向也要摘掉，
     * 否则用户会看到同一个应用在两边都有卡片、实际却不按「开」走。
     */
    fun keepOff(config: Config, offTargets: Set<String>): Pair<Config, Int> {
        val removed = offTargets intersect config.targets
        return config.copy(
            offTargets = LinkedHashSet(offTargets),
            targets = LinkedHashSet(config.targets - offTargets),
        ) to removed.size
    }

    /** Wi-Fi 名单同理：同一个 SSID 不能既算「始终开」又算「始终关」，关优先。 */
    fun keepWifiOn(config: Config, wifiOn: Set<String>): Pair<Config, Int> {
        val removed = wifiOn intersect config.wifiOff
        return config.copy(
            wifiOn = LinkedHashSet(wifiOn),
            wifiOff = LinkedHashSet(config.wifiOff - wifiOn),
        ) to removed.size
    }

    fun keepWifiOff(config: Config, wifiOff: Set<String>): Pair<Config, Int> {
        val removed = wifiOff intersect config.wifiOn
        return config.copy(
            wifiOff = LinkedHashSet(wifiOff),
            wifiOn = LinkedHashSet(config.wifiOn - wifiOff),
        ) to removed.size
    }

    /** 导入的旧配置可能带着交叉项，一次性按「关优先」清掉。 */
    fun normalize(config: Config): Pair<Config, RuleConflict> {
        val before = find(config)
        if (before.isEmpty) return config to before
        val cleaned = config.copy(
            targets = LinkedHashSet(config.targets - config.offTargets),
            wifiOn = LinkedHashSet(config.wifiOn - config.wifiOff),
        )
        return cleaned to before
    }
}
