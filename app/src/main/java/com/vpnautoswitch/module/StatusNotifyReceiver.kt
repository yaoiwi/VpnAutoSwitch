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
import android.os.Process
import android.provider.Settings
import org.json.JSONObject

/**
 * 常驻状态通知的渲染方。
 *
 * system_server 只算出「现在是什么状态」，然后把内容发一条显式广播过来，由 App 自己发通知。
 * 这样通知的归属包是本应用，Android 13 起通知栏左侧显示的才是应用图标（蓝底钥匙），
 * 而不是系统那个绿色小机器人。
 *
 * 广播这条通道会漏（后台受限、组件被停、开机未解锁），所以模块把内容同时写进
 * [ModuleContract.STATUS_STATE_KEY]。App 每次启动时调 [replay] 读一遍，缺什么补什么。
 */
class StatusNotifyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ModuleContract.ACTION_STATUS_NOTIFY) return
        // Android 14 起能拿到发送方 uid；低版本拿不到，只能靠「组件名不公开」兜着
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            sentFromUid != Process.SYSTEM_UID
        ) {
            return
        }
        if (!intent.getBooleanExtra(ModuleContract.EXTRA_STATUS_ON, false)) {
            cancel(context)
            return
        }
        render(
            context,
            intent.getStringExtra(ModuleContract.EXTRA_STATUS_TITLE).orEmpty(),
            intent.getStringExtra(ModuleContract.EXTRA_STATUS_TEXT).orEmpty(),
            intent.getStringExtra(ModuleContract.EXTRA_STATUS_PKG).orEmpty(),
            intent.getLongExtra(ModuleContract.EXTRA_STATUS_SINCE, 0L),
        )
    }

    companion object {

        /** 补发时的保鲜期：模块十分钟没更新过，说明状态早就变了，不如不发 */
        private const val REPLAY_MAX_AGE_MS = 10 * 60_000L

        private const val CHANNEL_ID = "vpn_autoswitch_status"

        /** 固定 id：状态变了就覆盖同一条，不会堆成一列 */
        private const val NOTIFICATION_ID = 0x5650

        /**
         * 读模块留下的那份内容，补一次通知。
         *
         * 只在 App 真正启动时调：用户打开应用，看到的第一眼就该是当前状态。
         */
        fun replay(context: Context) {
            val raw = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    ModuleContract.STATUS_STATE_KEY,
                )
            } catch (t: Throwable) {
                null
            }
            if (raw.isNullOrEmpty()) return

            val json = try {
                JSONObject(raw)
            } catch (t: Throwable) {
                return
            }
            if (!json.optBoolean("on", false)) {
                cancel(context)
                return
            }
            val at = json.optLong("at", 0L)
            if (at <= 0L || System.currentTimeMillis() - at > REPLAY_MAX_AGE_MS) return

            render(
                context,
                json.optString("title"),
                json.optString("text"),
                json.optString("pkg"),
                json.optLong("since", 0L),
            )
        }

        fun cancel(context: Context) {
            try {
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(NOTIFICATION_ID)
            } catch (t: Throwable) {
                // 忽略
            }
        }

        private fun render(context: Context, title: String, text: String, pkg: String, since: Long) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            // Android 13 起没有运行时授权时 notify 会被静默丢弃，提前返回省得白跑
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            try {
                ensureChannel(manager)
                val builder = Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_vpn_key)
                    .setContentTitle(title)
                    .setContentText(text)
                    // 内容可能在锁屏上被看到，别把 Wi-Fi 名称之类的东西挂上去
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(openTarget(context, pkg))
                if (since > 0L) {
                    // 交给系统自己走秒：模块不会为了「已连接 3 分钟」再发一次通知
                    builder.setWhen(since).setUsesChronometer(true)
                } else {
                    builder.setShowWhen(false)
                }
                manager.notify(NOTIFICATION_ID, builder.build())
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
         * 点通知直接进 VPN 客户端——连不上时第一反应是去客户端里看，而不是翻设置。
         *
         * 客户端没装、或拿不到启动入口时，退回本应用：桌面图标被隐藏时
         * activity-alias 是 disabled，`getLaunchIntentForPackage` 会返回 null，
         * 但 MainActivity 本身一直 exported，直接指过去就行。
         */
        private fun openTarget(context: Context, pkg: String): PendingIntent? = try {
            val client = pkg.takeIf { it.isNotEmpty() }
                ?.let { context.packageManager.getLaunchIntentForPackage(it) }
            val intent = client
                ?: context.packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
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
    }
}
