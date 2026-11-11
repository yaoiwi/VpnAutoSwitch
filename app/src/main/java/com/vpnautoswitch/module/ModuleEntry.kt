package com.vpnautoswitch.module

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.vpnautoswitch.module.system.ActivityRecords
import com.vpnautoswitch.module.system.attach
import com.vpnautoswitch.module.system.ClientStartGuard
import com.vpnautoswitch.module.system.ConfigRepo
import com.vpnautoswitch.module.system.Engine
import com.vpnautoswitch.module.system.HookCounts
import com.vpnautoswitch.module.system.ModuleLog
import com.vpnautoswitch.module.system.RuntimeDiagnostics
import com.vpnautoswitch.module.system.onAppLaunching
import com.vpnautoswitch.module.system.onForeground
import com.vpnautoswitch.module.system.onProcessDied
import com.vpnautoswitch.module.system.onTaskRemoved
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
    private var blockedStarts = 0

    private companion object {
        val PROCESS_HOLDERS = arrayOf(
            "com.android.server.am.ActivityManagerService",
            "com.android.server.am.ProcessList",
        )
        /**
         * 只挂「进程真的死了」这两个入口。
         *
         * 以前还挂了 `cleanUpApplicationRecordLocked`，但它是 AMS 清理残留进程记录时
         * 调用的内部方法：应用从被杀状态重新拉起、旧 ProcessRecord 被移除时也会走，
         * 包名完全正确，看上去就像「应用刚打开就退出了」。Chrome 这类多进程应用每次
         * 启动都会命中，导致 VPN 连上立刻被关（issue #6）。
         */
        val DEATH_METHODS = arrayOf(
            "handleAppDiedLocked",
            "appDiedLocked",
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return

        val ctx = systemContext()
        XposedBridge.log("[VpnAutoSwitch] hooked system_server (${lpparam.processName})")
        // 先写一行「开始加载」：后面任何一步抛异常，日志里至少能看出走到过这里，
        // 自检的「Hook 挂载」也就不会只剩一句「还没上报」而无从定位
        ModuleLog.append(ctx, "system_server load start (v${BuildConfig.VERSION_CODE})")

        // 这三步以前没有兜底：任一抛异常就跳过后面的 hook 安装和快照写入，
        // 界面表现为「hook 一栏永远未上报」，日志里也看不出断在哪一步
        step(ctx, "ActivityRecords") { ActivityRecords.attach(lpparam.classLoader) }
        step(ctx, "ProcessRecords") { ProcessRecords.attach(lpparam.classLoader) }
        step(ctx, "Engine") { Engine.attach(ctx) }

        // 逐类安装：某类在个别 ROM 上抛异常时，只把这一类记成 0，
        // 后面的几类和 publishHooks 都还能照常执行（见 install 的注释）
        val counts = HookCounts(
            startActivity = install(ctx, "startActivity") { hookActivityStart(lpparam.classLoader) },
            activityResumed = install(ctx, "activityResumed") { hookActivityResumed(lpparam.classLoader) },
            setState = install(ctx, "setState") { hookActivityRecordState(lpparam.classLoader) },
            death = install(ctx, "death") { hookProcessDeath(lpparam.classLoader) },
            taskRemoved = install(ctx, "taskRemoved") { hookTaskRemoved(lpparam.classLoader) },
            notif = install(ctx, "notif") { hookVpnNotification(lpparam.classLoader) },
            blockClientStart = install(ctx, "blockClientStart") { hookClientStarts(lpparam.classLoader) },
        )

        val summary = "hooks: " + counts.asMap().entries.joinToString(", ") { "${it.key}=${it.value}" }
        XposedBridge.log("[VpnAutoSwitch] $summary")
        ModuleLog.append(ctx, "system_server loaded ($summary)")

        // 每类单独记一行：以前挤在一行里，某类为 0 时很容易一眼扫过去；
        // 自检里也会按这些计数分级提示，日志这边留个能直接搜到名字的现场。
        counts.asMap().filterValues { it <= 0 }.forEach { (name, _) ->
            ModuleLog.append(ctx, "hook $name not installed (0 methods)")
        }
        RuntimeDiagnostics.publishHooks(ctx, counts)
    }

    /** 加载阶段的一步。失败只记一行，不打断后面的 hook 安装与快照写入。 */
    private fun step(ctx: Context?, name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            ModuleLog.append(ctx, "attach $name failed: ${t.javaClass.simpleName}: ${t.message}")
            XposedBridge.log("[VpnAutoSwitch] attach $name failed: $t")
        }
    }

    /**
     * 安装一类 hook，失败时只把这一类记成 0。
     *
     * 七类 hook 以前写在同一个 `HookCounts(...)` 构造里：任何一类在某个 ROM 上
     * 抛 `NoSuchMethodError` / `ClassNotFoundError`，后面的几类就都不会执行，
     * `publishHooks` 也走不到——结果 on 界面上表现为「模块心跳正常、hook 一栏永远未上报」，
     * 而日志里连 `system_server loaded` 都没有，看不出断在哪一类。
     */
    private fun install(ctx: Context?, name: String, block: () -> Int): Int = try {
        block()
    } catch (t: Throwable) {
        ModuleLog.append(ctx, "hook $name failed: ${t.javaClass.simpleName}: ${t.message}")
        XposedBridge.log("[VpnAutoSwitch] hook $name failed: $t")
        0
    }

    // ------------------------------------------------------------ Activity 前台

    /**
     * ActivityTaskManagerService.startActivity(..., Intent, ...)。
     *
     * 用户点开应用的那一刻就能拿到包名，比 activityResumed（第一帧画出后）早半秒到一秒。
     * 提前把常驻 VPN 写上，让系统在目标应用启动的同时 bind VpnService，
     * 而不是等应用已经打开、用户已经开始等网络了才动手。
     */
    private fun hookActivityStart(cl: ClassLoader): Int {
        val cls = findClass("com.android.server.wm.ActivityTaskManagerService", cl) ?: return 0
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                    val pkg = intent.component?.packageName ?: intent.`package`
                    if (!pkg.isNullOrEmpty()) Engine.onAppLaunching(pkg)
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
            }
        }
        var total = 0
        for (name in arrayOf("startActivity", "startActivityAsUser", "startActivityAsUserEvenIfSleeping")) {
            total += XposedBridge.hookAllMethods(cls, name, callback).size
        }
        return total
    }

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
                    handleDeathArgs(param.args, param.method?.name)
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

    private fun handleDeathArgs(args: Array<Any?>?, methodName: String? = null) {
        if (args == null) return
        val record = args.firstOrNull {
            it != null && it.javaClass.name == "com.android.server.am.ProcessRecord"
        } ?: return
        val pkg = ProcessRecords.packageOf(record) ?: return
        val procName = ProcessRecords.processNameOf(record) ?: pkg
        // 只有主进程结束才算「应用被划掉」，避免 :push / :remote 子进程退出误判
        if (procName == pkg) {
            // 带上方法与 pid：下次再出现「刚打开就报死亡」时，能直接看出是哪个回调、
            // 死的是不是新进程，不用再靠时间戳反推
            val detail = "${methodName ?: "unknown"}, pid=${ProcessRecords.pidOf(record)}"
            Engine.onProcessDied(pkg, detail)
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

    // ------------------------------------------------------------ 客户端自启动闸门

    /**
     * 强停 VPN 客户端之后，拦住几秒内对它的 bind / startService。
     *
     * `forceStopPackage` 只压制隐式广播和 Activity 自启，**不压制别人 bind 它的服务**。
     * 隧道刚断时 system_server 里的 Vpn 还挂着 ServiceConnection，或者客户端自己的前台服务
     * 被 START_STICKY 拉起，于是进程一秒内复活、隧道重建——日志里就是
     * `force stopped` → `guard: vpn came back -> re-off` 的同一次退出强停两遍，
     * 而 MIUI 会在 bind 时弹「正在启动服务」。
     *
     * 这里在 [ActiveServices] 的入口拦：窗口由 [ClientStartGuard] 管，只对刚被强停的那个包生效，
     * 用户主动打开客户端或模块自己 ensureVpn 时会立刻释放，不影响正常使用。
     */
    private fun hookClientStarts(cl: ClassLoader): Int {
        val cls = findClass("com.android.server.am.ActiveServices", cl) ?: return 0
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                    val pkg = intent.component?.packageName ?: intent.`package`
                    if (pkg.isNullOrEmpty()) return
                    if (!ClientStartGuard.blocked(pkg, SystemClock.elapsedRealtime())) return

                    // 注意 bindServiceLocked 返回的是结果码：0 表示成功，负数才是失败。
                    // 这里必须是负值，返回 0 等于骗调用方「绑上了」，它随后拿不到 binder 会 NPE。
                    param.result = when ((param.method as? java.lang.reflect.Method)?.returnType) {
                        Int::class.javaPrimitiveType -> -1
                        Boolean::class.javaPrimitiveType -> false
                        else -> null
                    }
                    // 每次关闭都会挡几拍，全记下来会淹掉日志，前三次足够证明闸门在工作
                    if (blockedStarts < 3) {
                        blockedStarts++
                        XposedBridge.log(
                            "[VpnAutoSwitch] blocked ${param.method.name} of $pkg " +
                                "(client was just force-stopped)"
                        )
                    }
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
            }
        }
        var total = 0
        for (name in arrayOf("bindServiceLocked", "startServiceLocked", "startServiceInPackage")) {
            total += XposedBridge.hookAllMethods(cls, name, callback).size
        }
        return total
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
