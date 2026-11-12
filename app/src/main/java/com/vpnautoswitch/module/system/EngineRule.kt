package com.vpnautoswitch.module.system

import android.content.Context
import android.os.SystemClock
import com.vpnautoswitch.module.Config
import de.robv.android.xposed.XposedBridge

// 规则描述与运行时快照：只描述状态，不参与控制决策

internal fun Engine.log(ctx: Context?, message: String) {
    XposedBridge.log("[$TAG] $message")
    ModuleLog.append(ctx, message)
}

/** 带类别的日志：行首多一个 `[启动]` 这样的方括号，App 里可以按它筛 */
internal fun Engine.log(ctx: Context?, cat: LogCat, message: String) {
    log(ctx, "[${cat.tag}] $message")
}

/**
 * 日志和界面里怎么称呼一个 Wi-Fi。
 *
 * 默认遮成 `Ho***Fi`，设置里可以关掉脱敏。读配置走后文的 3 秒缓存，不影响日志性能；
 * 匹配逻辑用的仍是原值，这里只决定「写出来长什么样」。
 */
internal fun Engine.ssidForLog(ctx: Context?, ssid: String?): String {
    val full = try {
        ctx != null && ConfigRepo.getOrNull(ctx)?.fullSsid == true
    } catch (t: Throwable) {
        false
    }
    return SsidMask.of(ssid, full)
}

/**
 * 隧道连上的**墙钟**时刻；没连或不知道何时连上时返回 0。
 *
 * [connectedAt] 记的是 elapsedRealtime（不受用户改时间影响，用来算存活时长），
 * 而通知的正计时需要 System.currentTimeMillis()，两者在入口这里换算一次。
 */
private fun Engine.connectedSince(): Long {
    val at = connectedAt
    if (at == 0L) return 0L
    return System.currentTimeMillis() - (SystemClock.elapsedRealtime() - at)
}

/**
 * 一次规则判定的完整结果。
 *
 * [trace] 记的是「哪几项已经排除」——用户看到「该开却没开」时，第一个问题永远是
 * 「是不是被别的规则挡住了」。只给命中项回答不了这个问题。
 */
internal data class RuleVerdict(
    val rule: String,
    val detail: String,
    /** 形如 `已排除：暂停 · Wi-Fi关 · 打开即关闭`；第一项就命中时为空 */
    val trace: String,
)

/** 只描述当前命中的规则，不参与任何控制决策。 */
internal fun Engine.currentRule(cfg: Config): Pair<String, String> {
    val v = judge(cfg, null)
    return v.rule to v.detail
}

/** 判定链的文本：空表示第一项就命中，没有任何条件被排除 */
internal fun ruleTraceText(excluded: List<String>): String =
    if (excluded.isEmpty()) "" else "已排除：" + excluded.joinToString(" · ")

internal fun Engine.judge(cfg: Config, ctx: Context?): RuleVerdict {
    val excluded = ArrayList<String>(8)
    fun verdict(rule: String, detail: String) = RuleVerdict(rule, detail, ruleTraceText(excluded))

    if (!cfg.enabled || cfg.vpnPackage.isEmpty()) {
        return verdict("disabled", "模块已关闭或未选择 VPN 客户端")
    }
    excluded += "模块开关"
    if (automationPaused) {
        return verdict("paused", "快捷磁贴已暂停自动联动")
    }
    excluded += "暂停"
    if (wifiSuppressed) {
        return verdict(
            "wifiOff",
            currentSsid?.let { "Wi-Fi \"${ssidForLog(ctx, it)}\" 命中关闭规则" }
                ?: "命中关闭 Wi-Fi 规则",
        )
    }
    excluded += "Wi-Fi关"
    offAppForeground?.let { pkg ->
        return verdict("offTarget", "打开 $pkg 时保持断开")
    }
    excluded += "打开即关闭"
    if (isWifiOnActive(cfg)) {
        return verdict(
            "wifiOn",
            "Wi-Fi \"${ssidForLog(ctx, currentSsid)}\" 命中开启规则",
        )
    }
    excluded += "Wi-Fi开"
    if (!anyTargetAllowedOnNetwork(cfg)) {
        return verdict("networkDisabled", "当前网络类型已关闭自动开关")
    }
    excluded += "网络类型"
    if (userOff) {
        return verdict("userOff", "已手动关闭，等待重新打开目标应用")
    }
    excluded += "手动关闭"
    if (aliveTargets.isNotEmpty()) {
        val sample = aliveTargets.take(3).joinToString("、")
        val more = if (aliveTargets.size > 3) " 等 ${aliveTargets.size} 个" else ""
        return verdict("target", "目标应用在前台：$sample$more")
    }
    return verdict("idle", "当前没有目标应用或触发条件")
}

internal fun Engine.publishRuntime(ctx: Context?) {
    val context = ctx ?: return
    try {
        val cfg = ConfigRepo.get(context)
        val verdict = judge(cfg, context)
        val rule = verdict.rule
        val detail = verdict.detail
        // 通知自己按「规则 + 连接状态」去重，成本只有一次 Settings 读取；
        // 放在快照的节流之前，开关刚改完也能立刻生效，不用等下一次状态变化
        StatusNotification.sync(context, cfg, rule, detail, connectedSince())

        // 「命中了规则但没连上」时才有必要解释卡在哪。其余时候不去查包和设置，
        // 免得每次心跳都多做几次跨进程调用
        val connected = hasVpnTransport(context)
        val expectedOn = rule == "target" || rule == "wifiOn"
        val readiness = if (expectedOn && !connected) {
            ReadinessPolicy.assess(Packages.factsOf(context, cfg.vpnPackage))
        } else {
            ClientReadiness.READY
        }
        val blocked = if (expectedOn && !connected) {
            startBlock(context, cfg, readiness, connected)
        } else {
            null
        }

        // 快照里带时间戳，写一次内容就「变」一次；换成自己比对关键字段，
        // 没人看诊断时就不必每次前台回调都写一遍设置
        val blockKey = blocked?.let { "${it.text}/${it.needsUser}" }.orEmpty()
        val key = "$rule|$detail|$verdict.trace|$lastTransport|${lastForeground.orEmpty()}|$blockKey"
        val now = SystemClock.elapsedRealtime()
        if (key == lastRuntimeKey && now - lastRuntimeAt < RUNTIME_REWRITE_MS) return
        lastRuntimeKey = key
        lastRuntimeAt = now
        RuntimeDiagnostics.publishRuntime(
            ctx = context,
            activeRule = rule,
            detail = detail,
            transport = lastTransport,
            foreground = lastForeground,
            blocked = blocked?.text.orEmpty(),
            blockNeedsUser = blocked?.needsUser == true,
            readiness = readiness.name,
            attempt = if (expectedOn && !connected) maxOf(externalAttempts, reapplyCount) else 0,
            trace = verdict.trace,
            // 连上了就不用查设置；只有可能要给「从没连上过」下结论时才读一次，
            // 而且这句写在节流 return 之后，平时根本走不到
            everConnected = connected || ExternalControl.everConnected(context, cfg.vpnPackage),
        )
    } catch (t: Throwable) {
        // 诊断失败不能影响控制逻辑
    }
}

/**
 * 「规则要求开、隧道没起来」这一刻的卡点。
 *
 * 冷却与「在等客户端初始化」用的是模块自己的计时字段，不需要重新探测；
 * 「有没有成功连上过」读的是一份很小的设置，查一次够用。
 */
private fun Engine.startBlock(
    ctx: Context,
    cfg: Config,
    readiness: ClientReadiness,
    connected: Boolean,
): BlockMessage? {
    val now = SystemClock.elapsedRealtime()
    val external = ExternalControl.modeOf(ctx, cfg.vpnPackage) != null
    val interval = if (external) EXTERNAL_ONLY_GUARD_MS else REAPPLY_FIRST_MS
    val sinceApply = if (lastApplyAt == 0L) Long.MAX_VALUE else now - lastApplyAt
    return StartBlockPolicy.of(
        BlockInput(
            expectedOn = true,
            vpnConnected = connected,
            readiness = readiness,
            attempts = maxOf(externalAttempts, reapplyCount),
            cooldownMs = (interval - sinceApply).coerceAtLeast(0L),
            waitingClient = externalAttempts > 0 &&
                lastStartAt != 0L &&
                now - lastStartAt < EXTERNAL_INIT_GRACE_MS,
            everConnected = ExternalControl.everConnected(ctx, cfg.vpnPackage),
        ),
    )
}
