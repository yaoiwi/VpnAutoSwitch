package com.vpnautoswitch.module.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vpnautoswitch.module.BuildConfig
import com.vpnautoswitch.module.Config
import de.robv.android.xposed.XposedBridge

/**
 * 常驻状态通知。
 *
 * 为什么由 system_server 发、而不是 App 自己发：模块的状态机整体在系统进程里，
 * 只有它知道「当前命中了哪条规则、VPN 是不是真的连上了」。让 App 常驻去轮询，
 * 等于用一个前台服务换来一条通知，不划算。
 *
 * 代价是通知归属显示为「Android 系统」（发通知的 uid 是 system），
 * 设置页的说明里已经写清楚了。
 */
internal object StatusNotification {

    private const val CHANNEL_ID = "vpn_autoswitch_status"

    /** 固定 id：状态变了就覆盖同一条，不会堆成一列 */
    private const val NOTIFICATION_ID = 0x5650

    private var lastKey: String? = null
    private var channelReady = false
    private var loggedFailure = false

    /**
     * 按当前状态刷新通知；[statusNotification] 关掉或模块停用时把已有通知撤掉。
     *
     * [detail] 直接用 `currentRule` 的中文说明，不在通知层再翻译一遍，避免两处文案分叉。
     */
    fun sync(ctx: Context, cfg: Config, rule: String, detail: String) {
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

        val manager = try {
            ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        } catch (t: Throwable) {
            null
        } ?: return

        try {
            ensureChannel(ctx, manager)
            val text = buildString {
                if (rule == "paused") append("自动联动已暂停") else append(
                    if (connected) "VPN 已连接" else "VPN 未连接"
                )
                if (detail.isNotEmpty()) append(" · ").append(detail)
            }
            Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(smallIconRes(ctx))
                .setContentTitle("VPN 自动开关")
                .setContentText(text)
                // 内容可能在锁屏上被看到，别把 Wi-Fi 名称之类的东西挂上去
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openAppIntent(ctx))
                .build()
                .let { manager.notify(NOTIFICATION_ID, it) }
            lastKey = key
        } catch (t: Throwable) {
            // 通知失败绝不能影响 VPN 控制；只在第一次记一行，避免刷屏
            if (!loggedFailure) {
                loggedFailure = true
                XposedBridge.log("[${Engine.TAG}] status notification failed: $t")
            }
        }
    }

    fun cancel(ctx: Context) {
        if (lastKey == null) return
        lastKey = null
        try {
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            // 忽略
        }
    }

    private fun ensureChannel(ctx: Context, manager: NotificationManager) {
        if (channelReady) return
        // 只建一次；已存在时系统会忽略重复创建
        val channel = NotificationChannel(
            CHANNEL_ID,
            "运行状态",
            // LOW：不响、不弹横幅，只在通知栏里挂着
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示 VPN 当前是否连接，以及命中的规则"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        channelReady = true
    }

    /**
     * 系统自带的小图标。
     *
     * `stat_sys_vpn_ic` 是 internal 资源，system_server 里能按名字取到；
     * 取不到（不同 ROM 改了名字）就退回一个肯定存在的通用图标。
     */
    private fun smallIconRes(ctx: Context): Int {
        val id = try {
            ctx.resources.getIdentifier("stat_sys_vpn_ic", "drawable", "android")
        } catch (t: Throwable) {
            0
        }
        return if (id != 0) id else android.R.drawable.ic_dialog_info
    }

    private fun openAppIntent(ctx: Context): PendingIntent? = try {
        // 优先走桌面入口：用户隐藏图标后 activity-alias 是 disabled，这个方法会返回 null，
        // 于是退回设置页本身——两条路都能进来，不会因为隐藏图标导致通知点了没反应
        val intent = ctx.packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
            ?: Intent().setClassName(MODULE_PACKAGE, "$MODULE_PACKAGE.MainActivity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // minSdk 26，FLAG_IMMUTABLE 必须有（Android 12 起不带会直接崩）
        PendingIntent.getActivity(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    } catch (t: Throwable) {
        null
    }

    /**
     * 代码所在的真实包名（namespace），和 applicationId 不是一回事。
     *
     * 这里不能写 `BuildConfig.APPLICATION_ID + ".MainActivity"`：
     * 应用 id 是 io.github.yaoiwi.vpnautoswitch，而类是 com.vpnautoswitch.module.MainActivity。
     * 也不能用 `MainActivity::class.java.name`——那会触发 App 侧的类加载，
     * 在 system_server 里没有 App 的 classloader。
     */
    private const val MODULE_PACKAGE = "com.vpnautoswitch.module"
}
