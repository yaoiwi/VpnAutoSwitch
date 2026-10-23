package com.vpnautoswitch.module.system

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import de.robv.android.xposed.XposedBridge

/**
 * VPN 开关的实际执行者。
 *
 * 思路：操纵系统的「始终开启的 VPN」（always-on VPN）。
 *  - 首选 ConnectivityManager.setAlwaysOnVpnPackageForUser(...)（系统「设置」应用用的同一条路径）
 *  - 该方法在个别版本上不可用时，退化为直接写 Settings.Secure
 *
 * 关闭时会校验设置是否真的被清掉（写入 null 在个别 ROM 上不会删除），
 * 并在日志里留下证据，方便排查「Toast 说关了但 VPN 还在」。
 *
 * 本类运行在 system_server（uid 1000），天然通过 CONTROL_VPN / WRITE_SECURE_SETTINGS 校验。
 */
internal object VpnSwitcher {

    private const val TAG = "VpnAutoSwitch"

    /** Settings.Secure.ALWAYS_ON_VPN_APP，部分 SDK 未公开，直接用字符串 */
    private const val KEY_ALWAYS_ON_APP = "always_on_vpn_app"
    private const val KEY_ALWAYS_ON_LOCKDOWN = "always_on_vpn_lockdown"

    /** 模块自己的辅助键，用于崩溃/重启后恢复用户原有设置 */
    private const val KEY_ORIGINAL_APP = "vpn_autoswitch_original_app"
    private const val KEY_ORIGINAL_LOCKDOWN = "vpn_autoswitch_original_lockdown"
    private const val KEY_ACTIVE = "vpn_autoswitch_active"

    fun isActive(cr: ContentResolver): Boolean = try {
        Settings.Secure.getInt(cr, KEY_ACTIVE, 0) == 1
    } catch (t: Throwable) {
        false
    }

    /** 系统当前的「始终开启的 VPN」应用 */
    fun currentAlwaysOn(ctx: Context): String? = try {
        Settings.Secure.getString(ctx.contentResolver, KEY_ALWAYS_ON_APP)
    } catch (t: Throwable) {
        null
    }

    /**
     * 设置已经指向我们的 VPN、但实际没连上时（例如用户在 VPN 客户端里手动断开），
     * 先清空再写回一次，让系统重新 bind 一次 VpnService。
     */
    fun reapply(ctx: Context, vpnPackage: String, lockdown: Boolean): Boolean {
        val cr = ctx.contentResolver
        return try {
            clearAlwaysOn(ctx, "reapply")
            Thread.sleep(250)
            applyAlwaysOn(ctx, vpnPackage, lockdown)
        } catch (t: Throwable) {
            ModuleLog.append(ctx, "reapply failed: $t")
            false
        }
    }

    /**
     * 打开 VPN：把常驻 VPN 应用设为 [vpnPackage]。
     * @return 是否成功
     */
    fun enable(ctx: Context, vpnPackage: String, lockdown: Boolean): Boolean {
        if (vpnPackage.isEmpty()) return false
        val cr = ctx.contentResolver
        return try {
            if (!isActive(cr)) {
                // 第一次接管：记录用户原本的常驻 VPN 配置
                Settings.Secure.putString(
                    cr, KEY_ORIGINAL_APP,
                    Settings.Secure.getString(cr, KEY_ALWAYS_ON_APP)
                )
                Settings.Secure.putInt(
                    cr, KEY_ORIGINAL_LOCKDOWN,
                    Settings.Secure.getInt(cr, KEY_ALWAYS_ON_LOCKDOWN, 0)
                )
                Settings.Secure.putInt(cr, KEY_ACTIVE, 1)
                ModuleLog.append(
                    ctx,
                    "takeover start, original always_on=${Settings.Secure.getString(cr, KEY_ORIGINAL_APP)}"
                )
            }

            val ok = applyAlwaysOn(ctx, vpnPackage, lockdown)
            ModuleLog.append(ctx, "enable -> $vpnPackage (lockdown=$lockdown) ok=$ok")
            ok
        } catch (t: Throwable) {
            ModuleLog.append(ctx, "enable failed: $t")
            false
        }
    }

    /**
     * 关闭 VPN：清空常驻 VPN 设置（或恢复用户原有配置）。
     * @param vpnPackage 当前配置的 VPN 应用，用于判断「恢复原值」是否等于自己
     * @return 是否执行了关闭动作
     */
    fun disable(ctx: Context, restoreOriginal: Boolean, vpnPackage: String): Boolean {
        val cr = ctx.contentResolver
        return try {
            if (!isActive(cr)) {
                ModuleLog.append(ctx, "disable skipped: not in takeover state")
                return false
            }

            // 先解除 lockdown，避免清空 VPN 时把网络也锁死
            Settings.Secure.putInt(cr, KEY_ALWAYS_ON_LOCKDOWN, 0)

            val original = Settings.Secure.getString(cr, KEY_ORIGINAL_APP)
            // 原本的常驻 VPN 就是同一个应用时，恢复等于没关，必须直接清空
            val canRestore = restoreOriginal && !original.isNullOrEmpty() && original != vpnPackage
            if (canRestore) {
                applyAlwaysOn(ctx, original, Settings.Secure.getInt(cr, KEY_ORIGINAL_LOCKDOWN, 0) == 1)
                ModuleLog.append(ctx, "disable -> restore original $original")
            } else {
                clearAlwaysOn(ctx, "disable")
            }

            Settings.Secure.putInt(cr, KEY_ACTIVE, 0)
            ModuleLog.append(
                ctx,
                "disable done, always_on now=${Settings.Secure.getString(cr, KEY_ALWAYS_ON_APP)}"
            )
            true
        } catch (t: Throwable) {
            ModuleLog.append(ctx, "disable failed: $t")
            false
        }
    }

    /**
     * 不管模块是否还持有「接管标记」，只要 always_on_vpn_app 仍指向 [pkg] 就清掉。
     *
     * 外部控制模式下我们不写这个设置，但历史版本可能留下过；如果不清理，
     * 强制停止客户端后系统会立刻又把它 bind 起来，VPN 就「关不掉」了。
     */
    fun clearIfPointsTo(ctx: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val cr = ctx.contentResolver
        return try {
            val current = Settings.Secure.getString(cr, KEY_ALWAYS_ON_APP)
            if (current != pkg) {
                false
            } else {
                Settings.Secure.putInt(cr, KEY_ALWAYS_ON_LOCKDOWN, 0)
                applyAlwaysOn(ctx, null, false)
                if (Settings.Secure.getString(cr, KEY_ALWAYS_ON_APP) == pkg) {
                    // 个别 ROM 上 putString(null) 不生效，补一次 delete
                    cr.delete(Settings.Secure.getUriFor(KEY_ALWAYS_ON_APP), null, null)
                }
                Settings.Secure.putInt(cr, KEY_ACTIVE, 0)
                ModuleLog.append(ctx, "cleared stale always_on for $pkg")
                true
            }
        } catch (t: Throwable) {
            ModuleLog.append(ctx, "clear stale always_on failed: $t")
            false
        }
    }

    // ---------------------------------------------------------------- 内部实现

    /**
     * 清空「始终开启的 VPN」，并校验是否真的清掉了。
     * 直接写 null 在个别 ROM 上不会删除设置项，所以补一次 delete。
     */
    private fun clearAlwaysOn(ctx: Context, reason: String): Boolean {
        val cr = ctx.contentResolver
        try {
            val methodOk = applyAlwaysOn(ctx, null, false)
            if (!methodOk) {
                Settings.Secure.putString(cr, KEY_ALWAYS_ON_APP, null)
            }
            val left = Settings.Secure.getString(cr, KEY_ALWAYS_ON_APP)
            if (!left.isNullOrEmpty()) {
                ModuleLog.append(ctx, "clear($reason): write null left '$left', try delete")
                try {
                    cr.delete(Settings.Secure.getUriFor(KEY_ALWAYS_ON_APP), null, null)
                } catch (t: Throwable) {
                    ModuleLog.append(ctx, "clear($reason): delete failed $t")
                }
            }
            val after = Settings.Secure.getString(cr, KEY_ALWAYS_ON_APP)
            ModuleLog.append(ctx, "clear($reason): always_on='${after.orEmpty()}'")
            return after.isNullOrEmpty()
        } catch (t: Throwable) {
            ModuleLog.append(ctx, "clear($reason) failed: $t")
            return false
        }
    }

    /**
     * 设置/清空始终开启的 VPN。
     * @param packageName null 表示清空（断开）
     */
    private fun applyAlwaysOn(ctx: Context, packageName: String?, lockdown: Boolean): Boolean {
        // 1) 系统 API：和「设置 → VPN → 始终开启的 VPN」完全相同的调用
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
            val method = cm.javaClass.getMethod(
                "setAlwaysOnVpnPackageForUser",
                Int::class.javaPrimitiveType,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                List::class.java
            )
            val result = method.invoke(
                cm, currentUserId(), packageName, lockdown, emptyList<String>()
            )
            if (result is Boolean && result) return true
            XposedBridge.log("[$TAG] setAlwaysOnVpnPackageForUser returned $result")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] system api unavailable, fallback to settings: $t")
        }

        // 2) 退化方案：直接写 Settings.Secure，Vpn 里的 ContentObserver 会响应
        return try {
            val cr = ctx.contentResolver
            if (packageName == null) {
                Settings.Secure.putString(cr, KEY_ALWAYS_ON_APP, null)
            } else {
                Settings.Secure.putString(cr, KEY_ALWAYS_ON_APP, packageName)
            }
            Settings.Secure.putInt(cr, KEY_ALWAYS_ON_LOCKDOWN, if (lockdown) 1 else 0)
            true
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] settings fallback failed: $t")
            false
        }
    }

    private fun currentUserId(): Int = try {
        val am = Class.forName("android.app.ActivityManager")
        am.getMethod("getCurrentUser").invoke(null) as? Int ?: 0
    } catch (t: Throwable) {
        0
    }
}
