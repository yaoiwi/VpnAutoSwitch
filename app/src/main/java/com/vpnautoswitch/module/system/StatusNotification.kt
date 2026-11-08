package com.vpnautoswitch.module.system

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.vpnautoswitch.module.BuildConfig
import com.vpnautoswitch.module.Config
import com.vpnautoswitch.module.ModuleContract
import de.robv.android.xposed.XposedBridge

/**
 * 常驻状态通知的「决策层」。
 *
 * 状态机整体在 system_server 里，只有它知道「当前命中了哪条规则、VPN 是不是真的连上了」，
 * 所以由它决定什么时候该刷新；但**真正发通知的是 App**，不是这里。
 *
 * 为什么绕这一圈：从 system_server 发通知，归属包是 `android`。Android 13 起通知栏左侧
 * 显示的是「应用图标」而不是 small icon，于是那里永远是系统那个绿色小机器人——之前几版
 * 反复换 small icon 都不见效，就是卡在这儿。交给 App 发之后，归属、图标、点击全部回到
 * 本应用，small icon 也能正常生效。
 *
 * 代价是每次状态变化要拉起一次 App 进程（显式广播，代价约百毫秒级，且只在状态真的
 * 变了的时候才发）。通知本身挂在系统里，App 进程被回收不影响它继续显示。
 */
internal object StatusNotification {

    private var lastKey: String? = null
    private var loggedFailure = false
    private var legacyCleared = false

    /**
     * 按当前状态刷新通知；[Config.statusNotification] 关掉或模块停用时把已有通知撤掉。
     *
     * [detail] 直接用 `currentRule` 的中文说明，不在通知层再翻译一遍，避免两处文案分叉。
     */
    fun sync(ctx: Context, cfg: Config, rule: String, detail: String) {
        clearLegacy(ctx)
        if (!cfg.statusNotification || !cfg.enabled || cfg.vpnPackage.isEmpty()) {
            cancel(ctx)
            return
        }
        val connected = try {
            VpnSwitcher.isActive(ctx.contentResolver)
        } catch (t: Throwable) {
            false
        }
        val key = "$rule|$detail|$connected"
        if (key == lastKey) return

        val text = buildString {
            if (rule == "paused") append("自动联动已暂停") else append(
                if (connected) "VPN 已连接" else "VPN 未连接"
            )
            if (detail.isNotEmpty()) append(" · ").append(detail)
        }
        if (send(ctx, true, "VPN 自动开关", text)) lastKey = key
    }

    fun cancel(ctx: Context) {
        clearLegacy(ctx)
        if (lastKey == null) return
        lastKey = null
        send(ctx, false, "", "")
    }

    /**
     * 撤掉 1.9.2 及以前由 system_server 直接发的那条通知。
     *
     * 那条的归属包是 `android`，App 自己 cancel 不掉；升级后如果不在这里清一次，
     * 它会一直挂在通知栏里（ongoing），跟新的那条并排显示。
     */
    private fun clearLegacy(ctx: Context) {
        if (legacyCleared) return
        legacyCleared = true
        try {
            ctx.getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            // 忽略：清不掉也只是多一条旧通知，不影响功能
        }
    }

    /**
     * 发一条显式广播给 App 的接收器。
     *
     * 类名必须写全：应用 id 是 io.github.yaoiwi.vpnautoswitch，代码所在的包是
     * com.vpnautoswitch.module，两者不是一回事。
     */
    private fun send(ctx: Context, on: Boolean, title: String, text: String): Boolean = try {
        val intent = Intent(ModuleContract.ACTION_STATUS_NOTIFY)
            .setClassName(BuildConfig.APPLICATION_ID, STATUS_RECEIVER)
            // 常驻通知要对得上当前状态，别排进后台队列里等
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(ModuleContract.EXTRA_STATUS_ON, on)
            .putExtra(ModuleContract.EXTRA_STATUS_TITLE, title)
            .putExtra(ModuleContract.EXTRA_STATUS_TEXT, text)
        ctx.sendBroadcast(intent)
        true
    } catch (t: Throwable) {
        // 通知失败绝不能影响 VPN 控制；只在第一次记一行，避免刷屏
        if (!loggedFailure) {
            loggedFailure = true
            XposedBridge.log("[${Engine.TAG}] status notification failed: $t")
        }
        false
    }

    /** 接收器类名（namespace 下的全限定名，见上） */
    private const val STATUS_RECEIVER = "com.vpnautoswitch.module.StatusNotifyReceiver"

    /** 与 App 侧一致；只用来清理旧版本那条归属 `android` 的通知 */
    private const val NOTIFICATION_ID = 0x5650
}
