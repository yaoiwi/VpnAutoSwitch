package com.vpnautoswitch.module.system

import android.os.IBinder

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
}
