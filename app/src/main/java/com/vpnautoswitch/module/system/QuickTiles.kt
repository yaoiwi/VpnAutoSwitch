package com.vpnautoswitch.module.system

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.service.quicksettings.TileService

/**
 * 通过 VPN 客户端自己的「快捷设置磁贴」来开/关 VPN。
 *
 * 有些客户端（例如 Clash Meta）并**不响应系统的 always-on VPN**：设置写进去了，
 * 系统也 bind 了它的 VpnService，但它不会去 establish 隧道，所以连不上。
 * 这类客户端通常都提供 QS 磁贴，从 system_server 点一下磁贴等价于用户手动点，
 * 是最通用的「远程控制」入口。
 *
 * 注意：`StatusBarManager.clickTile` 是 @SystemApi，但不同 Android 版本 / 定制 ROM 的
 * 方法名和可见性差异很大（有的只剩 `clickTileInternal`，有的干脆没有），所以这里
 * 依次尝试多条路径；全部失败后记住结论，不再反复反射和刷日志。
 */
internal object QuickTiles {

    /** 所有已知入口都不存在（避免每次重试都做一轮反射） */
    private var unsupported = false

    /** 找到某个应用的 QS 磁贴组件（没有就返回 null） */
    fun find(ctx: Context, pkg: String): ComponentName? = try {
        val intent = Intent(TileService.ACTION_QS_TILE).setPackage(pkg)
        ctx.packageManager.queryIntentServices(intent, 0)
            .firstOrNull { it.serviceInfo.packageName == pkg }
            ?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    } catch (t: Throwable) {
        null
    }

    /** 模拟点击磁贴；成功返回 true */
    fun click(ctx: Context, component: ComponentName): Boolean {
        if (unsupported) return false
        if (viaStatusBarManager(ctx, component)) return true
        if (viaStatusBarService(component)) return true

        unsupported = true
        ModuleLog.append(
            ctx,
            "当前系统没有可用的 clickTile 接口，磁贴方式不可用（不影响 always-on 方式）"
        )
        return false
    }

    private fun viaStatusBarManager(ctx: Context, c: ComponentName): Boolean {
        val sbm = try {
            ctx.getSystemService(Context.STATUS_BAR_SERVICE) as? StatusBarManager
        } catch (t: Throwable) {
            null
        } ?: return false

        val cls = sbm.javaClass
        // 1) clickTile(ComponentName) —— Android 13 / 14 的 @SystemApi
        invokeIfPresent(cls, sbm, "clickTile", arrayOf(ComponentName::class.java), c)?.let { return true }
        // 2) clickTileInternal(ComponentName) —— 部分 ROM（含 HyperOS）改名后的版本
        invokeIfPresent(cls, sbm, "clickTileInternal", arrayOf(ComponentName::class.java), c)?.let { return true }
        // 3) clickTile(ComponentName, int userId)
        invokeIfPresent(
            cls, sbm, "clickTile",
            arrayOf<Class<*>>(ComponentName::class.java, Int::class.javaPrimitiveType!!), c, 0
        )?.let { return true }
        return false
    }

    /**
     * 直接走 binder：`IStatusBarService.clickTile(ComponentName)`。
     * 即使 StatusBarManager 上被裁掉了，AIDL 接口通常还在。
     */
    private fun viaStatusBarService(c: ComponentName): Boolean = try {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java)
            .invoke(null, "statusbar") as? IBinder
        if (binder == null) {
            false
        } else {
            val stub = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
            val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val method = service?.javaClass?.getMethod("clickTile", ComponentName::class.java)
            if (service != null && method != null) {
                method.invoke(service, c)
                true
            } else {
                false
            }
        }
    } catch (t: Throwable) {
        false
    }

    /**
     * 方法不存在时返回 null（继续试下一条路径）；存在且调用成功返回 true。
     * 调用本身抛异常（权限、RemoteException）也算失败，继续试下一条。
     */
    private fun invokeIfPresent(
        cls: Class<*>,
        target: Any,
        name: String,
        types: Array<Class<*>>,
        vararg args: Any?
    ): Boolean? {
        val method = try {
            cls.getMethod(name, *types)
        } catch (t: NoSuchMethodException) {
            return null
        } catch (t: Throwable) {
            return null
        }
        return try {
            method.invoke(target, *args)
            true
        } catch (t: Throwable) {
            null
        }
    }
}
