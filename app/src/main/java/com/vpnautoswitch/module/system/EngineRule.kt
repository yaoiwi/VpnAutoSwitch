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

/** 只描述当前命中的规则，不参与任何控制决策。 */
internal fun Engine.currentRule(cfg: Config): Pair<String, String> {
    if (!cfg.enabled || cfg.vpnPackage.isEmpty()) {
        return "disabled" to "模块已关闭或未选择 VPN 客户端"
    }
    if (automationPaused) {
        return "paused" to "快捷磁贴已暂停自动联动"
    }
    if (wifiSuppressed) {
        return "wifiOff" to (currentSsid?.let { "Wi-Fi \"$it\" 命中关闭规则" } ?: "命中关闭 Wi-Fi 规则")
    }
    offAppForeground?.let { pkg ->
        return "offTarget" to "打开 $pkg 时保持断开"
    }
    if (isWifiOnActive(cfg)) {
        return "wifiOn" to "Wi-Fi \"${currentSsid.orEmpty()}\" 命中开启规则"
    }
    if (!anyTargetAllowedOnNetwork(cfg)) {
        return "networkDisabled" to "当前网络类型已关闭自动开关"
    }
    if (userOff) {
        return "userOff" to "已手动关闭，等待重新打开目标应用"
    }
    if (aliveTargets.isNotEmpty()) {
        val sample = aliveTargets.take(3).joinToString("、")
        val more = if (aliveTargets.size > 3) " 等 ${aliveTargets.size} 个" else ""
        return "target" to "目标应用在前台：$sample$more"
    }
    return "idle" to "当前没有目标应用或触发条件"
}

internal fun Engine.publishRuntime(ctx: Context?) {
    val context = ctx ?: return
    try {
        val cfg = ConfigRepo.get(context)
        val (rule, detail) = currentRule(cfg)
        // 通知自己按「规则 + 连接状态」去重，成本只有一次 Settings 读取；
        // 放在快照的节流之前，开关刚改完也能立刻生效，不用等下一次状态变化
        StatusNotification.sync(context, cfg, rule, detail, connectedSince())
        // 快照里带时间戳，写一次内容就「变」一次；换成自己比对关键字段，
        // 没人看诊断时就不必每次前台回调都写一遍设置
        val key = "$rule|$detail|$lastTransport|${lastForeground.orEmpty()}"
        val now = SystemClock.elapsedRealtime()
        if (key == lastRuntimeKey && now - lastRuntimeAt < RUNTIME_REWRITE_MS) return
        lastRuntimeKey = key
        lastRuntimeAt = now
        RuntimeDiagnostics.publishRuntime(context, rule, detail, lastTransport, lastForeground)
    } catch (t: Throwable) {
        // 诊断失败不能影响控制逻辑
    }
}
