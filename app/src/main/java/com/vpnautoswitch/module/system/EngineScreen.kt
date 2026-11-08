package com.vpnautoswitch.module.system

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager

// 锁屏断开：熄屏延时断、亮屏取消、解锁恢复

/**
 * 锁屏后延时断开。
 *
 * 手机放兜里通宵挂着 VPN，流量和电量都在漏。到点先确认屏幕真的还关着，
 * 再用和「划掉任务」同一套关闭链断掉，并置 [screenOffActive] 挡住自动重连。
 */
internal fun Engine.onScreenOff(ctx: Context) {
    val cfg = ConfigRepo.get(ctx)
    if (!cfg.enabled || !cfg.disconnectOnScreenOff || cfg.vpnPackage.isEmpty()) return
    if (automationPaused) return
    if (!hasVpnTransport(ctx)) return

    val minutes = cfg.screenOffMinutes.coerceIn(1, SCREEN_OFF_MAX_MINUTES)
    screenOffAt = SystemClock.elapsedRealtime()
    val token = ++screenOffToken
    log(ctx, "screen off -> disconnect in ${minutes}min")
    handler.postDelayed({
        if (token != screenOffToken) return@postDelayed
        val current = ConfigRepo.get(ctx)
        val elapsed = SystemClock.elapsedRealtime() - screenOffAt
        if (!EnginePolicy.shouldDisconnectOnScreenOff(
                enabled = current.disconnectOnScreenOff && current.enabled,
                screenOn = isScreenOn(ctx),
                hasTunnel = hasVpnTransport(ctx, fresh = true),
                elapsedMs = elapsed,
                configuredMinutes = current.screenOffMinutes,
            )
        ) {
            return@postDelayed
        }
        log(ctx, "screen off ${current.screenOffMinutes}min -> off")
        screenOffActive = true
        turnOff("screen off")
    }, minutes * 60_000L)
}

/**
 * 亮屏：作废还没到点的计时。
 *
 * 已经因为锁屏断开了就保持断开——亮屏多半只是看一眼时间，等解锁（[onScreenUnlocked]）再恢复，
 * 否则每次抬手看表都要重连一次 VPN。
 */
internal fun Engine.cancelScreenOffTimer(ctx: Context) {
    if (screenOffToken == 0) return
    screenOffToken++
    screenOffAt = 0L
    if (!screenOffActive) log(ctx, "screen on -> cancel the pending disconnect")
}

/** 解锁：解除锁屏断开状态，目标应用还活着就按原来的规则重新连上 */
internal fun Engine.onScreenUnlocked(ctx: Context) {
    if (!screenOffActive) return
    screenOffActive = false
    val cfg = ConfigRepo.get(ctx)
    if (!cfg.enabled || cfg.vpnPackage.isEmpty()) return
    log(ctx, "unlocked -> auto connect available again")
    if (aliveTargets.isNotEmpty() || isWifiOnActive(cfg)) {
        ensureVpn(ctx, cfg)
        startWatchdog()
    }
    publishRuntime(ctx)
}

internal fun Engine.isScreenOn(ctx: Context): Boolean = try {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    pm.isInteractive
} catch (t: Throwable) {
    // 读不到就当作亮着：宁可少断一次，也不要在用户正用手机时断开
    true
}

/** 用户是否已经解锁；读不到返回 null（当作「不知道」，不要据此做决定） */
internal fun Engine.isUserUnlocked(ctx: Context?): Boolean? = try {
    (ctx?.getSystemService(Context.USER_SERVICE) as? UserManager)?.isUserUnlocked
} catch (t: Throwable) {
    null
}

/**
 * 解锁完成（FBE 解密之后）。
 *
 * 开机时模块可能比用户解锁早得多，那时拉不起 App 进程，配置一直读不到，
 * 自动化就停在 idle——用户看到的是「重启后第一次打开目标应用没反应」。
 * 这里补一次启动，并把开机期间排队的前台/死亡事件重放掉。
 */
internal fun Engine.onUserUnlocked(ctx: Context) {
    lockedWaitLogged = false
    if (ConfigRepo.getOrNull(ctx) != null) {
        // 配置在锁屏期间已经从镜像拿到了，这里没什么可等的。但解锁前后网络状态
        // 往往变过（Wi-Fi 这时才真正可用），补一次评估，省得规则等到下次切换才认。
        handler.post {
            handleNetworkChanged()
            publishRuntime(ctx)
        }
        return
    }
    log(ctx, "user unlocked -> retry starting the module app")
    warmupConfig(ctx, 0)
    replayPendingEvents()
    publishRuntime(ctx)
}
