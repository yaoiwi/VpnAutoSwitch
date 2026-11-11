package com.vpnautoswitch.module.system

/**
 * 拉起失败之后，到底卡在哪一步。
 *
 * 现在失败只留一行「已连续 N 次没能建立 VPN」，用户看完仍然不知道该做什么：
 * 是客户端没授权、被系统挡了、还是它自己退了？这三种情况的处理办法完全不同，
 * 而日志里那行字一模一样。
 *
 * 判定依据全部来自模块自己观察到的现象，不去猜系统内部状态：
 *
 *   - `startForegroundService` 返回 null  → 系统明确拒绝了，包状态或后台限制
 *   - 两秒后进程不在了                    → 请求被接受，客户端自己退了（常见于没完成授权）
 *   - 进程还在、一直没隧道                → 客户端收到了请求但没有建连（没配节点／节点不通）
 *   - 这个客户端从来没成功连上过          → 上面几条的共同解释，也是最先该告诉用户的
 */
internal enum class FailureCause {
    /** 客户端本身没就绪：没装、禁用、没 VpnService、被冻结 */
    NOT_READY,

    /** 系统拒绝了启动请求 */
    REJECTED,

    /** 请求被接受，但客户端进程没留住 */
    PROCESS_GONE,

    /** 进程在，隧道一直不建 */
    NO_TUNNEL,

    /** 这个客户端在这台机器上从没成功连上过 */
    NEVER_CONNECTED,

    /** 看不出具体原因 */
    UNKNOWN,
}

/** 生成文案需要知道的东西，单独打包，避免函数签名堆一长串布尔 */
internal data class CauseContext(
    val label: String,
    val readiness: ClientReadiness,
    val attempts: Int,
    val clientMode: String,
    /** 本轮最后一次启动请求的观察结果：rejected / gone / alive / accepted / 空 */
    val outcome: String,
    /** 模块记录里这个客户端是否成功连上过 */
    val everConnected: Boolean,
)

internal object FailureCausePolicy {

    /**
     * 顺序就是「越靠前越能解释后面」：
     *
     * 客户端压根没就绪时，说「进程没留住」没意义；系统拒绝启动时，说「没配节点」也没意义。
     * 「从没连上过」排在两个具体现象之前——它既是解释，也是**唯一能一步解决的提示**
     * （手动连一次完成系统授权），issue #3 那句「必须自己手动打开连接一次才会判定」
     * 就是这个原因。
     */
    fun classify(readiness: ClientReadiness, outcome: String, everConnected: Boolean): FailureCause = when {
        readiness != ClientReadiness.READY && readiness != ClientReadiness.STOPPED ->
            FailureCause.NOT_READY
        outcome == ExternalControl.OUTCOME_REJECTED -> FailureCause.REJECTED
        // 首次使用必然「从没连上过」，这时先给授权提示，比说「进程没留住」有用
        !everConnected -> FailureCause.NEVER_CONNECTED
        outcome == ExternalControl.OUTCOME_GONE -> FailureCause.PROCESS_GONE
        outcome == ExternalControl.OUTCOME_ALIVE -> FailureCause.NO_TUNNEL
        else -> FailureCause.UNKNOWN
    }

    /** 一行归因 + 下一步，写进失败快照，自检和状态卡都用这一句 */
    fun describe(cause: FailureCause, ctx: CauseContext): String = when (cause) {
        FailureCause.NOT_READY ->
            "${ctx.label} 现在没法被拉起：" + ReadinessPolicy.advice(ctx.readiness)

        FailureCause.REJECTED ->
            "系统拒绝了启动 ${ctx.label} 的请求（startForegroundService 返回 null）。" +
                "这个包多半仍处在被强停的状态，或者被系统的后台限制拦住——" +
                "先在客户端里手动打开一次，再去应用信息里放开自启动和后台活动"

        FailureCause.NEVER_CONNECTED ->
            "模块没有 ${ctx.label} 成功连上过的记录。第一次使用必须由你手动在客户端里点一次连接，" +
                "让系统把 VPN 授权对话框弹出来并同意；在那之前，任何外部拉起都不会建立隧道"

        FailureCause.PROCESS_GONE ->
            "启动请求被接受了，但 ${ctx.label} 的进程两秒后就退出了。" +
                "常见原因是还没完成系统授权，或者客户端没有可用的配置——先在客户端里手动连一次"

        FailureCause.NO_TUNNEL ->
            "${ctx.label} 的进程一直在，但隧道没有建立。" +
                "这通常不是授权问题，而是客户端内部的连接失败：检查它自己的节点、订阅是否可用"

        FailureCause.UNKNOWN ->
            "连续 ${ctx.attempts} 次没能建立 VPN，" +
                "日志里看不出卡在启动、进程还是隧道哪一步。把完整日志发出来"
    }

    /** 这条归因要不要引导用户去「客户端适配」换启动方式 */
    fun suggestsAdapter(cause: FailureCause, clientMode: String): Boolean = when (cause) {
        // 进程在、隧道不建，且只固定了服务这一条路：换外部控制入口才可能通
        FailureCause.NO_TUNNEL -> clientMode == ClientRecipes.MODE_SERVICE
        else -> false
    }
}
