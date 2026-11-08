package com.vpnautoswitch.module.system

import android.os.SystemClock
import com.vpnautoswitch.module.BuildConfig

// 事件入口：把 AMS 回调转成工作线程上的任务，初始化未完成时先排队

internal fun Engine.onForeground(packageName: String) {
    handler.post { dispatchForeground(packageName) }
}

/**
 * 应用刚开始启动（Activity 还没 resumed）时的探测。
 *
 * activityResumed 要等应用画出第一帧才回调，那时才开始连 VPN，
 * 用户看到的就是「应用已经打开了，网还没通」。这里在 startActivity 阶段先看一眼：
 * 是目标应用、VPN 又没连上，就提前把常驻设置写上，让系统在应用启动的同时 bind VpnService。
 */
internal fun Engine.onAppLaunching(packageName: String) {
    if (packageName.isEmpty()) return
    handler.post {
        // 用户自己点开了 VPN 客户端：他要用它，立刻撤掉强停后的自启抑制，
        // 否则他点「连接」会起不来，看起来像模块把客户端弄坏了
        if (packageName == ConfigRepo.get(context()).vpnPackage) ClientStartGuard.release()
        prewarm(packageName)
    }
}

internal fun Engine.prewarm(pkg: String) {
    val ctx = context() ?: return
    val cfg = ConfigRepo.getOrNull(ctx)
    if (cfg == null) {
        noteConfigMissing(ctx, pkg)
        return
    }
    if (!cfg.enabled || cfg.vpnPackage.isEmpty()) return
    if (pkg == cfg.vpnPackage || pkg == BuildConfig.APPLICATION_ID) return
    if (!cfg.targets.contains(pkg)) return
    // 开机早期注册的网络回调可能一直没来，这里按当下网络补一次判断：
    // 否则 lastTransport 是 0、wifiSuppressed 还是初始的 false，Wi-Fi 规则等于没配。
    probeNetwork(ctx)
    refreshWifiSuppression(ctx, cfg)
    if (automationPaused || userOff || wifiSuppressed || offAppForeground != null) return
    if (screenOffActive) return
    if (!isNetworkAllowed(cfg, pkg)) return
    // 已经连着就不用插手，resumed 之后走正常流程
    if (hasVpnTransport(ctx)) return

    // 同一次启动会连着走好几次 startActivity，同一个包只提前探一次
    val now = SystemClock.elapsedRealtime()
    if (pkg == lastPrewarmPkg && now - lastPrewarmAt < PREWARM_DEDUPE_MS) return
    lastPrewarmPkg = pkg
    lastPrewarmAt = now

    log(ctx, "$pkg launching -> prewarm")
    ensureVpn(ctx, cfg, pkg)
    // 点了又立刻退出时没人收尾，交给看门狗发现「没有目标存活」后再关
    startWatchdog()
}

/**
 * @param detail 触发这次回调的 AMS 方法与进程 pid，只写进日志，用来区分
 *   「真的退出了」和「AMS 清理残留记录」。null 表示调用方没提供。
 */
internal fun Engine.onProcessDied(packageName: String, detail: String? = null) {
    handler.post { dispatchProcessDied(packageName, detail) }
}

internal fun Engine.onTaskRemoved(packageName: String) {
    handler.post { dispatchTaskRemoved(packageName) }
}

internal fun Engine.dispatchForeground(packageName: String) {
    if (!booted) {
        enqueue(Engine.PendingEvent.Foreground(packageName))
        return
    }
    handleForeground(packageName)
    publishRuntime(context())
}

internal fun Engine.dispatchProcessDied(packageName: String, detail: String? = null) {
    if (!booted) {
        enqueue(Engine.PendingEvent.Died(packageName, detail))
        return
    }
    handleProcessDied(packageName, detail)
    publishRuntime(context())
}

internal fun Engine.dispatchTaskRemoved(packageName: String) {
    if (!booted) {
        enqueue(Engine.PendingEvent.TaskRemoved(packageName))
        return
    }
    handleTaskRemoved(packageName)
    publishRuntime(context())
}

internal fun Engine.enqueue(event: Engine.PendingEvent) {
    if (pendingEvents.size >= MAX_PENDING_EVENTS) pendingEvents.removeFirst()
    pendingEvents.addLast(event)
}

internal fun Engine.replayPendingEvents() {
    while (pendingEvents.isNotEmpty()) {
        when (val event = pendingEvents.removeFirst()) {
            is Engine.PendingEvent.Foreground -> handleForeground(event.packageName)
            is Engine.PendingEvent.Died -> handleProcessDied(event.packageName, event.detail)
            is Engine.PendingEvent.TaskRemoved -> handleTaskRemoved(event.packageName)
        }
    }
}
