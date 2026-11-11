package com.vpnautoswitch.module.system

/**
 * 已知 VPN 客户端的启动配方。
 *
 * 在这之前，「先试外部控制入口还是先启 VpnService」只能靠运行时猜：
 * `startMode` 一律先 `resolveActivity(START_CLASH)`，客户端没有这个入口才退回服务，
 * 日志里就会留下一条「没有 action.START_CLASH 入口，改用备用方式」。
 * 对 v2rayNG 这种**从来就没有**该入口的客户端，这句话每次冷启动都会出现一遍，
 * 既多一次跨进程查询，也让排障的人以为「本来是能用的，只是这次没用上」。
 *
 * 配方只解决**第一次**的顺序问题：客户端换版本后入口可能变，所以不是硬绑定。
 * 真正确认下来的是 [ExternalControl.pin] 和自动记忆，配方只是让第一次不要走错。
 *
 * 包名在这里只用于识别，认不出来就按原来的顺序试，最坏结果和没有这张表一样。
 */
internal data class ClientRecipe(
    val packageName: String,
    val label: String,
    /** 首选方式：activity（有外部控制入口）/ service（直接启动 VpnService） */
    val preferredMode: String,
    /** 一句人话，说明为什么这样排序，诊断里会原样显示 */
    val note: String,
)

internal object ClientRecipes {

    const val MODE_ACTIVITY = "activity"
    const val MODE_SERVICE = "service"

    /**
     * 只有**确认导出过** `action.START_CLASH` 的客户端才标 activity。
     *
     * 拿不准的一律 service：那是现在失败后本来就会走到的第二步，
     * 标错成 activity 反而会多发一次没用的广播。
     */
    val ALL: List<ClientRecipe> = listOf(
        ClientRecipe(
            "com.github.metacubex.clash.meta",
            "Clash Meta for Android",
            MODE_ACTIVITY,
            "导出了 action.START_CLASH，发这个入口等价于用户点连接，比启服务可靠",
        ),
        ClientRecipe(
            "com.github.kr328.clash",
            "Clash for Android",
            MODE_ACTIVITY,
            "导出了 action.START_CLASH，发这个入口等价于用户点连接",
        ),
        ClientRecipe(
            "com.v2ray.ang",
            "v2rayNG",
            MODE_SERVICE,
            "没有外部控制入口，只能启动它的 VpnService",
        ),
        ClientRecipe(
            "moe.nb4a",
            "NekoBox",
            MODE_SERVICE,
            "没有可用的外部控制入口，按 VpnService 启动",
        ),
        ClientRecipe(
            "app.hiddify.com",
            "Hiddify",
            MODE_SERVICE,
            "没有可用的外部控制入口，按 VpnService 启动",
        ),
        ClientRecipe(
            "io.nekohasekai.sfa",
            "sing-box",
            MODE_SERVICE,
            "没有可用的外部控制入口，按 VpnService 启动",
        ),
        ClientRecipe(
            "com.getsurfboard",
            "Surfboard",
            MODE_SERVICE,
            "没有可用的外部控制入口，按 VpnService 启动",
        ),
        ClientRecipe(
            "io.nekohasekai.kitsunebi",
            "Kitsunebi",
            MODE_SERVICE,
            "没有可用的外部控制入口，按 VpnService 启动",
        ),
    )

    fun of(pkg: String): ClientRecipe? =
        if (pkg.isEmpty()) null else ALL.firstOrNull { it.packageName == pkg }

    /** 认不出来时返回包名本身，别在日志里留一个空名字 */
    fun labelOf(pkg: String): String = of(pkg)?.label ?: pkg
}
