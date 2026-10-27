package com.vpnautoswitch.module.ui

/** 纯诊断判断，和 UI 分离，便于固定边界行为。 */
object DiagnosticsPolicy {

    const val HEARTBEAT_FRESH_MS = 60_000L

    enum class ModuleState {
        /** 心跳新鲜且版本一致 */
        CURRENT,

        /** 心跳新鲜，但 system_server 仍是旧代码 */
        OLD_CODE,

        /** 以前注入过，但当前没有新鲜心跳 */
        STALE,

        /** 从未收到心跳 */
        NEVER;
    }

    fun isHeartbeatFresh(heartbeat: Long, now: Long): Boolean =
        heartbeat > 0L && now >= heartbeat && now - heartbeat <= HEARTBEAT_FRESH_MS

    fun heartbeatAge(heartbeat: Long, now: Long): Long =
        if (heartbeat <= 0L || now < heartbeat) 0L else now - heartbeat

    fun moduleState(
        heartbeat: Long,
        moduleVersion: Int,
        appVersion: Int,
        now: Long,
    ): ModuleState = when {
        !isHeartbeatFresh(heartbeat, now) ->
            if (heartbeat > 0L) ModuleState.STALE else ModuleState.NEVER
        moduleVersion != appVersion -> ModuleState.OLD_CODE
        else -> ModuleState.CURRENT
    }

    /** 当前规则对 VPN 的预期状态；UNKNOWN 表示规则不强制开或关。 */
    enum class ExpectedVpn { ON, OFF, UNKNOWN }

    fun expectedVpn(activeRule: String): ExpectedVpn = when (activeRule) {
        "target", "wifiOn" -> ExpectedVpn.ON
        "offTarget", "wifiOff", "networkDisabled", "userOff" -> ExpectedVpn.OFF
        else -> ExpectedVpn.UNKNOWN
    }

    /**
     * 规则与实际隧道状态是否不一致。
     *
     * 返回 null 表示规则不强制开/关，无法判断；true 表示不一致。
     * 这里不能把「连接中」当成失败，UI 只按提醒处理。
     */
    fun vpnMismatch(activeRule: String, vpnConnected: Boolean): Boolean? =
        when (expectedVpn(activeRule)) {
            ExpectedVpn.ON -> !vpnConnected
            ExpectedVpn.OFF -> vpnConnected
            ExpectedVpn.UNKNOWN -> null
        }

    /** 规则 key 转成一句人能读懂的话 */
    fun ruleLabel(rule: String): String = when (rule) {
        "target" -> "目标应用 → 应连接"
        "wifiOn" -> "Wi-Fi 规则 → 应连接"
        "offTarget" -> "打开即关闭 → 应断开"
        "wifiOff" -> "Wi-Fi 规则 → 应断开"
        "networkDisabled" -> "当前网络已停用自动开关"
        "userOff" -> "手动断开 → 等待重新打开"
        "paused" -> "已暂停 → 不干预"
        "disabled" -> "未启用或未选客户端"
        "idle" -> "没有触发条件"
        "" -> "尚未收到规则"
        else -> rule
    }

    /** 日志最后一条非空行，已经自带 HH:mm:ss 前缀 */
    fun lastEvent(log: String): String =
        log.lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()

    enum class Tone { NORMAL, WARN, MUTED }

    /** 首页状态卡要显示的四行因果链 */
    data class StatusSummary(
        val headline: String,
        val subtitle: String,
        val foreground: String,
        val rule: String,
        val actual: String,
        val actualTone: Tone,
        val lastAction: String,
    )

    fun summarize(
        heartbeat: Long,
        moduleVersion: Int,
        appVersion: Int,
        paused: Boolean,
        activeRule: String,
        ruleDetail: String,
        foreground: String,
        vpnConnected: Boolean,
        network: String,
        log: String,
        now: Long,
    ): StatusSummary {
        val state = moduleState(heartbeat, moduleVersion, appVersion, now)
        val fresh = state == ModuleState.CURRENT || state == ModuleState.OLD_CODE
        val age = heartbeatAge(heartbeat, now)

        val headline = when {
            !fresh && heartbeat == 0L -> "等待注入"
            !fresh -> "模块未运行"
            state == ModuleState.OLD_CODE -> "运行中（旧代码）"
            paused -> "已暂停"
            else -> "运行中"
        }

        val subtitle = when {
            !fresh && heartbeat == 0L ->
                "超过 60 秒没有收到系统框架心跳，去 LSPosed 里检查作用域并重启"
            !fresh -> "心跳已中断，最后一次是 ${formatAge(age)}前"
            state == ModuleState.OLD_CODE ->
                "App 是 v$appVersion，系统框架仍是 v$moduleVersion，重启手机后生效"
            paused -> "快捷磁贴暂停了自动联动，点「恢复联动」或磁贴恢复"
            else -> "心跳 ${formatAge(age)}前 · 当前网络 $network"
        }

        val hasForeground = foreground.isNotBlank()
        val wantsVpn = expectedVpn(activeRule) == ExpectedVpn.ON
        val mismatch = vpnMismatch(activeRule, vpnConnected) == true

        return StatusSummary(
            headline = headline,
            subtitle = subtitle,
            foreground = when {
                // 模块没在跑时，残留的前台包名不可信
                !fresh -> "未知"
                hasForeground -> foreground
                else -> "无（当前没有目标应用在前台）"
            },
            rule = when {
                !fresh -> "未知"
                ruleDetail.isNotBlank() -> ruleLabel(activeRule) + " · " + ruleDetail
                else -> ruleLabel(activeRule)
            },
            actual = when {
                !fresh -> "未知"
                vpnConnected -> "已连接"
                else -> "未连接"
            },
            actualTone = when {
                !fresh -> Tone.MUTED
                mismatch -> Tone.WARN
                vpnConnected && wantsVpn -> Tone.NORMAL
                else -> Tone.MUTED
            },
            lastAction = lastEvent(log).ifBlank { "暂无记录" },
        )
    }

    private fun formatAge(ms: Long): String = when {
        ms < 1_000L -> "刚刚"
        ms < 60_000L -> "${ms / 1_000L} 秒"
        ms < 3_600_000L -> "${ms / 60_000L} 分钟"
        else -> "${ms / 3_600_000L} 小时"
    }
}
