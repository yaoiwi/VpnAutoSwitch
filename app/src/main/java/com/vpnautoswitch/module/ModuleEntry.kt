package com.vpnautoswitch.module

import android.app.Notification
import android.content.Context
import android.os.IBinder
import com.vpnautoswitch.module.system.ActivityRecords
import com.vpnautoswitch.module.system.ConfigRepo
import com.vpnautoswitch.module.system.Engine
import com.vpnautoswitch.module.system.ModuleLog
import com.vpnautoswitch.module.system.ProcessRecords
import com.vpnautoswitch.module.system.TaskRecords
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 入口（见 assets/xposed_init）。
 *
 * 只在 system_server（包名 "android"）中工作：
 *  - 监听 Activity 进入 RESUMED → 目标应用被打开
 *  - 监听 AMS 的进程死亡回调   → 应用被划掉 / 被杀
 * 两者都在 system_server 里，天然拥有系统权限，可直接改写 always-on VPN 设置。
 */
class ModuleEntry : IXposedHookLoadPackage {

    private var suppressedLogged = false

    private companion object {
        val PROCESS_HOLDERS = arrayOf(
            "com.android.server.am.ActivityManagerService",
            "com.android.server.am.ProcessList",
        )
        val DEATH_METHODS = arrayOf(
            "handleAppDiedLocked",
            "appDiedLocked",
            "cleanUpApplicationRecordLocked",
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return

        val ctx = systemContext()
        XposedBridge.log("[VpnAutoSwitch] hooked system_server (${lpparam.processName})")

        ActivityRecords.attach(lpparam.classLoader)
        ProcessRecords.attach(lpparam.classLoader)
        Engine.attach(ctx)

        val resumedHooks = hookActivityResumed(lpparam.classLoader)
        val stateHooks = hookActivityRecordState(lpparam.classLoader)
        val deathHooks = hookProcessDeath(lpparam.classLoader)
        val taskHooks = hookTaskRemoved(lpparam.classLoader)
        val notifHooks = hookVpnNotification(lpparam.classLoader)

        val summary =
            "hooks: activityResumed=$resumedHooks, setState=$stateHooks, death=$deathHooks, " +
                "taskRemoved=$taskHooks, notif=$notifHooks"
        XposedBridge.log("[VpnAutoSwitch] $summary")
        ModuleLog.append(ctx, "system_server loaded ($summary)")
    }

    // ------------------------------------------------------------ Activity 前台

    /**
     * ActivityTaskManagerService.activityResumed(IBinder token[, boolean])，
     * 参数在不同版本上略有差异，用 hookAllMethods 全部接管，只取第一个 IBinder。
     */
    private fun hookActivityResumed(cl: ClassLoader): Int {
        val cls = findClass("com.android.server.wm.ActivityTaskManagerService", cl) ?: return 0
        val unhooks = XposedBridge.hookAllMethods(cls, "activityResumed", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val token = param.args.getOrNull(0) as? IBinder ?: return
                    val pkg = ActivityRecords.packageOfToken(token) ?: return
                    Engine.onForeground(pkg)
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
            }
        })
        return unhooks.size
    }

    /**
     * 兜底：ActivityRecord.setState(RESUMED, ...)。
     * 某些 ROM 上 activityResumed 被内联或改名时，这条路仍然有效。
     */
    private fun hookActivityRecordState(cl: ClassLoader): Int {
        val cls = findClass("com.android.server.wm.ActivityRecord", cl) ?: return 0
        val unhooks = XposedBridge.hookAllMethods(cls, "setState", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val state = param.args.getOrNull(0) as? Enum<*> ?: return
                    if (state.name != "RESUMED") return
                    val pkg = ActivityRecords.packageOf(param.thisObject) ?: return
                    Engine.onForeground(pkg)
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
            }
        })
        return unhooks.size
    }

    // ------------------------------------------------------------ 进程死亡

    /**
     * ActivityManagerService.handleAppDiedLocked(ProcessRecord, ...) —— 进程真正结束时回调，
     * 从最近任务里划掉应用、强行停止、崩溃都会走到这里。
     */
    private fun hookProcessDeath(cl: ClassLoader): Int {
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    handleDeathArgs(param.args)
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
            }
        }

        // 不同 Android 版本里，进程死亡的回调位置/方法名有差异，全都挂上，参数靠类型识别
        var total = 0
        for (className in PROCESS_HOLDERS) {
            val cls = findClass(className, cl) ?: continue
            for (methodName in DEATH_METHODS) {
                total += XposedBridge.hookAllMethods(cls, methodName, callback).size
            }
        }
        return total
    }

    private fun handleDeathArgs(args: Array<Any?>?) {
        if (args == null) return
        val record = args.firstOrNull {
            it != null && it.javaClass.name == "com.android.server.am.ProcessRecord"
        } ?: return
        val pkg = ProcessRecords.packageOf(record) ?: return
        val procName = ProcessRecords.processNameOf(record) ?: pkg
        // 只有主进程结束才算「应用被划掉」，避免 :push / :remote 子进程退出误判
        if (procName == pkg) {
            Engine.onProcessDied(pkg)
        }
    }

    // ------------------------------------------------------------ 最近任务移除

    /**
     * 从最近任务划掉应用。
     *
     * 进程有前台服务时不会立刻死亡，单靠 handleAppDiedLocked 会漏掉。
     * 这里优先挂 ActivityTaskSupervisor.removeTask(..., "remove-task")，参数里有 Task；
     * 再兜底挂 ActivityTaskManagerService.removeTask(int)，用 taskId 反查包名。
     * 两者可能同时命中，Engine 里的 aliveTargets 会去重。
     */
    private fun hookTaskRemoved(cl: ClassLoader): Int {
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val reason = param.args.lastOrNull() as? String
                    val task = param.args.firstOrNull { isTaskLike(it) }
                    if (task != null) {
                        // 只接管用户从最近任务移除，不碰 remove-stack / trim 等内部清理
                        if (reason != null && reason != "remove-task") return
                        val pkg = TaskRecords.packageOf(task) ?: return
                        Engine.onTaskRemoved(pkg)
                        return
                    }

                    if (param.args.size == 1) {
                        val taskId = param.args[0] as? Int ?: return
                        val pkg = TaskRecords.packageOfTaskId(param.thisObject, taskId) ?: return
                        Engine.onTaskRemoved(pkg)
                    }
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
            }
        }

        var total = 0
        for (className in arrayOf(
            "com.android.server.wm.ActivityTaskSupervisor",
            "com.android.server.wm.ActivityTaskManagerService",
        )) {
            val cls = findClass(className, cl) ?: continue
            total += XposedBridge.hookAllMethods(cls, "removeTask", callback).size
        }
        return total
    }

    private fun isTaskLike(value: Any?): Boolean {
        val name = value?.javaClass?.name ?: return false
        return name.endsWith(".Task") || name.endsWith(".TaskRecord")
    }

    // ------------------------------------------------------------ 工具

    /**
     * 拦截系统那条「始终开启的 VPN 已断开连接 / 更改网络或 VPN 设置」通知。
     *
     * 它由 system_server 以包名 "android" 发到 channel "VPN" 上，只要 always-on 指向某个
     * 客户端、而隧道当前没连着，就会反复出现。模块本来就在控制 VPN 的开关，这条提示
     * 对用户没有信息量，直接在 NotificationManagerService 入口丢掉。
     */
    private fun hookVpnNotification(cl: ClassLoader): Int {
        val cls = findClass("com.android.server.notification.NotificationManagerService", cl) ?: return 0
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val pkg = param.args.filterIsInstance<String>().firstOrNull() ?: return
                    if (pkg != "android") return
                    val notification = param.args.filterIsInstance<Notification>().firstOrNull() ?: return
                    if (!isAlwaysOnVpnNotification(notification)) return

                    val ctx = systemContext() ?: return
                    if (!ConfigRepo.get(ctx).hideVpnNotification) return

                    param.result = null
                    if (!suppressedLogged) {
                        suppressedLogged = true
                        ModuleLog.append(ctx, "suppressed system always-on VPN notification")
                    }
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
            }
        }
        val hooks = XposedBridge.hookAllMethods(cls, "enqueueNotificationInternal", callback)
        return hooks.size
    }

    private fun isAlwaysOnVpnNotification(n: Notification): Boolean {
        val channel = n.channelId.orEmpty()
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val body = "$title $text"
        return channel.equals("VPN", ignoreCase = true) ||
            body.contains("始终开启的 VPN") ||
            body.contains("Always-on VPN", ignoreCase = true) ||
            body.contains("Always on VPN", ignoreCase = true)
    }

    private fun findClass(name: String, cl: ClassLoader): Class<*>? = try {
        XposedHelpers.findClass(name, cl)
    } catch (t: Throwable) {
        XposedBridge.log("[VpnAutoSwitch] class not found: $name ($t)")
        null
    }

    private fun systemContext(): Context? = try {
        val cls = Class.forName("android.app.ActivityThread")
        val current = cls.getMethod("currentActivityThread").invoke(null)
        cls.getMethod("getSystemContext").invoke(current) as? Context
    } catch (t: Throwable) {
        XposedBridge.log("[VpnAutoSwitch] system context unavailable: $t")
        null
    }
}
