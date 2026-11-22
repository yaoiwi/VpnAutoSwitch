package com.vpnautoswitch.module

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 快捷设置磁贴：点一下暂停 / 恢复自动联动，长按打开 App。
 *
 * 磁贴运行在 App 进程，只负责发广播和读取状态；真正改设置、停看门狗由 system_server 里的模块完成。
 */
class VpnTileService : TileService() {

    private var observing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pauseObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        if (!observing) {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(ModuleContract.PAUSED_KEY),
                false,
                pauseObserver,
            )
            // 接管标记变化（VPN 开 / 关）也要重画：用户划掉应用后 VPN 关了，
            // 磁贴得跟着灭，不能只在拉下面板时才纠正
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(ModuleContract.ACTIVE_KEY),
                false,
                pauseObserver,
            )
            observing = true
        }
        updateTile()
    }

    override fun onStopListening() {
        if (observing) {
            contentResolver.unregisterContentObserver(pauseObserver)
            observing = false
        }
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (!moduleRunning()) {
            openApp()
            return
        }
        sendBroadcast(Intent(ModuleContract.ACTION_TOGGLE_PAUSE).setPackage("android"))
        // 模块写回状态通常几十毫秒，ContentObserver 也会触发；这里兜底刷新一次
        mainHandler.postDelayed({ updateTile() }, 400L)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = moduleRunning()
        val paused = isPaused()
        val vpnOn = isVpnOn()
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_vpn)
        // 亮不亮看「VPN 是否真的开着」，不看心跳：模块一直在跑，心跳永远是新鲜的，
        // 用它当状态的话，划掉目标应用、VPN 已经断开，磁贴还会一直亮着。
        tile.state = if (running && vpnOn && !paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !running -> getString(R.string.tile_unavailable)
                paused -> getString(R.string.tile_paused)
                vpnOn -> getString(R.string.tile_running)
                // 模块在跑、自动化也开着，只是当前没有目标应用在用 → VPN 故意关着
                else -> getString(R.string.tile_idle)
            }
        }
        tile.updateTile()
    }

    /**
     * 磁贴亮不亮。
     *
     * 以隧道为准：接管标记在用户手动断开客户端之后还会留一阵，只看它会一直亮着写「运行中」。
     * 标记只作为「查不到网络信息」时的兜底——个别 ROM 会把 VPN 网络从普通应用眼前藏起来，
     * 那种情况下宁可相信模块自己写的标记，也不要让磁贴永远灭着。
     */
    private fun isVpnOn(): Boolean = tunnelUp() ?: markedOn()

    /** @return null 表示查不到（没有权限、或系统异常），调用方退回标记判断 */
    private fun tunnelUp(): Boolean? = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            null
        } else {
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }
    } catch (t: Throwable) {
        null
    }

    private fun markedOn(): Boolean = try {
        Settings.Secure.getInt(contentResolver, ModuleContract.ACTIVE_KEY, 0) == 1
    } catch (t: Throwable) {
        false
    }

    private fun isPaused(): Boolean = try {
        Settings.Secure.getInt(contentResolver, ModuleContract.PAUSED_KEY, 0) == 1
    } catch (t: Throwable) {
        false
    }

    /** 15 秒心跳，60 秒内没有新心跳就认为模块没在运行。 */
    private fun moduleRunning(): Boolean {
        val heartbeat = try {
            Settings.Secure.getLong(contentResolver, "vpn_autoswitch_heartbeat", 0L)
        } catch (t: Throwable) {
            0L
        }
        val now = System.currentTimeMillis()
        return heartbeat > 0L && now >= heartbeat && now - heartbeat <= 60_000L
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
