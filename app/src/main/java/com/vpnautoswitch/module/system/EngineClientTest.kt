package com.vpnautoswitch.module.system

import android.content.Context

// 客户端启动方式测试：外部控制 / 直接起 VpnService，测完固定

/**
 * App 触发的客户端启动方式测试。
 *
 * 只测试“外部控制 Activity”和“直接启动 VpnService”，不测试 always-on：
 * 测试 always-on 会改写系统常驻 VPN 设置，可能打断用户当前连接。
 */
internal fun Engine.testClient(ctx: Context, pkg: String, mode: String) {
    val token = ++clientTestToken
    val cfg = ConfigRepo.get(ctx)
    when {
        pkg.isEmpty() -> {
            RuntimeDiagnostics.publishClientTest(ctx, pkg, mode, "failure", "没有选择 VPN 客户端")
            return
        }
        cfg.vpnPackage != pkg -> {
            RuntimeDiagnostics.publishClientTest(
                ctx, pkg, mode, "failure", "VPN 客户端已变化，请重新打开适配向导"
            )
            return
        }
        mode != "activity" && mode != "service" -> {
            RuntimeDiagnostics.publishClientTest(ctx, pkg, mode, "failure", "未知的测试方式")
            return
        }
        hasVpnTransport(ctx) -> {
            RuntimeDiagnostics.publishClientTest(
                ctx, pkg, mode, "failure", "当前已有 VPN 连接，请先断开后再测试"
            )
            return
        }
    }

    RuntimeDiagnostics.publishClientTest(
        ctx, pkg, mode, "running",
        if (mode == "activity") "正在请求客户端外部控制…" else "正在启动 VpnService…",
    )
    ExternalControl.resetProbe(pkg)
    // 用户手动测过一次，之前的「静默起不来」记录可能已经过时（换了节点、升级了客户端），
    // 清掉让下一轮重新从静默开始试
    ExternalControl.clearSilentFails(ctx, pkg)
    log(ctx, LogCat.START, "client test start: $pkg / $mode")
    // 手动测试和引擎上一轮的自动尝试是两件事：不先清掉，隧道起来之后
    // 会被记到那次尝试的方式上（日志 21:33:35 就是这么写出 service 的）
    markPendingExternalMode(null)

    // startMode 会回报**实际走通**的那条路：测试「外部控制」时如果客户端没有
    // START_CLASH、退回 VpnService 连上了，固定的就该是 service，否则下一轮
    // 又按 activity 去试一遍（多绕一步，日志也会误导）
    val actualMode = if (mode == "activity") {
        ExternalControl.startMode(ctx, pkg)
    } else {
        if (ExternalControl.startVpnService(ctx, pkg)) "service" else null
    }
    if (actualMode == null) {
        val detail = if (mode == "activity") {
            "客户端没有可用的外部控制入口"
        } else {
            "无法启动客户端的 VpnService"
        }
        RuntimeDiagnostics.publishClientTest(ctx, pkg, mode, "failure", detail)
        log(ctx, LogCat.START, "client test failed to start: $detail")
        return
    }

    pollClientTest(ctx, pkg, actualMode, token, 0)
}

internal fun Engine.pollClientTest(
    ctx: Context,
    pkg: String,
    mode: String,
    token: Int,
    attempt: Int,
) {
    if (token != clientTestToken) return
    if (hasVpnTransport(ctx)) {
        // 一次测试成功只说明「此刻有隧道」，不说明是这次启动建起来的：service 方式
        // 尤其不可靠——`startForegroundService` 返回非空只表示系统收下了这次调用，
        // FlClash 的服务起来后并不建隧道（21:19:30 started → 21:19:32「进程仍在，
        // 等隧道」→ 之后一直没有隧道）。
        // 1.9.21 把这种「成功」当成结论固定下来，引擎此后只发服务启动请求，
        // 把唯一能用的外部控制入口挡在外面（21:34:25 起连续三次 rejected）。
        // 所以：能查到外部控制入口的客户端，服务方式只记不固定。
        ExternalControl.remember(ctx, pkg, mode)
        val pinIt = mode == "activity" || !ExternalControl.hasExternalStart(ctx, pkg)
        if (pinIt) ExternalControl.pin(ctx, pkg, mode)
        // 这次连接是这个测试拉起来的，别再让引擎把它记到某次自动尝试头上
        markPendingExternalMode(null)
        RuntimeDiagnostics.publishClientTest(
            ctx, pkg, mode, "success",
            when {
                !pinIt -> "VpnService 启动成功，已记住；该客户端有外部控制入口，服务方式不固定"
                mode == "activity" -> "外部控制成功，已固定该方式"
                else -> "VpnService 启动成功，已固定该方式"
            },
        )
        log(
            ctx,
            LogCat.START,
            "client test success: $pkg / $mode (${if (pinIt) "pinned" else "remembered only"})",
        )
        return
    }
    if (attempt >= 30) {
        RuntimeDiagnostics.publishClientTest(
            ctx, pkg, mode, "failure", "15 秒内没有检测到 VPN transport",
        )
        log(ctx, LogCat.START, "client test timeout: $pkg / $mode")
        return
    }
    handler.postDelayed({
        pollClientTest(ctx, pkg, mode, token, attempt + 1)
    }, 500L)
}

internal fun Engine.forgetClientMode(ctx: Context, pkg: String) {
    if (pkg.isEmpty()) return
    ExternalControl.forget(ctx, pkg)
    // 「清除记忆」要连固定一起清，否则界面上显示「未记忆」，实际却还在按固定方式跑
    ExternalControl.unpin(ctx, pkg)
    if (ModuleState.prewarmUnsupported(ctx) == pkg) ModuleState.setPrewarmUnsupported(ctx, null)
    RuntimeDiagnostics.publishClientTest(ctx, pkg, "", "idle", "已清除记住的启动方式")
    log(ctx, LogCat.START, "client mode forgotten: $pkg")
}
