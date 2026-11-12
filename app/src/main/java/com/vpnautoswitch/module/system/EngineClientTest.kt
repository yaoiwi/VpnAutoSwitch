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
        // 手动测试成功就是用户明确选了这个方式，顺手固定住：
        // 不然下一次靠另一条路径连上，remember 就把这里改掉了
        ExternalControl.remember(ctx, pkg, mode)
        ExternalControl.pin(ctx, pkg, mode)
        RuntimeDiagnostics.publishClientTest(
            ctx, pkg, mode, "success",
            if (mode == "activity") "外部控制成功，已固定该方式" else "VpnService 启动成功，已固定该方式",
        )
        log(ctx, LogCat.START, "client test success: $pkg / $mode (pinned)")
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
