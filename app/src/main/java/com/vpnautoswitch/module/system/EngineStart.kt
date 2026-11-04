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

// ---------------------------------------------------------------- VPN 控制

/**
 * @param origin 触发这次开启意图的包名。只用于「关闭被取消」那条日志：
 *   以前取 lastForeground，从桌面点开应用时那里记的是桌面，日志会写成
 *   「off cancelled: com.android.launcher 回到前台」，看起来像被桌面打断了。
 */
internal fun Engine.ensureVpn(ctx: Context, cfg: Config, origin: String? = null) {
    // 有开启意图就说明现在要用了：先撤掉强停后那段「不许客户端自启」的窗口，
    // 否则下面重新启动 VpnService 会被自己的闸门挡掉
    ClientStartGuard.release()
    if (automationPaused) return
    // 锁屏断开期间不自动重连，等用户解锁后由 onScreenUnlocked 恢复
    if (screenOffActive) return
    // 任何新的授权开启意图都让此前排队的关闭验证链失效
    offGeneration++
    // 关闭链还在跑的时候来了开启意图：多半是「划掉目标应用 A，切到目标应用 B」。
    // 这一轮整体作废——排队的复查、守卫、预热，还有「已关闭」的提示都要撤掉：
    // 隧道可能从头到尾没断过，先说「已关闭」再什么都不说，比不提示更让人糊涂。
    val cancelledOff = closeChainRunning
    if (cancelledOff) {
        offToastPending = false
        pendingPrewarm = null
        log(
            ctx,
            "off cancelled: ${origin ?: lastForegroundTarget ?: lastForeground ?: "目标应用"} 回到前台，" +
                "本轮不做关闭",
        )
    }
    // 关闭意图已经被开启取代：主链排队的复查都失效了，busy 标志和守卫请求也要作废，
    // 否则守卫会在兜底时长后跳出来，把用户刚连上的 VPN 又关掉一次
    closeChainRunning = false
    closeChainStartedAt = 0L
    pendingGuard = null
    offGuardToken++
    guardUntil = 0L
    // 新的开启意图说明这条隧道归模块管了，之后的关闭可以按正常力度来
    chainForceStopAllowed = true
    val settingPointsToUs = VpnSwitcher.currentAlwaysOn(ctx) == cfg.vpnPackage
    val active = VpnSwitcher.isActive(ctx.contentResolver)
    val connected = hasVpnTransport(ctx)

    if (connected) {
        // 隧道通了，但不是模块这一轮写的设置（客户端自恢复、用户手动连上）。
        // 不补接管标记的话，看门狗会一直判「没接管」，关闭时还会跳过 disable，
        // 把 always_on_vpn_app 留在原地，下次停完 service 又被系统拉回来。
        val adopted = VpnSwitcher.adopt(ctx)
        // 已经连上了，说明用户「保持关闭」的意图已经结束（例如他自己又手动连上了）
        userOff = false
        val nowUp = SystemClock.elapsedRealtime()
        if (connectedAt == 0L) connectedAt = nowUp
        // 连稳了才清掉抖动重试的计数：否则「连上—掉—连上—掉」会一直重连下去。
        // 「稳」的门槛要比抖动窗口长，否则窗口内的掉落永远算不完次数。
        if (nowUp - connectedAt >= EnginePolicy.STABLE_CONNECTION_MS) flapRetries = 0
        // 从「开始动手」到「看到隧道」，就是用户感知的「打开应用多久有网」。
        // 刚连上就掉的抖动这里也会记一次，随后的重连会覆盖成真实值。
        if (connectStartedAt != 0L) {
            val cost = nowUp - connectStartedAt
            connectStartedAt = 0L
            RuntimeDiagnostics.publishTiming(ctx, "on", cost)
        }
        // 设置仍指向我们，说明这轮是模块接管的，看门狗可以负责关掉它
        if (active || settingPointsToUs || adopted) autoSession = true
        if (failureStreak > 0) log(ctx, "vpn connected (after $failureStreak failed attempts)")
        // 只有「确实靠外部手段连上」才记录，always-on 能连上的客户端不该被误判
        pendingExternalMode?.let { mode ->
            ExternalControl.remember(ctx, cfg.vpnPackage, mode)
            log(ctx, "external mode '$mode' works for ${cfg.vpnPackage}, remembered")
        }
        pendingExternalMode = null
        if (!connectedNotified) {
            connectedNotified = true
            when {
                // 这一轮关闭刚被「目标应用回到前台」取消，VPN 从头到尾没断过：
                // 状态没变，不提示「已开启」也不算漏话
                cancelledOff ->
                    log(ctx, "off cancelled but vpn stayed up -> skip the 'enabled' toast")
                // 刚断开的一两秒内又连上：多半是客户端自动重连，或系统按常驻设置把它拉了回来。
                // 用户刚点的是「关闭」，这时提示「已开启」与他的操作正好相反，宁可不说。
                !EnginePolicy.isFreshOff(SystemClock.elapsedRealtime() - lastOffAt) ->
                    toast(ctx, "VPN 已开启", cfg.toastEnabled)
                else ->
                    log(ctx, "vpn came back right after off -> skip the 'enabled' toast")
            }
        }
        failureStreak = 0
        failureLogged = false
        tileAttempts = 0
        reapplyCount = 0
        externalAttempts = 0
        initWaitLogged = false
        lastStartAt = 0L
        wasConnected = true
        return
    }

    // 从「已连接」掉下来：可能是用户手动关了（磁贴 / 客户端里断开），也可能是系统掐断。
    // 按你的要求，这时不自动重连——保持断开，等他重新打开目标应用时再连。
    if (wasConnected) {
        val now = SystemClock.elapsedRealtime()
        val connectedFor = if (connectedAt == 0L) Long.MAX_VALUE else now - connectedAt
        wasConnected = false
        connectedAt = 0L
        // 抖动重试靠看门狗下一拍发起。但看门狗在「连不上」时会累计 connectingTicks，
        // 满 8 拍就把节奏从 900ms 退到 5 秒——掉线时这个计数还留着，
        // 于是补偿重发被拖到 5 秒后（日志 15:14:47 掉、15:14:52 才重发）。
        // 掉线就等于重新开始一轮，计数清零，重发能在一拍内发出。
        connectingTicks = 0

        // 刚连上就掉：先怀疑「这次没连成」，不要当成用户手动断开。
        // 否则 userOff 一挂上，用户得退出应用再打开才会重连（日志里干等几十秒就是这个）。
        // 目标应用还停在前台时窗口放到 6 秒：用户明明开着应用，掉落几乎不会是手动断开，
        // 而 2 秒窗口常被采样误差顶到边界（15:02:57 连上、15:02:59 掉就属于这种）。
        if (autoSession && EnginePolicy.isStartupFlap(
                connectedForMs = connectedFor,
                retries = flapRetries,
                maxRetries = MAX_FLAP_RETRIES,
                windowMs = if (aliveTargets.isNotEmpty()) {
                    EnginePolicy.TARGET_FLAP_MS
                } else {
                    EnginePolicy.STARTUP_FLAP_MS
                },
            )
        ) {
            flapRetries++
            externalAttempts = 0
            // 补偿重试不走 6 秒的外部控制冷却：把上次动手时刻往前挪，几百毫秒后就能再试
            lastApplyAt = EnginePolicy.rewindForRetry(
                now = now,
                lastApplyAt = lastApplyAt,
                cooldownMs = EXTERNAL_ONLY_GUARD_MS,
                retryMs = FLAP_RETRY_MS,
            )
            log(
                ctx,
                "vpn dropped after ${connectedFor}ms -> retry " +
                    "($flapRetries/$MAX_FLAP_RETRIES)",
            )
            // 看门狗继续跑，下一拍就会重新发起连接
            return
        }

        externalAttempts = 0
        reapplyCount = 0
        connectedNotified = false
        autoSession = false
        userOff = true
        startToken++
        stopWatchdog()
        lastOffAt = now
        connectStartedAt = 0L
        // 常驻设置还指着客户端时，系统/客户端往往几秒内就把它重新拉起来，
        // 用户手动点断开，看到的却是「断一下又连上」。这里按关闭时的同一套还原逻辑收尾。
        if (active || settingPointsToUs) {
            val cleared = if (active) {
                VpnSwitcher.disable(ctx, cfg.restoreOriginal, cfg.vpnPackage)
            } else {
                VpnSwitcher.clearIfPointsTo(ctx, cfg.vpnPackage)
            }
            log(ctx, "vpn dropped, always-on cleared=$cleared so it stays off")
        }
        // 把「活了多久」写出来：下次再判成手动断开时，能直接看出是 2.1 秒还是 30 分钟，
        // 不用再靠时间戳反推
        val lifeText =
            if (connectedFor == Long.MAX_VALUE) "" else " after ${connectedFor}ms"
        log(ctx, "vpn dropped$lifeText -> stay off until a target app is reopened")
        return
    }

    // 已经被用户手动关掉，等下次打开目标应用
    if (userOff) return

    // 从这里往下模块真的要动手了，记下这轮是模块拉起的
    autoSession = true

    val now = SystemClock.elapsedRealtime()
    // 计时的起点放在「模块决定动手」这一刻：前面被冷却挡住的那些调用不算，
    // 否则「打开应用多久有网」会把这半秒的等待也算进去
    if (connectStartedAt == 0L) {
        connectStartedAt = now
        // 新的一轮连接：启动宽限和「已提示过等待」的节流都从这一刻重新算
        initWaitLogged = false
        lastStartAt = 0L
    }

    val externalMode = ExternalControl.modeOf(ctx, cfg.vpnPackage)

    // 已经确认这个客户端不响应 always-on：不再写常驻 VPN 设置（避免 unbind 干扰）
    if (externalMode != null) {
        // 上一次关闭只要走过强停，这个包就处在 stopped 状态，系统会拒绝为它启动
        // 任何组件——startForegroundService 直接返回 null（日志 start VPN service
        // rejected）。以前只在开了「直接强制停止」时才清标记，于是普通用户这边
        // 要等十几秒的延迟预热才恢复：日志 00:48:25 强停，00:48:38 才有隧道。
        // 清标记本身是幂等的（Packages 内部有集合缓存），每次连接都做一次没有代价。
        val unstopped = Packages.unstop(cfg.vpnPackage)
        if (unstopped && lastForceStopPkg == cfg.vpnPackage) {
            lastForceStopPkg = null
            log(ctx, "cleared stopped state for ${cfg.vpnPackage} (after force stop)")
        }

        if (!EnginePolicy.cooldownReady(now, lastApplyAt, EXTERNAL_ONLY_GUARD_MS)) return
        if (externalAttempts >= MAX_EXTERNAL_ATTEMPTS &&
            !EnginePolicy.cooldownReady(now, lastApplyAt, REAPPLY_SLOW_MS)
        ) return

        // 进程还活着，说明上一次启动请求已经被系统接受，隧道正在建。
        // 这时候再发一次启动，客户端会当成新连接把正在建的推倒重来——
        // 日志里「启动两次 → 7~11 秒才通、通了又掉」就是这么来的。
        if (EnginePolicy.waitForClientInit(
                alreadyStarted = externalAttempts > 0,
                sinceStartMs = now - lastStartAt,
                processAlive = isProcessAlive(ctx, cfg.vpnPackage),
                graceMs = EXTERNAL_INIT_GRACE_MS,
            )
        ) {
            if (!initWaitLogged) {
                initWaitLogged = true
                log(
                    ctx,
                    "external-only: 客户端还在初始化，最多再等 " +
                        "${EXTERNAL_INIT_GRACE_MS / 1000} 秒再重发启动",
                )
            }
            return
        }

        lastApplyAt = now
        lastStartAt = now
        externalAttempts++

        if (externalMode == "activity" && cfg.allowVpnUi &&
            ExternalControl.startWithUi(ctx, cfg.vpnPackage)
        ) {
            uiShownAt = SystemClock.elapsedRealtime()
            lastStartAt = now
            pendingExternalMode = "activity"
            log(ctx, "external-only: start via external control (remembered)")
        } else if (externalMode == "service" || externalAttempts <= 2) {
            log(ctx, "external-only: restart VPN service without UI (attempt $externalAttempts)")
            scheduleDelayedServiceStart(ctx, cfg)
        } else if (cfg.allowVpnUi && ExternalControl.startWithUi(ctx, cfg.vpnPackage)) {
            // 没有外部控制入口的客户端只能把界面拉到前台（FlClash 改版就属于这类）
            uiShownAt = SystemClock.elapsedRealtime()
            lastStartAt = now
            pendingExternalMode = "activity"
            log(ctx, "external-only: start via client UI (allowed in settings)")
        } else {
            log(
                ctx,
                "external-only: 无法在后台拉起 ${cfg.vpnPackage}，" +
                    "请在客户端里打开「自动连接」，或在「高级」里允许短暂打开客户端界面"
            )
        }
        return
    }

    val interval = EnginePolicy.reapplyInterval(
        reapplyCount = reapplyCount,
        maxFastReapply = MAX_FAST_REAPPLY,
        fastMs = REAPPLY_GUARD_MS,
        slowMs = REAPPLY_SLOW_MS,
        firstMs = REAPPLY_FIRST_MS,
    )
    if (!EnginePolicy.cooldownReady(now, lastApplyAt, interval)) return

    if (active && settingPointsToUs) {
        // 上一次关闭时如果强制停止过客户端，它会被系统标记成 stopped，
        // 之后 always-on 再怎么设置也不会 bind，先把这个标记清掉。
        // 不看「直接强制停止」那个开关：verify 链走到最后一步一样会强停。
        if (reapplyCount == 0) {
            if (Packages.unstop(cfg.vpnPackage)) {
                log(ctx, "cleared force-stop state for ${cfg.vpnPackage}")
            }
        }
        // 设置生效但隧道起不来：先请客户端自己启动（Clash 系只有这条能通）
        if (tryExternalStart(ctx, cfg, now)) {
            lastApplyAt = now
            return
        }
        if (reapplyCount >= TILE_AFTER_REAPPLY && tryQuickTile(ctx, cfg, now)) {
            lastApplyAt = now
            return
        }
        log(ctx, "vpn is down while setting points to us -> reapply")
        VpnSwitcher.reapply(ctx, cfg.vpnPackage, cfg.lockdown)
        lastApplyAt = now
        reapplyCount++
        noteFailure(ctx, cfg)
        return
    }

    // 全新接管前同样清一次 stopped 标记，否则可能写了设置也拉不起来
    Packages.unstop(cfg.vpnPackage)

    val ok = VpnSwitcher.enable(ctx, cfg.vpnPackage, cfg.lockdown)
    lastApplyAt = now
    reapplyCount = 0
    tileAttempts = 0
    externalAttempts = 0
    connectedNotified = false
    log(ctx, "apply vpn ok=$ok (active=$active, pointsToUs=$settingPointsToUs, connected=$connected)")
    // 之前确认过这个客户端不响应 always-on → 直接按记住的方式启动
    if (ok) {
        when (ExternalControl.modeOf(ctx, cfg.vpnPackage)) {
            // activity 模式 = 这个客户端只能靠「把界面拉到前台」来触发连接（FlClash 改版）。
            // 这里以前调的是 start()，它只会试 START_CLASH 再退到 startVpnService ——
            // 界面一次都不会出现，日志里就只剩一串 attempt，客户端永远连不上。
            "activity" -> if (ExternalControl.startWithUi(ctx, cfg.vpnPackage)) {
                uiShownAt = SystemClock.elapsedRealtime()
                lastStartAt = now
                pendingExternalMode = "activity"
                externalAttempts++
                lastExternalAt = now
                log(ctx, "known client: bring client UI forward")
            }
            "service" -> {
                pendingExternalMode = "service"
                externalAttempts++
                lastExternalAt = now
                // 宽限期按「这次启动」算：不盖这个戳，下一拍进外部控制分支时
                // sinceStart 会拿 0 去减，宽限直接失效，900ms 后就再发一次启动，
                // 把刚开始建的隧道推倒（和 02:00:37→02:00:43 那轮是同一个病）
                lastStartAt = now
                log(ctx, "known client: start VPN service (no ui)")
                scheduleDelayedServiceStart(ctx, cfg)
            }
        }
    }
    if (!ok) noteFailure(ctx, cfg)
}

/**
 * 让客户端自己启动 VPN（Clash 系导出的 `ACTION_START_CLASH` 入口）。
 * @return 是否已经发起（发起了就不再重复写 always-on 设置）
 */
internal fun Engine.tryExternalStart(ctx: Context, cfg: Config, now: Long): Boolean {
    if (cfg.vpnPackage.isEmpty()) return false
    if (externalAttempts >= MAX_EXTERNAL_ATTEMPTS) return false
    if (!EnginePolicy.cooldownReady(now, lastExternalAt, EXTERNAL_COOLDOWN_MS)) return false
    // 先给 always-on 一次机会，别一上来就拉 Activity
    if (reapplyCount < 1) return false

    if (!ExternalControl.start(ctx, cfg.vpnPackage)) {
        externalAttempts = MAX_EXTERNAL_ATTEMPTS
        return false
    }
    externalAttempts++
    lastExternalAt = now
    pendingExternalMode = "activity"
    log(ctx, "always-on did not connect -> external control START")
    return true
}

/**
 * always-on 设置生效了但隧道起不来时，改为点 VPN 客户端自己的快捷磁贴。
 * @return 是否已经点了磁贴（点了就不再重复写设置）
 */
internal fun Engine.tryQuickTile(ctx: Context, cfg: Config, now: Long): Boolean {
    if (tileAttempts >= MAX_TILE_ATTEMPTS) return false
    if (!EnginePolicy.cooldownReady(now, lastTileClickAt, TILE_COOLDOWN_MS)) return false

    val tile = QuickTiles.find(ctx, cfg.vpnPackage)
    if (tile == null) {
        if (tileAttempts == 0) {
            log(ctx, "${cfg.vpnPackage} has no QS tile, cannot auto start it")
        }
        tileAttempts = MAX_TILE_ATTEMPTS
        return false
    }

    lastTileClickAt = now
    tileAttempts++
    log(ctx, "always-on did not connect, click QS tile (attempt $tileAttempts)")
    return QuickTiles.click(ctx, tile)
}

internal fun Engine.noteFailure(ctx: Context, cfg: Config) {
    failureStreak++
    RuntimeDiagnostics.publishFailure(ctx, "enable", cfg.vpnPackage, failureStreak)
    if (!failureLogged && failureStreak >= FAILURE_HINT_AT) {
        failureLogged = true
        log(
            ctx,
            "已连续 $failureStreak 次没能建立 VPN：${cfg.vpnPackage} 不响应系统常驻 VPN，" +
                "也点不了它的快捷磁贴。请在客户端里打开「自动连接」，或换用 v2rayNG / Surfboard / WireGuard"
        )
    }
}

