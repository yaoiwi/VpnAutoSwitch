package com.vpnautoswitch.module.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * 清除「应用已被强制停止」标记。
 *
 * Android 的 force-stop 不只是杀进程，还会把包标记成 stopped 状态：之后系统不会
 * 再隐式启动它（包括为「始终开启的 VPN」去 bind 它的 VpnService），要等用户手动
 * 点一次图标才会解除。
 *
 * 这会带来一个很隐蔽的连锁问题：
 *   划掉目标应用 → 模块强制停止 VPN 客户端断开隧道 → 下次打开目标应用 →
 *   always-on 设置写进去了，但系统拒绝 bind 一个 stopped 的包 → 永远连不上。
 *
 * 所以强制停止之后、以及每次重新拉起之前，都要把这个标记清掉。
 */
internal object Packages {

    private var packageManagerInternal: Any? = null
    private val unstopped = HashSet<String>()

    /** 强制停止之后要调用，下一次才会重新清标记 */
    fun forget(pkg: String) {
        unstopped.remove(pkg)
    }

    /** @return 是否成功执行（不代表原本确实处于 stopped 状态） */
    fun unstop(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        if (pkg in unstopped) return true

        // 1) PackageManagerInternal.setPackageStoppedState —— system_server 本地接口，最直接
        try {
            val localServices = Class.forName("com.android.server.LocalServices")
            val pmiClass = Class.forName("android.content.pm.PackageManagerInternal")
            var pmi = packageManagerInternal
            if (pmi == null) {
                pmi = localServices
                    .getMethod("getService", Class::class.java)
                    .invoke(null, pmiClass)
                packageManagerInternal = pmi
            }
            if (pmi != null) {
                pmi.javaClass.getMethod(
                    "setPackageStoppedState",
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).invoke(pmi, pkg, false, 0)
                unstopped.add(pkg)
                return true
            }
        } catch (t: Throwable) {
            // 某些 ROM 没有这个方法，继续走 binder
        }

        // 2) IPackageManager.setPackageStoppedState
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java)
                .invoke(null, "package") as? IBinder ?: return false
            val stub = Class.forName("android.content.pm.IPackageManager\$Stub")
            val service = stub.getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: return false
            service.javaClass.getMethod(
                "setPackageStoppedState",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(service, pkg, false, 0)
            unstopped.add(pkg)
            return true
        } catch (t: Throwable) {
            return false
        }
    }

    /**
     * 一次性把这个包的几项关键事实取出来，给启动前的就绪判断用。
     *
     * 和 [stateLabel] 查的是同一批东西，区别是这个返回结构化的值：诊断要拿它下结论，
     * 不能再从「未停用 / 未冻结 / 后台受限」这种给人看的字符串里反解析。
     */
    fun factsOf(ctx: Context, pkg: String): ClientFacts {
        if (pkg.isEmpty()) return ClientFacts(installed = null, enabled = null, hasVpnService = null)
        val pm = ctx.packageManager
        val info = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (t: Throwable) {
            // 查不到就是没装（或正在安装，PackageManager 会短暂报 NameNotFound）
            return ClientFacts(installed = false, enabled = null, hasVpnService = null)
        }
        val suspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                pm.isPackageSuspended(pkg)
            } catch (t: Throwable) {
                false
            }
        } else {
            false
        }
        // 服务查不到和「没有服务」要分开：查包抛异常时不能断言它没有 VpnService
        val hasService: Boolean? = try {
            pm.getPackageInfo(pkg, PackageManager.GET_SERVICES)?.services?.any {
                it.permission == "android.permission.BIND_VPN_SERVICE"
            }
        } catch (t: Throwable) {
            null
        }
        return ClientFacts(
            installed = true,
            enabled = info.enabled,
            hasVpnService = hasService,
            stopped = info.flags and ApplicationInfo.FLAG_STOPPED != 0,
            suspended = suspended,
        )
    }

    /**
     * 一行说清这个包当前被系统怎么对待，形如 `未停用 / 未冻结 / 后台受限`。
     *
     * 拉起失败时最需要这三项：「服务起不来」到底是包被强停过、被系统冻结、
     * 还是后台被电池策略掐了，以前全靠人和用户来回问「自启开了吗、省电策略改了吗」。
     * 查询本身不需要权限，模块在 system_server 里直接读。
     */
    fun stateLabel(ctx: Context, pkg: String): String {
        if (pkg.isEmpty()) return "包状态未知"
        val pm = ctx.packageManager
        val info = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (t: Throwable) {
            null
        } ?: return "查不到包（$pkg）"
        val stopped = info.flags and ApplicationInfo.FLAG_STOPPED != 0
        val suspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                pm.isPackageSuspended(pkg)
            } catch (t: Throwable) {
                false
            }
        } else {
            false
        }
        val exempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val power = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            power?.isIgnoringBatteryOptimizations(pkg) == true
        } else {
            true
        }
        return listOf(
            if (stopped) "被强停过" else "未停用",
            if (suspended) "被冻结" else "未冻结",
            if (exempt) "后台豁免" else "后台受限",
        ).joinToString(" / ")
    }
}
