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
}
