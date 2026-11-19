package com.vpnautoswitch.module.ui

import android.content.Context
import android.content.pm.PackageManager

/**
 * 找本机的 Xposed 管理器。
 *
 * LSPosed 在 2026 年改名成了 Vector（JingMatrix 维护，v2.0 起），模块 API 没变，
 * 但管理器的包名从 `org.lsposed.manager` 换成了 `org.matrix.vector.manager`。
 * 以前这里写死一个包名，Vector 用户点「打开 LSPosed」只会拿到「没安装」，
 * 看起来就像模块不支持这个框架（#26）。
 *
 * 只认这两个包名，不去遍历所有已安装应用：管理器改名的情况以后还会有，
 * 但每次改名都是从这两个里分出来的，值得单列的时候再加一行。
 */
internal object LspManager {

    /** 已知的管理器包名，按「官方 LSPosed → Vector」排序 */
    val KNOWN: List<String> = listOf(
        "org.lsposed.manager",
        "org.matrix.vector.manager",
    )

    /**
     * 从候选里挑一个：优先能直接启动的，其次只要装着就行。
     *
     * 「装着但查不到启动入口」是冻结、停用、装在分身里的情况，跳应用详情页
     * 还能让用户自己解开，比直接说「没有」有用（#15）。
     */
    fun pick(hasLauncher: Set<String>, installed: Set<String>): String? =
        KNOWN.firstOrNull { it in hasLauncher } ?: KNOWN.firstOrNull { it in installed }

    /** 本机实际可用的管理器包名；一个都没有返回 null */
    fun resolve(context: Context): String? {
        val pm = context.packageManager
        val launchable = KNOWN.filter { pm.getLaunchIntentForPackage(it) != null }.toSet()
        val installed = KNOWN.filter { isInstalled(pm, it) }.toSet()
        return pick(launchable, installed)
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (t: Throwable) {
        false
    }
}
