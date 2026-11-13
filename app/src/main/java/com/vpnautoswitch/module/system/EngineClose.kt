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

internal fun Engine.turnOff(reason: String) {
    val ctx = context() ?: return
    beat(ctx)
    val cfg = ConfigRepo.get(ctx)
    val generation = ++offGeneration
    // 用户刚手动关过 VPN、现在这条隧道是他自己又连回来的：模块不该去强停它。
    // 但 [userOff] 会一直挂到下次打开目标应用，中间客户端完全可能自己把隧道拉起来
    // （日志 22:38 判了关闭，23:23 隧道又在），那时再按「他连的」禁止强停，
    // 关闭链只能放弃，VPN 一直挂到下次打开应用。所以还要看现场：
    // 前台是不是客户端、距离上次关闭过了多久。
    val wasUserOff = EnginePolicy.tunnelIsUserOwned(
        userOff = userOff,
        foregroundIsClient = lastForeground == cfg.vpnPackage,
        sinceOffMs = if (lastOffAt == 0L) {
            Long.MAX_VALUE
        } else {
            SystemClock.elapsedRealtime() - lastOffAt
        },
    )
    chainForceStopAllowed = !wasUserOff
    // 取消尚未执行的关闭任务，避免同一次关闭派生多条验证链
    offToken++
    // 手动连上的 VPN 没有接管标记，也要提示「已关闭」。
    // 这里必须绕开 400ms 缓存：turnOff 常常紧跟在一次「隧道还没建好」的采样之后，
    // 读到旧值就会把 wasActive 判成 false，后面的「VPN 已关闭」直接不发。
    val wasActive = VpnSwitcher.isActive(ctx.contentResolver) || hasVpnTransport(ctx, fresh = true)
    // 外部控制模式（重启 VpnService）不会写 always_on，因此 disable 会被跳过；
    // 用返回值区分「真的关掉了设置」和「只是请求客户端自己断开」
    val settingCleared = VpnSwitcher.disable(ctx, cfg.restoreOriginal, cfg.vpnPackage)
    val staleCleared = VpnSwitcher.clearIfPointsTo(ctx, cfg.vpnPackage)
    val tookOwnership = settingCleared || staleCleared
    reapplyCount = 0
    tileAttempts = 0
    externalAttempts = 0
    connectedNotified = false
    wasConnected = false
    offToastPending = false
    connectedAt = 0L
    connectStartedAt = 0L
    flapRetries = 0
    initWaitLogged = false
    serviceModeHintLogged = false
    lastStartAt = 0L
    stopServiceTried = false
    guardUntil = 0L
    // 取消上一轮的守卫窗口：这次是新的一次关闭，旧窗口盯着的是另一条链
    offGuardToken++
    pendingGuard = null
    pendingPrewarm = null
    // 主链从现在开始跑。守卫要等它彻底收尾后再接力——两条链同时跑会互相作废对方的复查。
    closeChainRunning = true
    closeChainGen = generation
    closeChainStartedAt = SystemClock.elapsedRealtime()
    val willGuard = EnginePolicy.shouldHandOffToGuard(
        guardEnabled = cfg.offGuard,
        tookOwnership = tookOwnership,
        wasActive = wasActive,
        wasUserOff = wasUserOff,
    )
    // 守卫窗口长度由 startOffGuard 按「真正开始观察」的时刻算，这里不再预估：
    // 以前按「主链 15s + 窗口 8s」压住预热，结果正常关闭也要等 23 秒客户端才变热。
    offStartedAt = SystemClock.elapsedRealtime()
    lastOffAt = offStartedAt
    stopWatchdog()
    startToken++
    markPendingExternalMode(null)
    autoSession = false
    userOff = false
    // 顺带把当前的 always-on 打出来：「强停后 VPN 又回来」只有在 always_on 指向客户端时
    // 才可能是系统重连，指向空/别的应用就只能是客户端自己或 ROM 拉起来的。
    // 以前这行信息缺失，只能靠「有没有 came back」去猜，白绕好几轮。
    val alwaysOnNow = VpnSwitcher.currentAlwaysOn(ctx).orEmpty()
    log(
        ctx,
        (if (tookOwnership) "vpn OFF ($reason)" else "off requested ($reason, 未接管常驻设置)") +
            ", always_on='${alwaysOnNow.ifEmpty { "-" }}'",
    )
    // 「VPN 已关闭」不在这里发：这条链随时可能因为「目标应用又回到前台」整轮作废，
    // 那时隧道一直连着，先说关闭就是假消息。改由 noteVpnDown 在确认断开时补上。
    offToastPending = wasActive

    // 清掉 always-on 未必能断开「用户手动连上的」VPN，稍等一下确认。
    // 外部控制模式没有设置项可清，直接把校验提前，尽快进入停止链路。
    // 清掉常驻设置后系统很快会 unbind，1.2 秒是白等；外部控制模式本来就没有设置项，直接查
    val verifyDelay = if (tookOwnership) 600L else 300L
    handler.postDelayed({
        verifyOff(
            ctx, cfg.vpnPackage, generation,
            // 开了「保留客户端进程」就先只走温和手段，实在关不掉再强停。
            // 用户手动关过的那条隧道例外：强停他手动连的客户端属于越权。
            forceStopAllowed = chainForceStopAllowed &&
                EnginePolicy.allowForceStopOnClose(
                    forceStopEnabled = cfg.forceStopVpn,
                    keepClientAlive = cfg.keepClientAlive,
                    directForceStop = cfg.forceStopImmediately,
                ),
        )
    }, verifyDelay)
    // 外部控制模式下客户端可能自己把 VPN 拉回来，关掉后盯一小会儿。
    // 用户刚手动关过的除外：那条隧道是他自己又连回来的，盯着他再关一次是越权。
    // 真正的窗口要等主链收尾才开（见 endCloseChain），这里只是把请求记下来。
    if (willGuard) {
        pendingGuard = Engine.GuardRequest(
            vpnPackage = cfg.vpnPackage,
            generation = generation,
            tookOwnership = tookOwnership,
            wasActive = wasActive,
            wasUserOff = wasUserOff,
        )
    }
    // 保留客户端进程：等主链和守卫都收尾后再 bind 一次 VpnService，把进程拉热。
    // 进程还在就什么都不做；这样下次点开目标应用不用等冷启动。
    // 「直接强制停止」本身就意味着不要留进程，跟这里正好相反，所以不预热。
    // 黑名单来自 [ModuleState]（落在 Secure 里）：一被唤醒就自连的客户端，重启后也不再盲试
    if (cfg.keepClientAlive && !cfg.forceStopImmediately &&
        cfg.vpnPackage.isNotEmpty() &&
        cfg.vpnPackage != ModuleState.prewarmUnsupported(ctx)
    ) {
        pendingPrewarm = Engine.PrewarmRequest(
            vpnPackage = cfg.vpnPackage,
            notBefore = SystemClock.elapsedRealtime() + PREWARM_DELAY_MS,
        )
        // 主链很快就结束、又没开守卫时由这一拍负责；否则它会把自己推迟到收尾之后再跑
        handler.postDelayed({ maybePrewarm(ctx) }, PREWARM_DELAY_MS)
    }
    publishRuntime(ctx)
}

/** 目标应用回到前台（压制解除）时不要动它；只是因为「打开即关闭」而断开时，继续关干净 */
internal fun Engine.canTouchVpn(ctx: Context): Boolean =
    !VpnSwitcher.isActive(ctx.contentResolver) &&
        !(aliveTargets.isNotEmpty() && offAppForeground == null)

/** 关闭链仍然有效：代次没变、VPN 包没换、当前仍允许操作 VPN。 */
internal fun Engine.closeChainValid(ctx: Context, expectedPackage: String, generation: Int): Boolean {
    val current = ConfigRepo.get(ctx)
    return EnginePolicy.isCloseChainValid(
        generation = generation,
        currentGeneration = offGeneration,
        expectedVpnPackage = expectedPackage,
        currentVpnPackage = current.vpnPackage,
        canTouchVpn = canTouchVpn(ctx),
    )
}

/**
 * 关闭链确认隧道已经消失时，把「从请求关闭到真的断开」的耗时写进诊断。
 *
 * 只记第一次确认：关后守卫发现客户端又把 VPN 拉起来时会再关一次，
 * 那属于另一轮，不该把第一轮的耗时覆盖成很短的假数据。
 */
internal fun Engine.noteVpnDown(ctx: Context) {
    // 提示改成「真的断了才说」：请求发出后可能被目标应用打断，那时隧道一直没断
    if (offToastPending) {
        offToastPending = false
        toast(ctx, "VPN 已关闭", ConfigRepo.get(ctx).toastEnabled)
    }
    val started = offStartedAt
    if (started == 0L) return
    offStartedAt = 0L
    RuntimeDiagnostics.publishTiming(ctx, "off", SystemClock.elapsedRealtime() - started)
}

/**
 * 关闭后校验：如果隧道还在（典型场景：用户在 VPN 客户端里手动连上的，
 * 清 always-on 设置并不会断开它），就依次尝试「停止请求 → 快捷磁贴 → 强制停止」。
 */
internal fun Engine.verifyOff(
    ctx: Context,
    expectedPackage: String,
    generation: Int,
    forceStopAllowed: Boolean = true,
) {
    if (!closeChainValid(ctx, expectedPackage, generation)) return
    if (!hasVpnTransport(ctx, fresh = true)) {
        noteVpnDown(ctx)
        log(ctx, LogCat.STOP, "verify: vpn is down")
        endCloseChain(ctx, generation)
        return
    }

    val cfg = ConfigRepo.get(ctx)
    val isServiceMode = expectedPackage.isNotEmpty() &&
        ExternalControl.modeOf(ctx, expectedPackage) == "service"
    val hasTile = expectedPackage.isNotEmpty() && QuickTiles.find(ctx, expectedPackage) != null
    val plan = EnginePolicy.stopOrder(
        hasPackage = expectedPackage.isNotEmpty(),
        allowActivityUi = cfg.allowVpnUi,
        // service 模式是模块用 startService 把客户端拉起来的，磁贴上的状态跟实际并不同步，
        // 点它等于盲按一次 toggle。日志里 15:50:32 点完，15:50:38 VPN 反而「came back」，
        // 之后又要强停一遍——这条路径本来就不该点磁贴。
        hasTile = hasTile && !isServiceMode,
        forceStopEnabled = cfg.forceStopVpn && forceStopAllowed,
        // 「直接强制停止」跳掉广播 / activity / 磁贴，一步到位
        directForceStop = cfg.forceStopImmediately,
    )
    // 这个客户端以前响应过 STOP 广播吗？连续两次没反应就不再白等那 0.7~1.2 秒
    val broadcastFailures = ModuleState.broadcastFailures(ctx, expectedPackage)
    val skipBroadcast = EnginePolicy.shouldSkipBroadcast(broadcastFailures)

    // 把这轮打算用哪些手段记一行：以后看到「温和手段没关掉」时，
    // 能立刻分清是磁贴没找到、还是点了没反应，不必靠时间戳反推
    log(
        ctx,
        "verify: steps=" + plan.joinToString(">") { step ->
            if (step == EnginePolicy.StopStep.BROADCAST && skipBroadcast) "broadcast(skip)"
            else step.name.lowercase()
        },
    )
    if (skipBroadcast && EnginePolicy.StopStep.BROADCAST in plan) {
        log(ctx, LogCat.STOP, "verify: 跳过 STOP 广播（连续 $broadcastFailures 次没有反应，等也是白等）")
    }

    // 1) 无 UI：给客户端发它自己的停止广播（Clash 系支持）。
    //    service 模式也试一次——某些客户端（mihomo / Clash.Meta）虽然只能靠
    //    VpnService 启动，但仍然实现了 STOP 广播，发一下比直接强杀更温和。
    if (EnginePolicy.StopStep.BROADCAST in plan && !skipBroadcast) {
        if (ExternalControl.requestStop(ctx, expectedPackage)) {
            awaitDown(
                ctx, expectedPackage, generation,
                EnginePolicy.broadcastWaitMs(isServiceMode),
            ) { down ->
                if (down) {
                    // 这次管用了：把「无效」的结论清掉，客户端升级后还能重新给机会
                    ModuleState.noteBroadcastResult(ctx, expectedPackage, true)
                    noteVpnDown(ctx)
                    log(ctx, LogCat.STOP, "verify: vpn is down (client stopped)")
                    endCloseChain(ctx, generation)
                } else {
                    ModuleState.noteBroadcastResult(ctx, expectedPackage, false)
                    verifyOffStep(ctx, expectedPackage, generation, true, plan)
                }
            }
            return
        }
        // 广播根本发不出去（客户端没注册 receiver）：和「发了没反应」算同一类
        ModuleState.noteBroadcastResult(ctx, expectedPackage, false)
    }
    verifyOffStep(ctx, expectedPackage, generation, true, plan)
}

/**
 * 关后守卫。
 *
 * 外部控制模式（重启 VpnService）关掉之后，客户端可能被自己的服务、JobScheduler
 * 或系统重新拉起来。这里在 8 秒窗口内盯几次，发现隧道又起来就再关一次；
 * 连续多次仍回来就放弃并记录，避免和客户端互相拉扯。
 *
 * 与主关闭链**串行**：由 [endCloseChain] 在主链收尾后接力启动，窗口内每次动手前
 * 还会再看一眼 [closeChainRunning]。两条链同时跑会让 `offGeneration` 互相作废，
 * 结果是磁贴被点两次、强停次数从 1 重数，日志里出现同一秒的两条 verify。
 *
 * 省电设计：只在没有接管常驻设置时启动；间隔递增（首拍 0.5s，最慢 2.4s），
 * 整个窗口只查 5 次左右；窗口结束不留下任何定时器。
 */
internal fun Engine.startOffGuard(ctx: Context, req: Engine.GuardRequest) {
    val cfg = ConfigRepo.get(ctx)
    if (!cfg.offGuard || req.vpnPackage.isEmpty()) return
    val startedAt = SystemClock.elapsedRealtime()
    // 窗口内不预热：bind 会把守卫正想抑制的客户端叫醒
    guardUntil = maxOf(guardUntil, startedAt + GUARD_WINDOW_MS)
    val token = ++offGuardToken
    var attempts = 0
    var interval = GUARD_FIRST_MS
    // 守卫真正开始观察的时刻：主链还没收尾时只是在等，不该消耗观察窗口
    var windowStart = 0L
    // 每次重新关闭都推进一遍代次：让上一条链里排队的复查、以及 turnOff 排的预热全部作废
    var chainGen = req.generation

    val tick = object : Runnable {
        override fun run() {
            if (token != offGuardToken) return
            val now = SystemClock.elapsedRealtime()

            // 主链还在跑（或兜底时长内仍认为自己没跑完）：守卫生来就是给它收尾的，不插队
            if (!EnginePolicy.guardMayAct(
                    busy = closeChainRunning,
                    busyForMs = now - closeChainStartedAt,
                    maxBusyMs = CLOSE_CHAIN_MAX_MS,
                )
            ) {
                handler.postDelayed(this, 500L)
                return
            }

            if (windowStart == 0L) windowStart = now
            if (now - windowStart >= GUARD_WINDOW_MS) {
                guardUntil = 0L
                // 抑制结束，客户端可以变热了（等这一拍已经推后的预热会自己到点，这里只是提前）
                maybePrewarm(ctx)
                return
            }
            if (!closeChainValid(ctx, req.vpnPackage, chainGen)) return

            if (!hasVpnTransport(ctx, fresh = true)) {
                interval = EnginePolicy.nextGuardInterval(interval)
                handler.postDelayed(this, interval)
                return
            }

            attempts++
            if (!EnginePolicy.shouldGuard(
                    guardEnabled = cfg.offGuard,
                    tookOwnership = false,
                    attempts = attempts,
                    maxAttempts = MAX_GUARD_ATTEMPTS,
                    closable = true,
                )
            ) {
                log(
                    ctx,
                    "guard: 客户端已 $attempts 次重新拉起 VPN，停止抑制，" +
                        "请检查客户端的「自动连接」设置",
                )
                guardUntil = 0L
                // 客户端会自己把 VPN 拉回来，这时候再 bind 一次只会让它更快连上
                if (pendingPrewarm != null) {
                    pendingPrewarm = null
                    log(ctx, LogCat.STOP, "prewarm cancelled: 客户端会自己重连，本轮不预热")
                }
                return
            }
            log(ctx, LogCat.STOP, "guard: vpn came back -> re-off ($attempts/$MAX_GUARD_ATTEMPTS)")
            // 走完整关闭链（停止广播 → 磁贴 → 强停），而不是只点磁贴：
            // 磁贴是切换语义，连续点两次会把 VPN 又打开
            chainGen = ++offGeneration
            closeChainRunning = true
            closeChainGen = chainGen
            closeChainStartedAt = now
            // 不再接力下一个守卫：窗口还没走完，下一拍继续由当前这一条负责
            pendingGuard = null
            verifyOff(
                ctx, req.vpnPackage, chainGen,
                forceStopAllowed = chainForceStopAllowed &&
                    EnginePolicy.allowForceStopOnClose(
                        forceStopEnabled = cfg.forceStopVpn,
                        keepClientAlive = cfg.keepClientAlive,
                        directForceStop = cfg.forceStopImmediately,
                    ),
            )
            // 等关闭链跑完再查（广播 2s + 磁贴 2s），避免在 transport 还没刷新时重复动手
            interval = 3_500L
            handler.postDelayed(this, interval)
        }
    }
    handler.postDelayed(tick, interval)
}

/**
 * 主关闭链收尾：释放 busy 标志，需要的话把守卫接力起来。
 *
 * 只认当前代次：旧链被新关闭作废后仍然可能跑到这里，它不能把新链的 busy 标志清掉。
 */
internal fun Engine.endCloseChain(ctx: Context?, generation: Int) {
    if (generation != closeChainGen) return
    closeChainRunning = false
    closeChainStartedAt = 0L
    val request = pendingGuard
    if (request != null) pendingGuard = null
    if (ctx == null) return
    if (request != null && request.generation == generation) startOffGuard(ctx, request)
    // 守卫没开或不适用时，主链一结束就可以预热；开了守卫则要等窗口结束（见 maybePrewarm）
    maybePrewarm(ctx)
}

/**
 * 预热客户端进程，把「下次打开目标应用」的冷启动省掉。
 *
 * 由三处唤起：[turnOff] 排的第一拍、[endCloseChain] 主链收尾、守卫窗口结束/放弃。
 * 谁先满足条件谁执行，执行时清空 [pendingPrewarm]，其余的调用自然返回。
 *
 * 前置条件缺一不可：主链已收尾、守卫窗口已结束、隧道确实断开、进程确实不在了。
 * 预热会 bind 客户端，正好把它从后台叫醒——这对「保留进程」是目的，
 * 对「关闭后继续抑制」却是干扰，所以必须等抑制结束。
 */
internal fun Engine.maybePrewarm(ctx: Context) {
    val req = pendingPrewarm ?: return
    // 预热的有效性**不**跟「哪一代关闭链」绑。守卫每次重新关闭都会推进 offGeneration，
    // 按代次判定就一定会被它顶掉——日志 15:13:33 那条
    // 「prewarm skipped: 已重新连接或目标应用回到前台」其实正是被作废，
    // 结果是开了「保留客户端进程」也从来不预热。
    // 真正该判的是「现在还是不是那个该保持关闭的状态」。
    val cfgNow = ConfigRepo.get(ctx)
    if (!cfgNow.enabled || cfgNow.vpnPackage != req.vpnPackage) {
        pendingPrewarm = null
        log(ctx, LogCat.STOP, "prewarm cancelled: 客户端或开关已变")
        return
    }
    if (userOff || automationPaused) {
        pendingPrewarm = null
        log(ctx, LogCat.STOP, "prewarm skipped: 已停止自动控制")
        return
    }
    if (aliveTargets.isNotEmpty() || offAppForeground != null) {
        pendingPrewarm = null
        log(ctx, LogCat.STOP, "prewarm skipped: 目标应用已回到前台，不用预热")
        return
    }
    // 主链还在跑：它收尾时会再叫一次，这里不插队
    if (closeChainRunning) return
    val now = SystemClock.elapsedRealtime()
    when (EnginePolicy.prewarmGate(
        chainRunning = false,
        now = now,
        guardUntil = guardUntil,
        notBefore = req.notBefore,
        hasTransport = hasVpnTransport(ctx),
        processAlive = isProcessAlive(ctx, req.vpnPackage),
        sinceForceStopMs = now - lastForceStopAt,
        forceStopGuardMs = PREWARM_AFTER_FORCE_STOP_MS,
    )) {
        // 主链还在跑（兜底分支）：它收尾时会再叫一次，这里不插队
        EnginePolicy.PrewarmGate.WAIT_CHAIN -> return
        // 守卫还在盯「别让它回来」：这时候 bind 等于把要抑制的进程叫醒
        EnginePolicy.PrewarmGate.WAIT_GUARD -> {
            handler.postDelayed({ maybePrewarm(ctx) }, guardUntil - now + 300L)
            return
        }
        EnginePolicy.PrewarmGate.WAIT_TIME -> {
            handler.postDelayed({ maybePrewarm(ctx) }, req.notBefore - now)
            return
        }
        // 隧道还在：这一轮不是「关掉了」的状态，预热会让它更难断
        EnginePolicy.PrewarmGate.SKIP_TRANSPORT -> {
            pendingPrewarm = null
            log(ctx, LogCat.STOP, "prewarm skipped: 隧道还连着，这一轮不用预热")
            return
        }
        // 进程还活着就已经是热的，什么都不用做
        EnginePolicy.PrewarmGate.SKIP_ALIVE -> {
            pendingPrewarm = null
            log(ctx, LogCat.STOP, "prewarm skipped: 客户端进程还活着，不用预热")
            return
        }
        // 刚强停过：bind 会把刚进入 stopped 状态的客户端拉回来，稍等一下再试，
        // 不能就此放弃——否则「强停过」的这一轮永远不会预热（关掉守卫时尤其明显）。
        EnginePolicy.PrewarmGate.SKIP_FORCE_STOP -> {
            val wait = (lastForceStopAt + PREWARM_AFTER_FORCE_STOP_MS - now + 300L)
                .coerceAtLeast(300L)
            log(ctx, LogCat.STOP, "prewarm: 刚强停过，${wait}ms 后再预热")
            handler.postDelayed({ maybePrewarm(ctx) }, wait)
            return
        }
        EnginePolicy.PrewarmGate.GO -> Unit
    }
    pendingPrewarm = null
    // 上一轮强停可能留下了 stopped 标记，先清掉再 bind，否则拉不起来
    val unstopped = Packages.unstop(req.vpnPackage)
    log(ctx, LogCat.STOP, "prewarm: 开始预热 ${req.vpnPackage}（清除 stopped=$unstopped）")
    if (!ExternalControl.prewarm(ctx, req.vpnPackage, handler)) return
    handler.postDelayed({ verifyPrewarm(ctx, req) }, PREWARM_CHECK_MS)
}

/**
 * 预热复查。
 *
 * 有的客户端在进程被唤醒时就把上次的连接恢复了（onCreate / onBind 里直接 reconnect），
 * bind 一下等于替它把 VPN 又打开了。它做不到「进程热着、隧道断开」这个状态，
 * 那就再关一次，并记住以后不给它预热，免得每次关闭都多一轮开→关。
 */
internal fun Engine.verifyPrewarm(ctx: Context, req: Engine.PrewarmRequest) {
    val cfg = ConfigRepo.get(ctx)
    if (!cfg.enabled || cfg.vpnPackage != req.vpnPackage) return
    // 用户已经重新打开目标应用：这时隧道起来是正常的连接请求，不是「预热把它弄醒了」
    if (aliveTargets.isNotEmpty() || offAppForeground != null) return
    if (!hasVpnTransport(ctx, fresh = true)) return
    ModuleState.setPrewarmUnsupported(ctx, req.vpnPackage)
    log(ctx, LogCat.STOP, "prewarm: 客户端一被唤醒就自己连上了，以后不再预热（重启后也记住），并再关一次")
    val generation = ++offGeneration
    closeChainRunning = true
    closeChainGen = generation
    closeChainStartedAt = SystemClock.elapsedRealtime()
    verifyOff(
        ctx, req.vpnPackage, generation,
        forceStopAllowed = chainForceStopAllowed &&
            EnginePolicy.allowForceStopOnClose(
                forceStopEnabled = cfg.forceStopVpn,
                keepClientAlive = cfg.keepClientAlive,
                directForceStop = cfg.forceStopImmediately,
            ),
    )
}

/** 每 400ms 看一次隧道是否断开，最多等 [deadlineMs]，避免固定 sleep 白等 */
internal fun Engine.awaitDown(
    ctx: Context,
    expectedPackage: String,
    generation: Int,
    deadlineMs: Long,
    onDone: (Boolean) -> Unit,
) {
    val startedAt = SystemClock.elapsedRealtime()
    val poll = object : Runnable {
        override fun run() {
            if (!closeChainValid(ctx, expectedPackage, generation)) return
            if (!hasVpnTransport(ctx, fresh = true)) {
                onDone(true)
                return
            }
            if (SystemClock.elapsedRealtime() - startedAt >= deadlineMs) {
                onDone(false)
                return
            }
            handler.postDelayed(this, 400L)
        }
    }
    handler.postDelayed(poll, 400L)
}

internal fun Engine.verifyOffStep(
    ctx: Context,
    expectedPackage: String,
    generation: Int,
    allowActivity: Boolean,
    plan: List<EnginePolicy.StopStep>,
) {
    if (!closeChainValid(ctx, expectedPackage, generation)) return
    val cfg = ConfigRepo.get(ctx)
    if (!hasVpnTransport(ctx, fresh = true)) {
        noteVpnDown(ctx)
        log(ctx, LogCat.STOP, "verify: vpn is down")
        endCloseChain(ctx, generation)
        return
    }

    // 2) 客户端导出的 STOP_CLASH 入口
    if (allowActivity && EnginePolicy.StopStep.ACTIVITY in plan &&
        cfg.allowVpnUi && expectedPackage.isNotEmpty() &&
        ExternalControl.stop(ctx, expectedPackage)
    ) {
        awaitDown(ctx, expectedPackage, generation, 2_500L) { down ->
            if (down) {
                noteVpnDown(ctx)
                log(ctx, LogCat.STOP, "verify: vpn is down (client stop activity)")
                endCloseChain(ctx, generation)
            } else {
                verifyOffStep(ctx, expectedPackage, generation, false, plan)
            }
        }
        return
    }

    // 3) 快捷磁贴。磁贴是切换语义：主链点完还没生效，关后守卫再点一次就把它又打开了，
    //    所以这里的第二次点击必须挡掉，宁可直接进强停。
    val now = SystemClock.elapsedRealtime()
    val tileAllowed = EnginePolicy.tileToggleAllowed(
        now = now,
        lastToggleAt = lastTileToggleAt,
        guardMs = TILE_TOGGLE_GUARD_MS,
    )
    if (EnginePolicy.StopStep.TILE in plan && !tileAllowed) {
        log(
            ctx,
            "verify: tile toggled ${now - lastTileToggleAt}ms ago, " +
                "skip the second toggle (would turn the vpn back on)",
        )
    }
    val tile = if (EnginePolicy.StopStep.TILE in plan && tileAllowed) {
        QuickTiles.find(ctx, expectedPackage)
    } else {
        null
    }
    if (tile != null) {
        if (QuickTiles.click(ctx, tile)) {
            lastTileToggleAt = now
            // 磁贴生效通常是即时的，1.2 秒还没断就说明这次点击没被采纳，
            // 再等下去只是白拖关闭时间（原来 2 秒，加上前面的广播就是 3 秒）
            awaitDown(ctx, expectedPackage, generation, 1_200L) { down ->
                if (down) {
                    noteVpnDown(ctx)
                    log(ctx, LogCat.STOP, "verify: vpn is down (qs tile)")
                    endCloseChain(ctx, generation)
                } else {
                    finishVerifyOff(ctx, expectedPackage, generation, 0, plan)
                }
            }
            return
        }
        // 磁贴存在但点不动（ROM 限制 / 磁贴未启用）：继续走后面的手段，但留个记录，
        // 否则日志里只会看到「温和手段没关掉」，查不出是哪一步失效
        log(ctx, LogCat.STOP, "verify: 快捷磁贴点击未生效，继续下一种手段")
    }
    finishVerifyOff(ctx, expectedPackage, generation, 0, plan)
}

/**
 * 强制停止客户端。
 *
 * 有些设备上进程被杀了，VPN 网络还要几秒才真正消失；以前只查一次就下结论，
 * 日志里会出现「still up after force stop」但其实马上就断了。现在分批复查，
 * 仍然连着就再停一次，最多三次，最后如实报告。
 */
internal fun Engine.finishVerifyOff(
    ctx: Context,
    expectedPackage: String,
    generation: Int,
    attempt: Int,
    plan: List<EnginePolicy.StopStep>,
) {
    if (!closeChainValid(ctx, expectedPackage, generation)) return
    val cfg = ConfigRepo.get(ctx)
    if (!hasVpnTransport(ctx, fresh = true)) {
        noteVpnDown(ctx)
        log(
            ctx,
            if (attempt == 0) "verify: vpn is down" else "verify: disconnected after force stop"
        )
        endCloseChain(ctx, generation)
        return
    }

    // 强停之前，先让客户端自己把 VpnService 停掉。onDestroy 里它会走自己的
    // 断流与清理，比杀进程温和；更要紧的是——进程被杀而 VPN 会话还登记在
    // system_server 时，会话会立刻被重新 bind，进程原地复活
    // （日志 15:34:05「still up after force stop, app alive=true」就是这个来回）。
    if (attempt == 0 && !stopServiceTried && expectedPackage.isNotEmpty()) {
        stopServiceTried = true
        if (ExternalControl.stopVpnService(ctx, expectedPackage)) {
            log(ctx, LogCat.STOP, "verify: 先停客户端 VpnService，等它自己断开")
            handler.postDelayed({
                if (!closeChainValid(ctx, expectedPackage, generation)) return@postDelayed
                if (!hasVpnTransport(ctx, fresh = true)) {
                    noteVpnDown(ctx)
                    log(ctx, LogCat.STOP, "verify: vpn is down (VpnService stopped)")
                    endCloseChain(ctx, generation)
                } else {
                    finishVerifyOff(ctx, expectedPackage, generation, attempt, plan)
                }
            }, STOP_SERVICE_WAIT_MS)
            return
        }
    }

    if (EnginePolicy.StopStep.FORCE_STOP !in plan ||
        !cfg.forceStopVpn || expectedPackage.isEmpty()
    ) {
        // 「保留客户端进程」时上面只跑了温和手段；确实关不掉才允许强停一次，
        // 宁可让进程被杀，也不能出现「点了断开、VPN 还连着」。
        // 用户手动连的那条例外：那是他自己的连接，杀掉客户端属于越权。
        if (cfg.keepClientAlive && cfg.forceStopVpn && expectedPackage.isNotEmpty() &&
            chainForceStopAllowed
        ) {
            log(ctx, LogCat.STOP, "verify: 温和手段没关掉，改为强制停止（稍后会重新预热进程）")
            finishVerifyOff(
                ctx, expectedPackage, generation, attempt,
                listOf(EnginePolicy.StopStep.FORCE_STOP),
            )
            return
        }
        log(ctx, LogCat.STOP, "verify: vpn still up, no further step allowed")
        endCloseChain(ctx, generation)
        return
    }
    if (attempt >= MAX_FORCE_STOP_ATTEMPTS) {
        log(ctx, LogCat.STOP, "verify: still up after $attempt force stops, giving up")
        RuntimeDiagnostics.publishFailure(ctx, "stop", expectedPackage, attempt)
        endCloseChain(ctx, generation)
        return
    }

    // 少数 ROM / 客户端会把 always-on 又写回来，动手前再清一次
    VpnSwitcher.clearIfPointsTo(ctx, expectedPackage)

    log(
        ctx,
        "verify: vpn still up -> force stop $expectedPackage " +
            "(${attempt + 1}/$MAX_FORCE_STOP_ATTEMPTS)"
    )
    val ok = forceStop(ctx, expectedPackage)
    if (!ok) {
        endCloseChain(ctx, generation)
        return
    }
    lastForceStopAt = SystemClock.elapsedRealtime()
    lastForceStopPkg = expectedPackage
    // 这里**不要**立刻 unstop：stopped 标记会阻止系统再次 bind 它的 VpnService，
    // 正是我们想要的「关掉就真的关掉」。下次需要启动时 ensureVpn 会再清除。
    Packages.forget(expectedPackage)
    // 下面这 6 秒是关键：force-stop 不拦 bind，Vpn 的 ServiceConnection 或客户端自己的
    // 前台服务会立刻把它拉回来（日志 15:50:35 → 15:50:38 的 came back，以及那个
    // 「正在启动服务」提示都出在这里）。窗口内让 ActiveServices 的入口直接拒绝启动。
    ClientStartGuard.suppress(
        expectedPackage,
        SystemClock.elapsedRealtime(),
        CLIENT_START_GUARD_MS,
    )
    log(
        ctx,
        "force stopped, stopped-state kept (will clear before next start), " +
            "client starts blocked for ${CLIENT_START_GUARD_MS}ms",
    )

    handler.postDelayed({
        if (!closeChainValid(ctx, expectedPackage, generation)) return@postDelayed
        if (!hasVpnTransport(ctx, fresh = true)) {
            // 这里以前只打了日志、没走 noteVpnDown，「VPN 已关闭」就永远弹不出来：
            // 强停这条路是绝大多数关闭的终点（17:49:58、17:53:37 都是），
            // 结果只有走磁贴 / 广播成功的少数情况才有提示。
            noteVpnDown(ctx)
            log(ctx, LogCat.STOP, "verify: disconnected after force stop")
            endCloseChain(ctx, generation)
        } else {
            // 强停后隧道还在：多半是 VPN 会话没拆完，system_server 又把客户端的
            // VpnService bind 了起来（进程原地复活）。不是「没杀掉」，只能再停一次。
            val alive = isProcessAlive(ctx, expectedPackage)
            log(
                ctx,
                "verify: 强停后隧道还在，" +
                    if (alive) "客户端已被系统重新拉起 -> 再停一次"
                    else "进程已结束 -> 再停一次",
            )
            finishVerifyOff(ctx, expectedPackage, generation, attempt + 1, plan)
        }
    }, FORCE_STOP_RECHECK_MS + attempt * 400L)
}

internal fun Engine.forceStop(ctx: Context, pkg: String): Boolean = try {
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    am.javaClass.getMethod("forceStopPackage", String::class.java).invoke(am, pkg)
    true
} catch (t: Throwable) {
    log(ctx, LogCat.STOP, "force stop failed: $t")
    RuntimeDiagnostics.publishFailure(ctx, "forceStop", pkg, 0)
    false
}

/**
 * 当前有没有 VPN 隧道。
 *
 * 默认带 400ms 记忆：一拍看门狗里 `logForeground` / `ensureVpn` / 结尾复查会各问一次，
 * 每次都遍历 allNetworks 再逐个跨进程取 capabilities，纯属重复劳动。
 * 关闭链里判断「到底断开没有」的地方传 `fresh = true`，宁可多查一次也不能看旧值。
 */
internal fun Engine.hasVpnTransport(ctx: Context, fresh: Boolean = false): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (!fresh && now - transportAt in 0..TRANSPORT_MEMO_MS) return transportValue
    val value = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (t: Throwable) {
        true
    }
    transportAt = now
    transportValue = value
    return value
}

internal fun Engine.toast(ctx: Context, message: String, enabled: Boolean) {
    if (!enabled) return
    try {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] toast failed: $t")
            }
        }
    } catch (t: Throwable) {
        XposedBridge.log("[$TAG] toast post failed: $t")
    }
}

