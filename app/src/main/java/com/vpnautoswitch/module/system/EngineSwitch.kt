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
 * @return true 表示已经开始关旧的；false 表示还在冷却里，这一拍保持现状。
 *   冷却期内不切也不代表放弃：目标应用还开着的话，下一次心跳自然会再判断一次。
 */
internal fun Engine.requestSwitch(ctx: Context, rule: VpnRule, now: Long): Boolean {
    if (now - lastSwitchAt < SWITCH_COOLDOWN_MS) return false
    lastSwitchAt = now
    pendingSwitchRuleId = rule.id
    pendingSwitchPkg = rule.vpnPackage
    pendingSwitchAt = now
    log(
        ctx,
        LogCat.START,
        "switch: ${activePkg.ifEmpty { "?" }} -> ${rule.vpnPackage} (${groupTag(rule)})",
    )
    turnOff("switch to ${groupTag(rule)}")
    return true
}

/**
 * 关闭链确认隧道没了之后，把待切换的组拉起来。
 *
 * 不在这里检查「是不是同一组」：requestSwitch 只在两个客户端不同的时候才发起，
 * 这里唯一要防的是「等待期间目标应用已经退出了」——那种情况再开就是凭空连一个
 * 用户已经不需要的 VPN。
 */
internal fun Engine.resumePendingSwitch(ctx: Context) {
    val ruleId = pendingSwitchRuleId ?: return
    val pkg = pendingSwitchPkg.orEmpty()
    pendingSwitchRuleId = null
    pendingSwitchPkg = null
    if (SystemClock.elapsedRealtime() - pendingSwitchAt > PENDING_SWITCH_TTL_MS) {
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
    // 触发它的应用已经不在前台、也没活在后台：别开了，等它再回来。
    // 前台优先——用户切回上一组的应用时，上一组的目标往往还留在 aliveTargets 里，
    // 只看后者会把已经不需要的那一组又拉起来
    val ssid = currentSsid
    val wifiHit = ssid != null && ssid in rule.wifiOn
    val fg = lastForeground
    val wanted = wifiHit || if (!fg.isNullOrEmpty()) {
        fg in rule.targets
    } else {
        aliveTargets.any { it in rule.targets }
    }
    if (!wanted) {
        log(ctx, LogCat.START, "switch to ${groupTag(rule)} dropped: 触发它的应用已经不在")
        return
    }
    log(ctx, LogCat.START, "switch: starting ${rule.vpnPackage} (${groupTag(rule)})")
    // 包名以配置里最新的为准：等待期间用户可能把这一组的客户端换掉了
    val target = if (pkg.isNotEmpty()) pkg else rule.vpnPackage
    ensureVpn(ctx, cfg.forClient(target, rule.id), rule = rule)
    startWatchdog()
}

/** 切换还欠着一条「开新的」没做完 */
internal fun Engine.hasPendingSwitch(): Boolean = pendingSwitchRuleId != null

internal fun Engine.clearPendingSwitch(): Boolean {
    val had = pendingSwitchRuleId != null
    pendingSwitchRuleId = null
    pendingSwitchAt = 0L
    return had
}
