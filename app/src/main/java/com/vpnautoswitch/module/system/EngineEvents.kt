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

// ---------------------------------------------------------------- 事件

internal fun Engine.handleForeground(pkg: String) {
    val ctx = context() ?: return
    beat(ctx)
    val cfg = ConfigRepo.getOrNull(ctx)
    if (cfg == null) {
        // 「读不到配置」和「用户没配置」是两件事。这里绝不能往下走：
        // 下面的 `vpnPackage.isEmpty()` 分支会把正在用的 VPN 当成「模块已停用」直接关掉。
        noteConfigMissing(ctx, pkg)
        return
    }
    // 上一次前台不是它 => 这次是「重新打开」，而不是应用内部换页 / 弹窗后 resume。
    // 从 VPN 客户端本身退回来也不算：用户多半是刚在里面点了断开，别立刻给他连回去。
    val freshOpen = EnginePolicy.isFreshOpen(lastForeground, pkg, cfg.vpnPackage)
    lastForeground = pkg
    val foregroundKind = EnginePolicy.foregroundKind(pkg, cfg.targets, cfg.offTargets)

    if (!cfg.enabled || cfg.vpnPackage.isEmpty()) {
        offAppForeground = null
        startToken++
        if (VpnSwitcher.isActive(ctx.contentResolver)) {
            aliveTargets.clear()
            turnOff("module disabled")
        }
        return
    }

    if (automationPaused) return

    // 打开「关闭 VPN」列表里的应用：VPN 只要还连着就断开，
    // 并且在它退出之前一直压制自动开启（避免看门狗 5 秒后又把 VPN 拉起来）。
    if (foregroundKind == EnginePolicy.ForegroundKind.OFF_TARGET) {
        val first = offAppForeground != pkg
        offAppForeground = pkg
        offToken++
        // 即使 VPN 当前没连上，也要让 600ms 后准备启动的服务失效
        startToken++
        if (first) log(ctx, "$pkg -> foreground (off trigger)")
        if (VpnSwitcher.isActive(ctx.contentResolver) || hasVpnTransport(ctx)) {
            turnOff("opened $pkg")
        } else {
            stopWatchdog()
            if (first) log(ctx, "vpn already off, keep off while $pkg is open")
        }
        return
    }

    if (foregroundKind != EnginePolicy.ForegroundKind.TARGET) return

    // 同 prewarm：回调没来时按当下网络重算一次，别用上一次留下的结论
    probeNetwork(ctx)
    refreshWifiSuppression(ctx, cfg)

    // 当前 Wi-Fi 在「关闭 VPN」列表里：不自动开启
    if (wifiSuppressed) {
        log(ctx, "$pkg -> foreground, but wifi rule keeps vpn off")
        return
    }

    if (offAppForeground != null) {
        offAppForeground = null
        log(ctx, "back to $pkg, auto enable resumed")
    }

    // 目标又回到前台，取消上一次「进程结束」排队的关闭
    offToken++
    aliveTargets.add(pkg)
    lastForegroundTarget = pkg
    // 必须和 lastForegroundTarget 一起更新：切到桌面再回来时 lastForeground 会变成桌面，
    // 若用它的时间戳，会把「用了很久后真的退出」误判成刚打开
    lastForegroundSeenAt = SystemClock.elapsedRealtime()

    if (userOff) {
        if (!EnginePolicy.shouldResumeAfterUserOff(freshOpen)) {
            // 应用内部的页面切换 / 从系统面板返回，不算「重新打开」，继续让 VPN 保持关闭
            return
        }
        userOff = false
        // 新一轮「用户主动打开」，抖动重试次数重新开始算
        flapRetries = 0
        log(ctx, "$pkg reopened -> auto enable resumed")
    }
    // 是「重新打开」就当作一次全新的连接请求，别被上一轮的掉线状态挡住
    if (freshOpen) wasConnected = false

    logForeground(ctx, pkg, freshOpen)
    if (isNetworkAllowed(cfg, pkg)) {
        ensureVpn(ctx, cfg, pkg)
    } else {
        log(ctx, "network type disabled, skip enabling")
    }
    startWatchdog()
}

/**
 * 「xxx -> foreground」只在有信息量时记一条。
 *
 * 应用内部换页、弹窗、从通知栏返回都会各来一次 activityResumed，
 * 每来一次记一条，60 行的缓冲区很快就被刷满，真正有用的失败信息反而被挤掉。
 */
internal fun Engine.logForeground(ctx: Context, pkg: String, freshOpen: Boolean) {
    val now = SystemClock.elapsedRealtime()
    val repeated = pkg == lastLoggedForegroundPkg && now - lastForegroundLogAt < FOREGROUND_LOG_MS
    // 不是「重新打开」、VPN 也还连着：这一条没有信息量，跳过
    if (repeated || (!freshOpen && hasVpnTransport(ctx))) return
    lastLoggedForegroundPkg = pkg
    lastForegroundLogAt = now
    log(ctx, "$pkg -> foreground")
}

internal fun Engine.handleProcessDied(pkg: String, detail: String? = null) {
    if (automationPaused) return
    if (pkg == offAppForeground) {
        offAppForeground = null
        log(context(), "$pkg (off trigger) exited")
    }
    // 刚回到前台的进程不可能已经退出。AMS 清理上一轮残留 ProcessRecord 时也会回调
    // 这个入口，包名完全正确，只是死的是上一次的进程；Chrome 这类多进程应用每次
    // 从被杀状态拉起都会走一遍，日志里就是 launching 和 process died 挤在同一秒。
    // 以前直接把这种事件当成「用户退了」，VPN 刚连上就被关掉。
    val ctx0 = context()
    val now = SystemClock.elapsedRealtime()
    if (EnginePolicy.isStaleProcessDeath(
            pkg = pkg,
            lastForeground = lastForegroundTarget,
            lastForegroundAtMs = lastForegroundSeenAt,
            now = now,
            windowMs = DEATH_STALE_WINDOW_MS,
        )
    ) {
        val since = (now - lastForegroundSeenAt).coerceAtLeast(0)
        val extra = detail?.let { " / $it" } ?: ""
        // 同一次前台切换里，同一个包会被连着回调好几次（多进程、上一轮残留记录各来一遍），
        // 这些行只差 pid。一秒内留第一条即可，否则一次切应用刷十几行，
        // 把紧随其后的 reconcile 结论全埋掉。
        val lastLog = staleDeathLogAt[pkg] ?: 0L
        if (EnginePolicy.shouldLogStaleDeath(now, lastLog, STALE_DEATH_LOG_MS)) {
            staleDeathLogAt[pkg] = now
            log(ctx0, "$pkg -> process died ignored（${since}ms 前刚回到前台，判定为残留记录$extra）")
        }
        // 不把它当成退出：进程还在，存活集合也不能动
        return
    }
    val wasForeground = lastForegroundTarget == pkg
    // 别的目标还停在前台时不能收会话，否则正在用的那个会被一起断掉
    val anotherInFront = EnginePolicy.anotherTargetInForeground(lastForeground, pkg, aliveTargets)
    val removed = aliveTargets.remove(pkg)
    if (!removed) return
    val ctx = context()
    log(ctx, "$pkg -> process died${detail?.let { " ($it)" } ?: ""}")
    if (lastForeground == pkg) lastForeground = null
    if (wasForeground) lastForegroundTarget = null
    // 按用户选的口径判断要不要收会话（只断当前 / 任意一个 / 全部退出）。
    // 如果它只是被系统回收后重启，下面的 1.5 秒防抖里会重新回到前台并取消这次关闭。
    val dismissMode = ctx?.let { ConfigRepo.get(it).dismissMode } ?: Config.DISMISS_CURRENT
    if (EnginePolicy.shouldEndSessionOnLeave(
            removedWasForeground = wasForeground,
            remainingTargets = aliveTargets.size,
            dismissMode = dismissMode,
            anotherTargetInForeground = anotherInFront,
        )
    ) {
        aliveTargets.clear()
        log(
            ctx,
            if (wasForeground) "other targets only in background -> end session"
            else "a background target left -> end session",
        )
    }
    if (!EnginePolicy.shouldScheduleOffAfterDeath(true, aliveTargets.size)) return
    // 应用有时只是进程重启（被系统回收后立刻恢复），等一下再决定要不要关，
    // 否则会出现「刚连上就被关、关了又重连」的抖动。
    scheduleOff(ctx, "$pkg died", OFF_SLOW_MS)
}

/**
 * 用户从最近任务划掉应用。和进程死亡不同，这时进程可能还活着（例如有前台服务）。
 * 只有在开关开启、目标确实在存活集合里时才接管，避免任务恢复 / 分屏误判。
 */
internal fun Engine.handleTaskRemoved(pkg: String) {
    val ctx = context() ?: return
    val cfg = ConfigRepo.get(ctx)
    val verdict = EnginePolicy.taskRemovalVerdict(
        enabled = cfg.enabled,
        featureEnabled = cfg.disconnectOnTaskRemoved,
        paused = automationPaused,
        offAppForeground = offAppForeground != null,
        wifiSuppressed = wifiSuppressed,
        target = cfg.targets.contains(pkg),
        aliveTarget = aliveTargets.contains(pkg),
    )
    if (verdict != EnginePolicy.TaskRemovalVerdict.HANDLE) {
        logTaskRemovalSkip(ctx, pkg, verdict)
        return
    }

    // 任务刚被移除，可引擎里它还是前台应用 —— 移除的只能是同一应用的**另一条**任务记录：
    // 浏览器每开一个窗口都会新建 task，旧的那条被系统清掉时同样回调 removeTask，
    // 包名完全正确。照单全收的结果就是日志里那种「打开 → 两秒后 task removed → 关 VPN」
    // 的循环，用户正在浏览，VPN 却一次次掉。
    //
    // 真划掉时桌面的 resume 事件一般紧跟着这个回调，所以这里先挂一笔，两秒后再看：
    // 那时它已经不在前台，就按正常退出处理，整体只是晚了 2 秒。
    if (EnginePolicy.isStaleTaskRemoval(pkg, lastForeground)) {
        log(ctx, "$pkg -> task removed ignored（仍是前台应用，判定为残留任务记录）")
        scheduleTaskRecheck(ctx, cfg, pkg)
        return
    }
    // 刚把客户端界面拉到前台：目标应用是被我们挤下去的，不是用户离开。
    // 这里不复查——界面会停在前面几秒，复查只会得到「它确实不在前台」而照样关掉。
    if (EnginePolicy.isUiInducedTaskRemoval(
            now = SystemClock.elapsedRealtime(),
            uiShownAt = uiShownAt,
            windowMs = UI_TASK_REMOVAL_GUARD_MS,
        )
    ) {
        log(ctx, "$pkg -> task removed ignored（刚打开客户端界面，判定为被挤到后台）")
        return
    }
    log(ctx, "$pkg -> task removed")
    applyTaskRemoval(ctx, cfg, pkg)
}

/** 同一包同一原因多久内不重复记：用户反复上滑会连着触发回调，不节流会被刷满 */
private const val TASK_SKIP_LOG_MS = 60_000L

/**
 * 划掉任务被跳过时，把原因写出来。
 *
 * 以前这里是一串 `&&` 直接 return，日志里什么都没有；用户以为「模块没反应」，
 * 其实是「划掉任务即断开」没开、或者这个包不在目标列表——都是自己改一下就好。
 */
internal fun Engine.logTaskRemovalSkip(
    ctx: Context,
    pkg: String,
    verdict: EnginePolicy.TaskRemovalVerdict,
) {
    val now = SystemClock.elapsedRealtime()
    val key = "$pkg/${verdict.name}"
    val last = taskSkipLoggedAt[key]
    if (last != null && now - last < TASK_SKIP_LOG_MS) return
    taskSkipLoggedAt[key] = now
    log(ctx, "$pkg -> task removed ignored（${EnginePolicy.taskRemovalSkipReason(verdict)}）")
}

/** 残留任务记录的复查：两秒后该包仍不在前台，才按划掉处理 */
internal fun Engine.scheduleTaskRecheck(ctx: Context, cfg: Config, pkg: String) {
    val token = ++taskRecheckToken
    handler.postDelayed({
        if (token != taskRecheckToken) return@postDelayed
        // 两秒过去它还在前台：确实是同一应用的另一条任务记录被清掉，什么都不做
        if (lastForeground == pkg) return@postDelayed
        val verdict = EnginePolicy.taskRemovalVerdict(
            enabled = cfg.enabled,
            featureEnabled = cfg.disconnectOnTaskRemoved,
            paused = automationPaused,
            offAppForeground = offAppForeground != null,
            wifiSuppressed = wifiSuppressed,
            target = cfg.targets.contains(pkg),
            aliveTarget = aliveTargets.contains(pkg),
        )
        if (verdict != EnginePolicy.TaskRemovalVerdict.HANDLE) {
            logTaskRemovalSkip(ctx, pkg, verdict)
            return@postDelayed
        }
        log(ctx, "$pkg -> task removed（复查确认已离开前台）")
        applyTaskRemoval(ctx, ConfigRepo.get(ctx), pkg)
    }, TASK_RECHECK_MS)
}

internal fun Engine.applyTaskRemoval(ctx: Context, cfg: Config, pkg: String) {
    val wasForeground = lastForegroundTarget == pkg
    // 另一目标正停在前台时保留会话，避免把用户还在用的那个一起断掉
    val anotherInFront = EnginePolicy.anotherTargetInForeground(lastForeground, pkg, aliveTargets)
    aliveTargets.remove(pkg)
    if (lastForeground == pkg) lastForeground = null
    if (wasForeground) lastForegroundTarget = null
    // 划掉的正好是刚在用的那个，或者开着「划掉任意目标即断开」：其它目标只是挂在后台的进程，
    // 算这次会话结束。否则它们的存活会让 VPN 一直被拽住，划掉当前应用也不断开。
    if (EnginePolicy.shouldEndSessionOnLeave(
            removedWasForeground = wasForeground,
            remainingTargets = aliveTargets.size,
            dismissMode = cfg.dismissMode,
            anotherTargetInForeground = anotherInFront,
        )
    ) {
        aliveTargets.clear()
        log(
            ctx,
            if (wasForeground) "other targets only in background -> end session"
            else "a background target was dismissed -> end session",
        )
    }
    if (EnginePolicy.shouldScheduleOffAfterDeath(true, aliveTargets.size)) {
        scheduleOff(ctx, "$pkg task removed")
    }
}

internal fun Engine.scheduleOff(ctx: Context?, reason: String, debounceMs: Long = OFF_FAST_MS) {
    val context = ctx ?: return
    val token = ++offToken
    handler.postDelayed({
        if (token != offToken) return@postDelayed
        val cfg = ConfigRepo.get(context)
        val wifiOnActive = cfg.enabled && isWifiOnActive(cfg)
        if (!EnginePolicy.shouldRunDelayedOff(
                token,
                offToken,
                aliveTargets.isNotEmpty(),
                wifiOnActive,
            )
        ) return@postDelayed
        turnOff(reason)
    }, debounceMs)
}

/**
 * 停止并稍后重启客户端的 VpnService。
 *
 * 600ms 窗口里可能又打开了「关闭 VPN」应用、切了网络、改了 VPN 包或进入手动关闭状态，
 * 所以执行前必须重新读取配置并逐项确认，不能只看启动序号。
 */
internal fun Engine.scheduleDelayedServiceStart(ctx: Context, cfg: Config) {
    if (cfg.vpnPackage.isEmpty()) return
    val scheduledPackage = cfg.vpnPackage
    pendingExternalMode = "service"
    // 只有本轮第一次启动才先停掉残留实例：这时客户端可能还挂着上一次的会话，
    // 停一下再起才能干净地建新隧道。重试时绝不能再停——第二次 stopService 会把
    // 第一次刚刚开始建立的隧道推倒，日志 17:52:53→17:53:10 的 17 秒就是这么来的
    // （三次「restart VPN service」，每次都先 stop 再 start）。
    if (externalAttempts <= 1) {
        ExternalControl.stopVpnService(ctx, scheduledPackage)
    }
    val token = ++startToken
    handler.postDelayed({
        val current = ConfigRepo.get(ctx)
        val wifiOnActive = current.enabled && isWifiOnActive(current)
        // 排队的这 250ms 里隧道可能已经通了（客户端自己恢复连接、或上一次启动终于生效）。
        // 这时再发一次启动，v2rayNG 会把刚建好的核心推倒重建，表现就是「连上五秒又掉」。
        val tunnelUp = hasVpnTransport(ctx)
        val allowed = EnginePolicy.shouldRunDelayedStart(
            token = token,
            currentToken = startToken,
            scheduledVpnPackage = scheduledPackage,
            enabled = current.enabled,
            currentVpnPackage = current.vpnPackage,
            offAppForeground = offAppForeground != null,
            userOff = userOff,
            wifiSuppressed = wifiSuppressed,
            networkAllowed = isNetworkAllowed(current),
            hasAliveTarget = aliveTargets.isNotEmpty(),
            wifiOnActive = wifiOnActive,
            hasTunnel = tunnelUp,
            // 「目标刚启动」这一段里 aliveTargets 还是空的：只要它已经是当前前台，
            // 就说明用户确实要用它，不该因为拿不到进程而取消这次启动
            scheduledInFront = lastForeground == scheduledPackage ||
                lastForegroundTarget == scheduledPackage,
        )
        if (!allowed) {
            log(
                ctx,
                if (tunnelUp) {
                    "delayed service start skipped: tunnel is already up"
                } else {
                    "delayed service start cancelled (state changed)"
                },
            )
            return@postDelayed
        }
        ExternalControl.startVpnService(ctx, scheduledPackage)
        // 原来等 600ms 是为了让 stopService 先落地；250ms 足够，用户感知上是半秒差距
    }, 250L)
}

