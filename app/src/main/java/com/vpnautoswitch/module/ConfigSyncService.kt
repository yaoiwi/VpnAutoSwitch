package com.vpnautoswitch.module

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 只为「把 App 进程拉起来」而存在的空服务。
 *
 * 模块（跑在 system_server 里）要读配置，只能查询本 App 暴露的 [ConfigProvider]，
 * 而 provider 能不能启动取决于这个进程在不在。开机那一刻它显然没起，系统会不会
 * 替我们去起它，各家 ROM 规矩不一样（自启动管理、后台限制），于是就有了
 * 「重启后必须先打开一次模块界面」这个老问题。
 *
 * 现在由模块在开机时 startService 这个服务把进程拉起：调用方是 system_server，
 * 不受后台启动限制，也能启动非 exported 组件。服务本身什么都不做，起来就自己停，
 * 进程随即变成普通的缓存进程。
 *
 * 安全问题：组件不导出，只有 system_server 那条路径能调；而且它不接收任何参数、
 * 不返回任何数据，能被调起最多算一次「被唤醒」。
 */
class ConfigSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
