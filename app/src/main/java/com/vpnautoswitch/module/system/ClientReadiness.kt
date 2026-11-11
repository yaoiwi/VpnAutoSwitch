package com.vpnautoswitch.module.system

/**
 * 启动前的客户端就绪状态。
 *
 * 「拉不起 VPN」在日志里长得都差不多：一串 restart VPN service，然后没有隧道。
 * 但背后至少是四件事，处理方式完全不同：
 *
 *   - 包被卸载/禁用 → 用户得先装回来
 *   - 没声明 VpnService → 这个客户端根本不可能被外部拉起，只能换一个
 *   - 包处于 stopped（被强停过）→ 模块自己就能清，不该让用户去点图标
 *   - 包被冻结 → 模块解不开，得用户去系统里解冻
 *
 * 以前这些只在失败之后从 `Packages.stateLabel` 的一行字里体现，而且只在日志深处。
 * 现在先判一次，把结论直接写进诊断和状态卡。
 */
internal enum class ClientReadiness {
    /** 可以动手 */
    READY,

    /** 没装或查不到 */
    NOT_INSTALLED,

    /** 被系统禁用 */
    DISABLED,

    /** 没有声明 BIND_VPN_SERVICE 的服务，外部拉不起来 */
    NO_VPN_SERVICE,

    /** 被强停过，处于 stopped 状态；模块启动前会自动清掉，但值得说出来 */
    STOPPED,

    /** 被系统冻结（工作资料、应用冻结类工具），模块解不开 */
    SUSPENDED,

    /** 查不到信息，无法判断 */
    UNKNOWN,
}

/** 判断就绪状态需要的事实，全部来自 PackageManager，查询不需要权限 */
internal data class ClientFacts(
    val installed: Boolean?,
    val enabled: Boolean?,
    val hasVpnService: Boolean?,
    val stopped: Boolean = false,
    val suspended: Boolean = false,
)

internal object ReadinessPolicy {

    /**
     * 顺序不能换：先排除「根本没有这个东西」，再说「有这个但被挡住」。
     *
     * stopped 排在 suspended 前面，因为 stopped 是模块能自己处理的，
     * 提示语不一样——把能自动修的当成要用户动手的，会白折腾一轮设置。
     */
    fun assess(facts: ClientFacts): ClientReadiness = when {
        facts.installed == null -> ClientReadiness.UNKNOWN
        !facts.installed -> ClientReadiness.NOT_INSTALLED
        facts.enabled == false -> ClientReadiness.DISABLED
        facts.hasVpnService == false -> ClientReadiness.NO_VPN_SERVICE
        facts.stopped -> ClientReadiness.STOPPED
        facts.suspended -> ClientReadiness.SUSPENDED
        else -> ClientReadiness.READY
    }

    /** 这条状态要不要让用户动手 */
    fun needsUser(readiness: ClientReadiness): Boolean = when (readiness) {
        ClientReadiness.READY, ClientReadiness.STOPPED, ClientReadiness.UNKNOWN -> false
        else -> true
    }

    /** 状态卡 / 诊断里的一句话；空字符串表示不用解释 */
    fun advice(readiness: ClientReadiness): String = when (readiness) {
        ClientReadiness.READY -> ""
        ClientReadiness.NOT_INSTALLED -> "客户端没装或查不到，先在系统里装回来"
        ClientReadiness.DISABLED -> "客户端被系统禁用了，到应用信息里启用"
        ClientReadiness.NO_VPN_SERVICE -> "这个客户端没有声明 VpnService，模块拉不起来，换一个客户端"
        // 模块每次启动前都会 unstop 一次，所以这里只是解释「为什么刚才可能失败过」
        ClientReadiness.STOPPED -> "客户端被强停过（stopped 状态），模块会在启动前自动清掉这个标记"
        ClientReadiness.SUSPENDED -> "客户端被系统冻结了，模块解不开，先在系统里解冻"
        ClientReadiness.UNKNOWN -> "查不到客户端信息，可能正在安装或升级"
    }

    /** 诊断里的一行短标签 */
    fun label(readiness: ClientReadiness): String = when (readiness) {
        ClientReadiness.READY -> "正常"
        ClientReadiness.NOT_INSTALLED -> "未安装"
        ClientReadiness.DISABLED -> "已禁用"
        ClientReadiness.NO_VPN_SERVICE -> "无 VpnService"
        ClientReadiness.STOPPED -> "被强停过"
        ClientReadiness.SUSPENDED -> "被冻结"
        ClientReadiness.UNKNOWN -> "未知"
    }
}
