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
}
