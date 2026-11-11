package com.vpnautoswitch.module.ui

import com.vpnautoswitch.module.system.ClientRecipes

/**
 * 常见 Android VPN 客户端。
 *
 * 这里存包名只用于「在已安装的应用里认出来」，不会替用户凭空选中没装的东西：
 * 包名万一过时，最坏结果是少显示一个快捷入口，而不是让用户选中一个错误的客户端。
 *
 * 名单本身来自 [ClientRecipes]：system_server 那边要靠同一份表决定「先试服务还是
 * 先发外部控制广播」。分成两份维护的话，只改一边就会出现「界面认识它、引擎不认识」。
 */
data class VpnClientPreset(
    val name: String,
    val packageName: String,
)

object VpnClientPresets {

    val ALL: List<VpnClientPreset> = ClientRecipes.ALL.map {
        VpnClientPreset(name = it.label, packageName = it.packageName)
    }

    /** 已安装的预设，保持 [ALL] 里的顺序 */
    fun installed(packages: Collection<String>): List<VpnClientPreset> {
        val set = packages.toSet()
        return ALL.filter { it.packageName in set }
    }

    fun nameOf(packageName: String): String? =
        ALL.firstOrNull { it.packageName == packageName }?.name
}
