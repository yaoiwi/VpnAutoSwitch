package com.vpnautoswitch.module

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 应用被卸载后，把它从目标列表里清掉。
 *
 * 用 `PACKAGE_FULLY_REMOVED` 而不是 `PACKAGE_REMOVED`：
 * 后者在「覆盖安装 / 更新」时也会发，会把正在升级的应用误删；
 * 前者只在真正卸载完成时发一次。
 *
 * 这个 receiver 是静态注册的，即使模块 App 没在运行，系统也会拉起进程执行一次，
 * 所以「卸载后从没打开过 App 就重装」同样能做到重装默认关闭。
 */
class PackageRemovedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        if (intent?.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg.isBlank()) return
        try {
            ConfigCleaner.prunePackage(context.applicationContext, pkg)
        } catch (t: Throwable) {
            // 清理失败不能影响系统广播流程，下次打开 App 还有一次兜底
        }
    }
}
