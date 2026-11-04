package com.vpnautoswitch.module.system

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import com.vpnautoswitch.module.BuildConfig
import com.vpnautoswitch.module.Config
import com.vpnautoswitch.module.ModuleContract
import de.robv.android.xposed.XposedBridge

internal fun Engine.watchdogTick() {
    val ctx = context() ?: return
    val cfg = ConfigRepo.getOrNull(ctx)
    if (cfg == null) {
        // 配置读不到就先不动：下面第一段判断会把空配置当成「模块已停用」，
        // 直接把用户正在用的 VPN 关掉，而且连一句日志都不留
        noteConfigMissing(ctx, "watchdog")
        handler.postDelayed(watchdog, RECONCILE_INTERVAL_MS)
        return
    }

    // 这一拍只读一次常驻状态：原来 1~3 个分支各读一遍 Settings.Secure
    val active = VpnSwitcher.isActive(ctx.contentResolver)
    // 进程列表同理：一次 tick 里最多用到两处，原来各构造一遍
    var procs: List<ActivityManager.RunningAppProcessInfo>? = null
    var procsLoaded = false
    fun processes(): List<ActivityManager.RunningAppProcessInfo>? {
        if (!procsLoaded) {
            procs = runningProcesses(ctx)
            procsLoaded = true
        }
        return procs
    }

    if (!cfg.enabled || cfg.vpnPackage.isEmpty()) {
        if (active) turnOff("module disabled")
        aliveTargets.clear()
        watchdogRunning = false
        return
    }

    if (automationPaused) {
        watchdogRunning = false
        return
    }

    // 锁屏断开后不再对账：目标进程还在，但用户就是希望它保持断开
    if (screenOffActive) {
        watchdogRunning = false
        return
    }

    if (wifiSuppressed) {
        if (active) turnOff("wifi off")
        watchdogRunning = false
        return
    }

    if (aliveTargets.isEmpty()) {
        // 兜底：目标进程都没了，VPN 却还开着（例如进程死亡事件被系统吞了），
        // 只要这轮是模块拉起的、且没有「打开即关闭」的应用在压制，就直接关掉。
        if (autoSession && offAppForeground == null && (active || hasVpnTransport(ctx))) {
            log(ctx, "reconcile: no target alive but vpn still on -> schedule off")
            scheduleOff(ctx, "no target alive")
        }
        watchdogRunning = false
        return
    }

    // 「打开即关闭」的应用还活着就不再拉起 VPN；它退出后恢复自动开启
    // 用户手动关掉了 VPN：他要求保持关闭，看门狗不再轮询
    if (userOff) {
        watchdogRunning = false
        return
    }

    val suppressor = offAppForeground
    if (suppressor != null) {
        if (isProcessAlive(ctx, suppressor, processes())) {
            watchdogRunning = false
            return
        }
        offAppForeground = null
        log(ctx, "$suppressor is gone, auto vpn available again")
    }

    if (!isNetworkAllowed(cfg)) {
        if (active) {
            log(ctx, "reconcile: network type disabled -> off")
            turnOff("network type disabled")
            // turnOff 已经停掉并移除了本看门狗，不能再把自己排回去
            return
        }
        handler.postDelayed(watchdog, RECONCILE_INTERVAL_MS)
        return
    }

    if (!EnginePolicy.shouldEnsureForProcessState(anyTargetAlive(ctx, processes()))) {
        log(ctx, "reconcile: target process gone -> off")
        aliveTargets.clear()
        scheduleOff(ctx, "watchdog")
        watchdogRunning = false
    } else {
        // 只有「隧道确实没通、也没接管」才值得记一行：接管标记还没补上的那一两拍里，
        // 隧道其实已经通了，写成「vpn is off」会把人带偏（一口气刷三十条也是这么来的）
        if (!active && !ExternalControl.isKnown(ctx, cfg.vpnPackage) && !hasVpnTransport(ctx)) {
            log(ctx, "reconcile: vpn is off but target alive -> enable")
        }
        ensureVpn(ctx, cfg)
        // 没连上就快步重试：原来的固定 5 秒一拍会让「第一次没成功」再白等 5 秒
        val connected = hasVpnTransport(ctx)
        connectingTicks = if (connected) 0 else connectingTicks + 1
        handler.postDelayed(
            watchdog,
            EnginePolicy.reconcileDelay(
                connected = connected,
                connectingTicks = connectingTicks,
                maxFastTicks = RECONCILE_CONNECTING_TICKS,
                fastMs = RECONCILE_CONNECTING_MS,
                normalMs = RECONCILE_INTERVAL_MS,
            ),
        )
    }
}


internal fun Engine.startWatchdog() {
    if (watchdogRunning) return
    watchdogRunning = true
    // 新一轮连接从快节奏开始：上一轮可能停在 5 秒的慢拍上（计数没清）
    connectingTicks = 0
    // 首拍用连接窗口的短间隔：刚发起连接就复核一次，不用干等 5 秒
    handler.postDelayed(watchdog, RECONCILE_CONNECTING_MS)
}

internal fun Engine.stopWatchdog() {
    watchdogRunning = false
    connectingTicks = 0
    handler.removeCallbacks(watchdog)
}

/** 读一次进程列表。看门狗一拍里会用到两处，由调用方复用同一份快照 */
internal fun Engine.runningProcesses(ctx: Context): List<ActivityManager.RunningAppProcessInfo>? {
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
    return try {
        am.runningAppProcesses
    } catch (t: Throwable) {
        null
    }
}

internal fun Engine.isProcessAlive(
    ctx: Context,
    pkg: String,
    snapshot: List<ActivityManager.RunningAppProcessInfo>? = null,
): Boolean {
    val processes = snapshot ?: runningProcesses(ctx) ?: return false
    return processes.any { it.processName == pkg || it.processName.startsWith("$pkg:") }
}

/**
 * @return true 还在运行；false 已经结束；null 无法判断（列表被系统裁剪时）
 */
internal fun Engine.anyTargetAlive(
    ctx: Context,
    snapshot: List<ActivityManager.RunningAppProcessInfo>? = null,
): Boolean? {
    val processes = snapshot ?: runningProcesses(ctx) ?: return null

    if (processes.none { it.uid != Process.myUid() }) return null

    return aliveTargets.any { pkg ->
        processes.any { it.processName == pkg || it.processName.startsWith("$pkg:") }
    }
}

