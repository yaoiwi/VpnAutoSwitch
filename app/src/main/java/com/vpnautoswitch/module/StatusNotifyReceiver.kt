package com.vpnautoswitch.module

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * 常驻状态通知的渲染方。
 *
 * system_server 只算出「现在是什么状态」，然后把内容发一条显式广播过来，由 App 自己发通知。
 * 这样通知的归属包是本应用，Android 13 起通知栏左侧显示的才是应用图标（蓝底钥匙），
 * 而不是系统那个绿色小机器人。
 *
 * 接收器在清单里声明了 `android.permission.STATUS_BAR_SERVICE`，只有系统进程能发到它。
 */
class StatusNotifyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ModuleContract.ACTION_STATUS_NOTIFY) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (!intent.getBooleanExtra(ModuleContract.EXTRA_STATUS_ON, false)) {
            manager.cancel(NOTIFICATION_ID)
            return
        }

        // Android 13 起没有运行时授权时 notify 会被静默丢弃，提前返回省得白跑
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val title = intent.getStringExtra(ModuleContract.EXTRA_STATUS_TITLE).orEmpty()
        val text = intent.getStringExtra(ModuleContract.EXTRA_STATUS_TEXT).orEmpty()
        try {
            ensureChannel(manager)
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_vpn_key)
                .setContentTitle(title)
                .setContentText(text)
                // 内容可能在锁屏上被看到，别把 Wi-Fi 名称之类的东西挂上去
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp(context))
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            // 通知失败不能影响任何控制逻辑
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
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
    }

    /**
     * 点击进设置页。
     *
     * 桌面图标被隐藏后 activity-alias 是 disabled，`getLaunchIntentForPackage` 会返回 null，
     * 这时直接指向 MainActivity 本身——它一直 exported，不受隐藏影响。
     */
    private fun openApp(context: Context): PendingIntent? = try {
        val intent = context.packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
            ?: Intent(context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    } catch (t: Throwable) {
        null
    }

    private companion object {
        const val CHANNEL_ID = "vpn_autoswitch_status"

        /** 固定 id：状态变了就覆盖同一条，不会堆成一列 */
        const val NOTIFICATION_ID = 0x5650
    }
}
