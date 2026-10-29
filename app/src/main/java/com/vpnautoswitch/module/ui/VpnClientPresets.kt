package com.vpnautoswitch.module.ui

/**
 * 常见 Android VPN 客户端。
 *
 * 这里存包名只用于「在已安装的应用里认出来」，不会替用户凭空选中没装的东西：
 * 包名万一过时，最坏结果是少显示一个快捷入口，而不是让用户选中一个错误的客户端。
 */
data class VpnClientPreset(
    val name: String,
    val packageName: String,
)

object VpnClientPresets {

    val ALL: List<VpnClientPreset> = listOf(
        VpnClientPreset("Clash Meta for Android", "com.github.metacubex.clash.meta"),
        VpnClientPreset("Clash for Android", "com.github.kr328.clash"),
        VpnClientPreset("v2rayNG", "com.v2ray.ang"),
        VpnClientPreset("NekoBox", "moe.nb4a"),
        VpnClientPreset("Hiddify", "app.hiddify.com"),
        VpnClientPreset("sing-box", "io.nekohasekai.sfa"),
        VpnClientPreset("Surfboard", "com.getsurfboard"),
        VpnClientPreset("Kitsunebi", "io.nekohasekai.kitsunebi"),
    )

    /** 已安装的预设，保持 [ALL] 里的顺序 */
    fun installed(packages: Collection<String>): List<VpnClientPreset> {
        val set = packages.toSet()
        return ALL.filter { it.packageName in set }
    }

    fun nameOf(packageName: String): String? =
        ALL.firstOrNull { it.packageName == packageName }?.name
}
