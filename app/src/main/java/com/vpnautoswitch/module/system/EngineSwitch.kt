package com.vpnautoswitch.module.system

import android.content.Context
import android.os.SystemClock
import com.vpnautoswitch.module.Config
import com.vpnautoswitch.module.VpnRule

// 组间切换：先关、确认断开、再开
//
// Android 同一时刻只有一条 VPN 隧道。直接开新客户端会顶掉旧的，而旧客户端多半有
// 「自动重连」，反过来把新的顶掉——两边互踢，谁也连不稳。所以只能串行：
// turnOff 之后什么都不做，等 noteVpnDown 确认隧道真的没了，再启动新的那一组。

/** 待切换的请求最多挂多久：超过就是上一轮的事了，不能留到下次关闭时突然启动 */
internal const val PENDING_SWITCH_TTL_MS = 30_000L

/** 这次判断的结果：调用方据此决定「收工 / 继续拉 VPN」 */
internal enum class SwitchDecision {
    /** 不需要切换，或者现场不允许切：照常往下走 */
    NONE,

    /** 已经开始关旧的，后半段由 noteVpnDown 接力 */
    STARTED,

    /** 想切，但冷却或退避压着：这一拍保持现状，下一拍再看 */
    DEFERRED,

    /**
     * 隧道开着，但认不出是哪个客户端（always_on 没写、模块刚重启、客户端自己连的）。
     *
     * 返回它而不是 NONE：调用方要是照常 ensureVpn，会把别人的隧道直接记成这一组的，
     * 之后关的时候就会关错。
     */
    UNKNOWN,
}

/**
 * 「现在跑的是谁」。
 *
 * 模块重启（LSPosed 重新加载、系统更新）会把内存清空，这时 activePkg 是空的。
 * 光靠配置推不出来——配置说「命中的是 B 组」，实际开着的可能还是 A 组。
 * always_on 指向谁就是谁，这是唯一可靠的现场证据。
 */
internal fun Engine.resolveActivePkg(ctx: Context, cfg: Config): String {
    if (activePkg.isNotEmpty()) return activePkg
    val on = VpnSwitcher.currentAlwaysOn(ctx) ?: return ""
    if (on !in cfg.knownClients()) return ""
    activePkg = on
    activeRuleId = cfg.ruleOfPackage(on)?.id.orEmpty()
    log(ctx, LogCat.DIAG, "active client recovered from always_on: $on")
    return on
}

/**
 * 发起一次切换。
 *
 * @param rule 目标组；默认组用 [Config.defaultRule] 造的那份（id 为空串）
 */
internal fun Engine.requestSwitch(ctx: Context, rule: VpnRule, now: Long): SwitchDecision {
    // 连续几次都没切完就先停手。关闭链一直确认不了断开时，切换会被丢弃，
    // 冷却一过又发起一次，于是变成「每 30 秒关一次」，用户看到的是网络反复断。
    if (switchTrouble.blocked(rule.id, now)) {
        if (now - switchBlockLoggedAt > SWITCH_RETRY_WINDOW_MS) {
            switchBlockLoggedAt = now
            log(
                ctx,
                LogCat.START,
                "switch to ${groupTag(rule)} parked: ${switchTrouble.fails} attempts in a row " +
                    "did not finish, waiting ${SWITCH_BACKOFF_MS / 1000}s",
            )
        }
        return SwitchDecision.DEFERRED
    }
    if (now - lastSwitchAt < SWITCH_COOLDOWN_MS) return SwitchDecision.DEFERRED
    lastSwitchAt = now
    switchTrouble = switchTrouble.noteAttempt(rule.id, now)
    pendingSwitchRuleId = rule.id
    pendingSwitchAt = now
    log(
        ctx,
        LogCat.START,
        "switch: ${activePkg.ifEmpty { "?" }} -> ${rule.vpnPackage} (${groupTag(rule)})",
    )
    // 切换要断一两秒网，什么都不说的话用户只会觉得「VPN 坏了」
    toast(ctx, "正在切换到${groupTag(rule)}", ConfigRepo.get(ctx).toastEnabled)
    turnOff("switch to ${groupTag(rule)}")
    return SwitchDecision.STARTED
}

/**
 * 关闭链确认隧道没了之后，把待切换的组拉起来。
 *
 * 不在这里检查「是不是同一组」：requestSwitch 只在两个客户端不同的时候才发起，
 * 这里要防的是「等待期间现场已经变了」——用户切回了上一组的应用、或者目标应用
 * 已经退出。这两种情况再开就是凭空连一个用户已经不需要的 VPN。
 */
internal fun Engine.resumePendingSwitch(ctx: Context) {
    val ruleId = pendingSwitchRuleId ?: return
    pendingSwitchRuleId = null
    val now = SystemClock.elapsedRealtime()
    if (now - pendingSwitchAt > PENDING_SWITCH_TTL_MS) {
        log(ctx, LogCat.START, "switch dropped: 排队的请求已经过期")
        return
    }
    val cfg = ConfigRepo.get(ctx)
    // 默认组的 id 是空串；它永远「存在」，即使规则组被删了也还能切回去
    val rule = if (ruleId.isEmpty()) {
        cfg.defaultRule()
    } else {
        cfg.rules.firstOrNull { it.id == ruleId && it.enabled }
    } ?: return
    if (rule.vpnPackage.isEmpty()) return
    if (!cfg.enabled || automationPaused || screenOffActive || wifiSuppressed) return
    // 现场重新算一遍命中的是谁，而不是拼接「前台 / 存活目标 / SSID」三种条件：
    // 判定顺序（组内 Wi-Fi 优先、前台压过往后台的进程）就在这里，重写一遍迟早会走岔。
    // 命中组已经换人（或者回到没有组的界面）就说明用户不需要这次切换了。
    val nowHit = cfg.hitGroup(ruleProbe())
    if (!switchStillWanted(ruleId, nowHit)) {
        log(
            ctx,
            LogCat.START,
            "switch to ${groupTag(rule)} dropped: 现在命中${nowHit?.let { groupTag(it.rule) } ?: "默认组"}",
        )
        return
    }
    // 包名以配置里最新的为准：等待期间用户可能把这一组的客户端换掉了
    log(ctx, LogCat.START, "switch: starting ${rule.vpnPackage} (${groupTag(rule)})")
    // 连上时由 ensureVpn 取走这句话。放在这里而不是 requestSwitch：那会儿隧道刚断，
    // 「已切换」和真正连上之间还隔着一次客户端启动。
    switchToast = "已切换到${groupTag(rule)}"
    switchToastAt = now
    switchInProgress = true
    try {
        ensureVpn(ctx, cfg.forClient(rule.vpnPackage, rule.id), rule = rule)
    } finally {
        switchInProgress = false
    }
    startWatchdog()
}

/** 切换还欠着一条「开新的」没做完 */
internal fun Engine.hasPendingSwitch(): Boolean = pendingSwitchRuleId != null

/**
 * 取走切换途中放下的那句「已切换到 X」。
 *
 * 隧道起不来时这句话会一直挂着，所以按 [PENDING_SWITCH_TTL_MS] 判一次新鲜度：
 * 过期就丢掉，免得半小时后某次正常连上弹出「已切换到内网穿透」。
 */
internal fun Engine.takeSwitchToast(): String? {
    val text = switchToast ?: return null
    switchToast = null
    if (SystemClock.elapsedRealtime() - switchToastAt > PENDING_SWITCH_TTL_MS) return null
    return text
}

/**
 * 「隧道通着，但跑的不是这次要的那一组」——现在该不该发起切换。
 *
 * 四个入口（前台变化、两个网络回调、看门狗）原本各写了一遍同样的判断，
 * 冷却与退避的日志也只有其中一处记得住。收进来之后，行为至少是一致的。
 *
 * @param want 这一拍希望跑的那一组
 * @param vpnOn 隧道或接管标记任一成立（调用方已经读过一次，不再重复读设置）
 */
internal fun Engine.maybeSwitch(
    ctx: Context,
    want: VpnRule,
    vpnOn: Boolean,
    cfg: Config,
): SwitchDecision {
    if (!vpnOn || want.vpnPackage.isEmpty()) return SwitchDecision.NONE
    // 顶层配置：knownClients 要把默认组和所有规则组都算上，才知道 always_on 认不认得
    val running = resolveActivePkg(ctx, cfg)
    if (running.isEmpty()) {
        // 隧道开着，却说不清是谁的（always_on 没写、模块刚重启、客户端自己连的）。
        // 这时贸然 turnOff 会关掉用户自己连的 VPN，所以只留痕不动作；
        // 真正的后果是「打开 B 组的应用，走的还是 A 组的隧道」，有这行日志才查得出来。
        val now = SystemClock.elapsedRealtime()
        if (now - unknownClientLoggedAt > SWITCH_RETRY_WINDOW_MS) {
            unknownClientLoggedAt = now
            val on = VpnSwitcher.currentAlwaysOn(ctx).orEmpty()
            log(
                ctx,
                LogCat.DIAG,
                "vpn is on but the client is unknown (always_on='${on.ifEmpty { "-" }}') " +
                    "-> cannot switch to ${groupTag(want)}",
            )
        }
        return SwitchDecision.UNKNOWN
    }
    if (running == want.vpnPackage) return SwitchDecision.NONE
    return requestSwitch(ctx, want, SystemClock.elapsedRealtime())
}
