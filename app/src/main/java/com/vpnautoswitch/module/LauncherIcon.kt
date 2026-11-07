package com.vpnautoswitch.module

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面图标显隐。
 *
 * 桌面入口由 manifest 里的 activity-alias `LauncherEntry` 承载，隐藏图标只是把该
 * alias 置为 disabled：MainActivity 本身仍带 `MODULE_SETTINGS` 入口，依然能从
 * LSPosed 管理器打开本页把图标恢复回来，不会把自己锁在外面。
 *
 * 状态以系统的组件状态为唯一真相源（不落 SharedPreferences / 配置文件）：组件启用
 * 状态由 PackageManagerService 持久化，清 App 数据也不会丢，用 adb 改过之后回到
 * 本页显示的也是桌面实际表现。
 */
object LauncherIcon {

    /**
     * alias 的完整类名。
     *
     * 本项目的 namespace（com.vpnautoswitch.module）与 applicationId
     * （io.github.yaoiwi.vpnautoswitch）不同，ComponentName 的包名要用后者、类名要用前者，
     * 正好对应 manifest 里写的完整别名。
     */
    private const val ALIAS_CLASS = "com.vpnautoswitch.module.LauncherEntry"

    private fun alias(context: Context) = ComponentName(context.packageName, ALIAS_CLASS)

    /** `COMPONENT_ENABLED_STATE_DEFAULT` 表示没被改过、按 manifest 走（即显示），不算隐藏。 */
    fun isHidden(context: Context): Boolean = runCatching {
        when (context.packageManager.getComponentEnabledSetting(alias(context))) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            -> true

            else -> false
        }
    }.getOrDefault(false)

    /**
     * 切换桌面图标显隐。
     *
     * `DONT_KILL_APP` 是必需的：改组件状态默认会连带杀掉本进程，设置页会当场退出。
     *
     * 恢复用 `COMPONENT_ENABLED_STATE_DEFAULT` 而不是 `ENABLED`：DEFAULT 是「回到 manifest
     * 的声明」，以后哪天把 alias 改成默认禁用也不会被这里强行覆盖。
     *
     * @return true 表示系统没有抛异常。注意这并不代表状态真的变了——对不存在的组件
     *   调用是静默成功的，所以调用方仍要以 [isHidden] 的回读结果为准。
     */
    fun setHidden(context: Context, hidden: Boolean): Boolean = runCatching {
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            },
            PackageManager.DONT_KILL_APP,
        )
    }.isSuccess
}
