package com.vpnautoswitch.module

import android.content.Context
import com.vpnautoswitch.module.apps.AppsRepository

/**
 * 卸载清理：把已经不存在的包从「目标应用 / 打开即关闭」里移除。
 *
 * 两个入口：
 *  - [PackageRemovedReceiver]：系统卸载完成广播，App 进程没运行也会被拉起；
 *  - [com.vpnautoswitch.module.ui.AppState]：每次打开 App 兜底，覆盖旧版本遗留的配置。
 *
 * 为什么必须真正删掉、而不是只在界面上过滤：
 * 配置里留着包名的话，用户重装同一个应用后会「默认继承」之前的开启状态。
 * 删掉之后重装回来就是未选中，符合「重装默认关闭」的预期。
 */
object ConfigCleaner {

    /** 只查询，不写盘。返回配置里那些已经装不出来的包。 */
    fun findMissing(context: Context, config: Config): Set<String> {
        val all = config.targets + config.offTargets
        if (all.isEmpty()) return emptySet()
        return all.filterNot { AppsRepository.isInstalled(context, it) }.toSet()
    }

    /** 全量清理，返回被移除的包名；没有变化时返回空集。 */
    fun pruneAll(context: Context): Set<String> {
        val config = Config.load(context)
        val gone = findMissing(context, config)
        if (gone.isEmpty()) return emptySet()
        Config.save(context, config.withoutPackages(gone))
        return gone
    }

    /**
     * 只处理卸载广播里的那一个包。
     *
     * 再次确认「确实已卸载」：卸载更新（uninstall updates）等情况也会走到这里，
     * 但包本身还在，不能从配置里删掉。
     */
    fun prunePackage(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val config = Config.load(context)
        if (packageName !in config.targets && packageName !in config.offTargets) return false
        if (AppsRepository.isInstalled(context, packageName)) return false
        Config.save(context, config.withoutPackages(setOf(packageName)))
        return true
    }
}

/** 摘掉指定包，其余配置保持不变。纯函数，便于单元测试。 */
internal fun Config.withoutPackages(gone: Set<String>): Config {
    if (gone.isEmpty()) return this
    return copy(
        targets = targets - gone,
        offTargets = offTargets - gone,
    )
}
