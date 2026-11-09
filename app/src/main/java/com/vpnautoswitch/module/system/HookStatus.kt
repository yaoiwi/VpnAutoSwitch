package com.vpnautoswitch.module.system

/**
 * system_server 加载时各类 hook 实际挂上的方法数。
 *
 * 以前这些计数只拼成一行日志，某类为 0 时模块会静默失去对应能力：LSPosed 里显示
 * 「已激活」、自检全绿，功能却就是不工作（issue #16 里划掉任务那条链就是这么丢的）。
 * 现在把计数做成快照交给 App，自检按重要性分级提示。
 *
 * 这个文件和 [hookReport] 是纯 Kotlin，不碰 Android API，方便本地单测。
 */
data class HookCounts(
    val startActivity: Int = 0,
    val activityResumed: Int = 0,
    val setState: Int = 0,
    val death: Int = 0,
    val taskRemoved: Int = 0,
    val notif: Int = 0,
    val blockClientStart: Int = 0,
) {
    /** 前台检测的三条路互相兜底，只要有一条挂上就能知道应用切换 */
    val foreground: Int get() = startActivity + activityResumed + setState

    /** 写进 Settings.Secure 的字段名，和日志里 `hooks:` 那一行完全对应 */
    fun asMap(): Map<String, Int> = mapOf(
        "startActivity" to startActivity,
        "activityResumed" to activityResumed,
        "setState" to setState,
        "death" to death,
        "taskRemoved" to taskRemoved,
        "notif" to notif,
        "blockClientStart" to blockClientStart,
    )
}

/** 字段名只在这一处定义，App 侧按同一份 key 反解，避免两边写岔 */
fun hookCountsFromMap(map: Map<String, Int>): HookCounts = HookCounts(
    startActivity = map["startActivity"] ?: 0,
    activityResumed = map["activityResumed"] ?: 0,
    setState = map["setState"] ?: 0,
    death = map["death"] ?: 0,
    taskRemoved = map["taskRemoved"] ?: 0,
    notif = map["notif"] ?: 0,
    blockClientStart = map["blockClientStart"] ?: 0,
)

enum class HookHealth { OK, DEGRADED, BROKEN, UNKNOWN }

data class HookReport(
    val health: HookHealth,
    /** 缺了模块基本不工作 */
    val missingCritical: List<String> = emptyList(),
    /** 缺了功能降级（例如划掉任务后不会立刻断开，得等进程死） */
    val degraded: List<String> = emptyList(),
    /** 缺了只是少点体验优化，不影响自动开关 */
    val cosmetic: List<String> = emptyList(),
)

/**
 * 按重要性给 hook 完整性定级。
 *
 * 前台检测三条只要有一条生效即可（不同 Android 版本 / ROM 上本来就会挂不同的入口，
 * 三条全零才算坏）；进程死亡是「目标退出 → 关 VPN」唯一的可靠来源，必须挂上。
 * 划掉任务缺失会退化到看门狗兜底，标黄；通知隐藏和自启闸门纯属锦上添花，只记录不报警。
 */
fun hookReport(counts: HookCounts?): HookReport {
    if (counts == null) return HookReport(HookHealth.UNKNOWN)

    val critical = mutableListOf<String>()
    if (counts.foreground <= 0) critical += "前台应用检测"
    if (counts.death <= 0) critical += "进程死亡回调"

    val degraded = mutableListOf<String>()
    if (counts.taskRemoved <= 0) degraded += "划掉最近任务"

    val cosmetic = mutableListOf<String>()
    if (counts.notif <= 0) cosmetic += "系统 VPN 通知隐藏"
    if (counts.blockClientStart <= 0) cosmetic += "客户端自启闸门"

    return when {
        critical.isNotEmpty() -> HookReport(HookHealth.BROKEN, critical, degraded, cosmetic)
        degraded.isNotEmpty() -> HookReport(HookHealth.DEGRADED, emptyList(), degraded, cosmetic)
        else -> HookReport(HookHealth.OK, emptyList(), emptyList(), cosmetic)
    }
}
