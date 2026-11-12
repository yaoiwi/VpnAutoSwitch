package com.vpnautoswitch.module.ui

/**
 * 首次使用的四步。
 *
 * 这个模块的门槛不在功能，在「装完到第一次真正连上」这一段：要重启手机、要选客户端、
 * 要给个触发条件，而且第一次连接必须由用户自己在客户端里点一次完成系统授权。
 * 这四件事以前散在四处提示里——首页的状态卡、自检弹窗、客户端适配弹窗、日志，
 * 用户得自己拼起来；issue 里大部分「用不了」都卡在这条线上。
 *
 * 做成一张清单：做完的打勾，没做的写清楚下一步点哪里。全部完成后整张卡消失。
 */
internal object FirstRunGuide {

    const val ID_MODULE = "module"
    const val ID_CLIENT = "client"
    const val ID_TRIGGER = "trigger"
    const val ID_CONNECT = "connect"

    data class Step(
        val id: String,
        val title: String,
        val detail: String,
        val done: Boolean,
    )

    fun steps(
        moduleState: DiagnosticsPolicy.ModuleState,
        vpnPackage: String,
        targets: Int,
        wifiRules: Int,
        everConnected: Boolean,
    ): List<Step> {
        // OLD_CODE 也算「模块在跑」：那是升级后还没重启，下一步是重启而不是重装
        val moduleReady = moduleState == DiagnosticsPolicy.ModuleState.CURRENT ||
            moduleState == DiagnosticsPolicy.ModuleState.OLD_CODE
        val clientReady = vpnPackage.isNotEmpty()
        val triggerReady = targets > 0 || wifiRules > 0
        return listOf(
            Step(
                ID_MODULE,
                "启用模块",
                when (moduleState) {
                    DiagnosticsPolicy.ModuleState.NEVER ->
                        "在 LSPosed 里勾选本模块并启用，然后重启手机"
                    DiagnosticsPolicy.ModuleState.STALE ->
                        "模块以前加载过，现在没有心跳。重启手机让它重新注入"
                    DiagnosticsPolicy.ModuleState.OLD_CODE ->
                        "system_server 里还是旧版本，重启手机加载新的"
                    DiagnosticsPolicy.ModuleState.CURRENT ->
                        "system_server 已加载模块"
                },
                moduleReady,
            ),
            Step(
                ID_CLIENT,
                "选择 VPN 客户端",
                if (clientReady) vpnPackage else "选择平时用的 VPN 客户端，模块通过它建立隧道",
                clientReady,
            ),
            Step(
                ID_TRIGGER,
                "设置触发条件",
                if (triggerReady) {
                    "$targets 个目标应用 · $wifiRules 条 Wi-Fi 规则"
                } else {
                    "选几个目标应用（打开时连 VPN），或指定一个 Wi-Fi"
                },
                triggerReady,
            ),
            Step(
                ID_CONNECT,
                "成功连接一次",
                when {
                    everConnected -> "已经成功连接过，之后都由模块自动接管"
                    // 前一步没做完时，这一项还轮不到；直接说清楚，免得用户以为坏了
                    !triggerReady -> "设置好触发条件后，打开目标应用试一次"
                    else -> "打开目标应用；如果一直连不上，先在客户端里手动点一次连接，" +
                        "完成系统的 VPN 授权后再交给模块"
                },
                everConnected,
            ),
        )
    }

    fun remaining(steps: List<Step>): Int = steps.count { !it.done }

    /** 全部完成时首页不再显示这张卡 */
    fun isComplete(steps: List<Step>): Boolean = steps.all { it.done }
}
