package com.vpnautoswitch.module.ui

import android.os.Build
import com.vpnautoswitch.module.BuildConfig
import com.vpnautoswitch.module.Config
import com.vpnautoswitch.module.system.DeviceInfo
import com.vpnautoswitch.module.system.HookHealth
import com.vpnautoswitch.module.system.LogPolicy
import com.vpnautoswitch.module.system.hookReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

internal enum class CheckStatus { OK, WARN, FAIL, UNKNOWN }

/**
 * 一条检查项后面能点的动作。
 *
 * 诊断以前只会「说」，用户读完还得自己找设置在哪。能点一步到位的，就不要写成
 * 「请到设置里……」——这类文案在这个 App 里已经堆得太多了。
 */
internal sealed class CheckAction {
    /** 跳到系统「设置 → VPN」 */
    data object OpenVpnSettings : CheckAction()

    /** 打开客户端的系统应用详情页（自启动、电池优化都在里面） */
    data class OpenAppDetails(val pkg: String) : CheckAction()

    /** 打开本机的 LSPosed 管理器 */
    data object OpenLsposed : CheckAction()

    /** 打开「客户端适配」，测一次启动方式 */
    data object OpenClientAdapter : CheckAction()

    /** 滚到下面的运行日志 */
    data object OpenLog : CheckAction()
}

internal data class CheckItem(
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val action: CheckAction? = null,
)

internal object DiagnosticsReport {

    /** 开启超过这么久就认为偏慢，在诊断里标黄 */
    private const val SLOW_ON_MS = 5_000L

    /** 通知内容超过这么久没更新，就认为链路可能断了 */
    private const val NOTIFY_STALE_MS = 30 * 60_000L

    fun buildChecks(diagnostics: Diagnostics, config: Config): List<CheckItem> {
        val now = System.currentTimeMillis()
        val checks = mutableListOf<CheckItem>()

        checks += when (diagnostics.moduleState) {
            DiagnosticsPolicy.ModuleState.CURRENT -> CheckItem(
                "模块运行",
                CheckStatus.OK,
                "心跳 ${ago(diagnostics.heartbeat, now)}，模块版本 v${diagnostics.moduleVersion} 与 App 一致",
            )
            DiagnosticsPolicy.ModuleState.OLD_CODE -> CheckItem(
                "模块运行",
                CheckStatus.WARN,
                "App 是 v${BuildConfig.VERSION_CODE}，system_server 仍加载 v${diagnostics.moduleVersion}。" +
                    "重启手机后新代码才会生效",
                CheckAction.OpenLsposed,
            )
            DiagnosticsPolicy.ModuleState.STALE -> CheckItem(
                "模块运行",
                CheckStatus.FAIL,
                "上次心跳在 ${ago(diagnostics.heartbeat, now)}，超过 90 秒没有新心跳。" +
                    "检查 LSPosed 是否启用模块、作用域是否只有「系统框架」",
                CheckAction.OpenLsposed,
            )
            DiagnosticsPolicy.ModuleState.NEVER -> CheckItem(
                "模块运行",
                CheckStatus.FAIL,
                "从未收到心跳。请在 LSPosed 中启用模块，作用域只勾「系统框架」，然后重启手机",
                CheckAction.OpenLsposed,
            )
        }

        checks += CheckItem(
            "LSPosed 作用域",
            if (diagnostics.moduleState == DiagnosticsPolicy.ModuleState.CURRENT) {
                CheckStatus.OK
            } else {
                CheckStatus.UNKNOWN
            },
            "只能通过系统框架心跳推断；请在 LSPosed 里确认本模块只勾选了「系统框架」",
            CheckAction.OpenLsposed,
        )

        checks += vpnClientCheck(diagnostics)

        checks += CheckItem(
            "当前网络与规则",
            when {
                diagnostics.activeRule.isEmpty() -> CheckStatus.UNKNOWN
                diagnostics.ruleUpdatedAt > 0 &&
                    now - diagnostics.ruleUpdatedAt > DiagnosticsPolicy.HEARTBEAT_FRESH_MS ->
                    CheckStatus.WARN
                else -> CheckStatus.OK
            },
            buildString {
                append(diagnostics.network)
                append(" · ").append(ssidLabel(diagnostics, config))
                if (diagnostics.ssidState == "masked") {
                    append("\n读到的是占位值：系统对 SSID 做了脱敏，「指定 Wi-Fi」规则可能匹配不上")
                }
                if (diagnostics.ruleDetail.isNotEmpty()) {
                    append("\n当前：${ruleLabel(diagnostics.activeRule)}，${diagnostics.ruleDetail}")
                } else {
                    append("\n模块还没有上报当前规则")
                }
            },
        )

        checks += vpnTunnelCheck(diagnostics)
        checks += timingCheck(diagnostics, now)

        val needsAlwaysOn = diagnostics.activeRule == "target" || diagnostics.activeRule == "wifiOn"
        val alwaysOnAbsent = diagnostics.alwaysOnApp.isNullOrEmpty()
        // 空着不等于坏事：外部控制方式不写这项，隧道已经连着时也用不上
        val alwaysOnExcused = alwaysOnAbsent && DiagnosticsPolicy.alwaysOnNotNeeded(
            clientMode = diagnostics.clientMode,
            vpnConnected = diagnostics.vpnConnected,
            activeRule = diagnostics.activeRule,
            takenByModule = diagnostics.takenByModule,
            needsAlwaysOn = needsAlwaysOn,
        )
        checks += CheckItem(
            "始终开启的 VPN",
            when {
                alwaysOnExcused -> CheckStatus.OK
                // 规则还没上报（空）时仍留 UNKNOWN，不能替模块许诺
                alwaysOnAbsent -> CheckStatus.UNKNOWN
                needsAlwaysOn && diagnostics.alwaysOnApp != config.vpnPackage -> CheckStatus.WARN
                else -> CheckStatus.OK
            },
            buildString {
                append("系统当前指向：")
                append(diagnostics.alwaysOnApp?.takeIf { it.isNotEmpty() } ?: "未设置")
                if (alwaysOnAbsent) {
                    when {
                        diagnostics.clientMode == "activity" ->
                            append("\n启动方式是外部控制，模块不写系统常驻设置，属于正常状态")
                        diagnostics.vpnConnected && needsAlwaysOn ->
                            append("\n隧道已经建立，常驻设置没写入不影响当前连接")
                        diagnostics.activeRule.isNotEmpty() &&
                            !diagnostics.takenByModule && !needsAlwaysOn ->
                            append("\n模块未接管，当前规则也不需要常驻 VPN，属于正常状态")
                    }
                }
                if (needsAlwaysOn && diagnostics.alwaysOnApp != config.vpnPackage && !alwaysOnExcused) {
                    append("\n规则要求开启 VPN，但设置尚未指向 ${config.vpnPackage}，可能正在切换")
                }
            },
            // 设置没指向客户端时，下一步就是去系统设置里改它
            if (needsAlwaysOn && !alwaysOnAbsent && diagnostics.alwaysOnApp != config.vpnPackage) {
                CheckAction.OpenVpnSettings
            } else {
                null
            },
        )

        checks += hookCheck(diagnostics)
        checks += configSourceCheck(diagnostics, now)
        checks += failureCheck(diagnostics, now)
        checks += notificationCheck(config, diagnostics, now)
        checks += configCheck(config)

        return checks
    }

    /**
     * 生成可粘贴的纯文本报告。
     *
     * [fullLog] 为 true 时带全部日志（日志本身最多 500 行），用于「一键复制」；
     * 自检弹窗里的复制仍然只带最近 60 行，够说明问题又不至于太长。
     */
    fun buildText(
        diagnostics: Diagnostics,
        config: Config,
        fullLog: Boolean = false,
    ): String {
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        // 拿不到快照、或者快照是升级前的旧数据时，宁可不判 hook，也不给错的结论
        val hookHealth = diagnostics.hooks
            ?.takeIf {
                DiagnosticsPolicy.hookSnapshotUsable(
                    diagnostics.hookSnapshotVersion,
                    BuildConfig.VERSION_CODE,
                )
            }
            ?.let { hookReport(it).health }
        return buildString {
            appendLine("VpnAutoSwitch 状态报告")
            appendLine("生成：${fmt.format(Date(now))}")
            appendLine(
                "结论：" + DiagnosticsPolicy.conclusion(
                    DiagnosticsPolicy.VerdictInput(
                        moduleState = diagnostics.moduleState,
                        hookHealth = hookHealth,
                        vpnInstalled = diagnostics.vpnInstalled,
                        vpnHasService = diagnostics.vpnHasService,
                        vpnConnected = diagnostics.vpnConnected,
                        alwaysOnApp = diagnostics.alwaysOnApp,
                        vpnPackage = config.vpnPackage,
                        failureStreak = diagnostics.lastFailureStreak,
                        paused = diagnostics.paused,
                        failureStage = diagnostics.lastFailureStage,
                        failureAgeMs = if (diagnostics.lastFailureAt > 0L) {
                            (now - diagnostics.lastFailureAt).coerceAtLeast(0L)
                        } else {
                            Long.MAX_VALUE
                        },
                        // 规则要求断开时，「没连上」本来就是对的，不能算异常
                        expectsOn = DiagnosticsPolicy.expectedVpn(diagnostics.activeRule) !=
                            DiagnosticsPolicy.ExpectedVpn.OFF,
                        clientMode = diagnostics.clientMode,
                    )
                )
            )
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ROM：${Build.DISPLAY}${DeviceInfo.romVendor()?.let { " · $it" }.orEmpty()}")
            appendLine("App：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("模块：v${diagnostics.moduleVersion} · ${moduleStateLabel(diagnostics.moduleState)}")
            appendLine(
                "心跳：" + if (diagnostics.heartbeat > 0L) {
                    "${fmt.format(Date(diagnostics.heartbeat))}（${ago(diagnostics.heartbeat, now)}）"
                } else {
                    "从未"
                }
            )
            appendLine("网络：${diagnostics.network} · SSID=${ssidLabel(diagnostics, config)}")
            appendLine("VPN 包：${config.vpnPackage.ifEmpty { "未选择" }}")
            // 固定写一行：排查「拉不起来」时第一个要看的就是它，
            // 以前只能从 external-only: restart VPN service 这类日志反推
            appendLine(
                "启动方式：" + when (diagnostics.clientMode) {
                    "activity" -> "外部控制（activity）"
                    "service" -> "VpnService（service）"
                    else -> "未记忆，按顺序尝试"
                }
            )
            appendLine("安装：${yesNo(diagnostics.vpnInstalled)} · 启用：${yesNo(diagnostics.vpnEnabled)} · VpnService：${yesNo(diagnostics.vpnHasService)}")
            if (diagnostics.readiness.isNotEmpty() && diagnostics.readiness != "READY") {
                appendLine("客户端状态：${readinessLabel(diagnostics.readiness)}")
            }
            appendLine("隧道：${if (diagnostics.vpnConnected) "已连接" else "未连接"}")
            appendLine("always-on：${diagnostics.alwaysOnApp ?: "未设置"} · 接管：${if (diagnostics.takenByModule) "是" else "否"}")
            appendLine("当前规则：${ruleLabel(diagnostics.activeRule)} · ${diagnostics.ruleDetail}")
            // 「该开却没开」时先排除「被别的规则挡住」这一种可能，比逐项检查快得多
            if (diagnostics.ruleTrace.isNotEmpty()) {
                appendLine("判定链：${diagnostics.ruleTrace}")
            }
            // 规则命中却没连上时，这一行比后面十几项检查都先被看到
            if (diagnostics.blocked.isNotBlank()) {
                appendLine("卡在：${diagnostics.blocked}")
            }
            appendLine("规则更新时间：${if (diagnostics.ruleUpdatedAt > 0) fmt.format(Date(diagnostics.ruleUpdatedAt)) else "未上报"}")
            appendLine("最近失败：${failureLabel(diagnostics)}")
            appendLine(
                "最近耗时：开启 " + if (diagnostics.lastOnMs > 0L) {
                    formatMs(diagnostics.lastOnMs)
                } else {
                    "未记录"
                } + " · 关闭 " + if (diagnostics.lastOffMs > 0L) {
                    formatMs(diagnostics.lastOffMs)
                } else {
                    "未记录"
                }
            )
            appendLine(
                "上次关闭：" + if (diagnostics.lastOffReason.isNotBlank()) {
                    DiagnosticsPolicy.offReasonLabel(diagnostics.lastOffReason)
                } else {
                    "未记录"
                }
            )
            appendLine(
                "状态通知：" + when {
                    !config.statusNotification -> "未启用"
                    !diagnostics.notifyPermission -> "无通知权限"
                    diagnostics.notifyAt > 0L -> "最近更新 ${ago(diagnostics.notifyAt, now)}"
                    else -> "模块尚未上报"
                }
            )
            appendLine("目标应用：${config.targets.size} · 打开即关闭：${config.offTargets.size} · Wi-Fi 开/关：${config.wifiOn.size}/${config.wifiOff.size}")
            appendLine("配置来源：" + when {
                diagnostics.configSource.isEmpty() -> "未上报"
                diagnostics.configSource == "provider" -> "实时（provider）"
                diagnostics.configSource == "mirror" ->
                    "镜像（写于 ${ago(diagnostics.configMirrorAt, now)}）"
                else -> diagnostics.configSource
            })
            appendLine("Hook：" + if (diagnostics.hooks == null) {
                "未上报"
            } else {
                diagnostics.hooks.asMap().entries.joinToString(" ") { "${it.key}=${it.value}" } +
                    "（快照 v${diagnostics.hookSnapshotVersion}）"
            })
            appendLine()
            appendLine("检查结果：")
            buildChecks(diagnostics, config).forEach {
                appendLine("[${it.status.name}] ${it.title}：${it.detail.replace("\n", " / ")}")
            }
            appendLine()
            val logLines = diagnostics.log.lines().filter { it.isNotBlank() }
            // 两种模式都走 tailWithHeader：设备指纹始终留在第一行，
            // 否则贴出去的日志看不出是哪个机器
            if (fullLog) {
                appendLine("完整日志（${logLines.size} 行，最新在最后）：")
                append(
                    LogPolicy
                        .tailWithHeader(logLines, logLines.size)
                        .joinToString("\n")
                )
            } else {
                appendLine("最近日志：")
                append(
                    LogPolicy
                        .tailWithHeader(logLines, 60)
                        .joinToString("\n")
                )
            }
        }
    }

    /**
     * 状态通知这条链路是否通。
     *
     * 模块写内容、App 渲染，任何一环断了通知都不会出现，而且失败时是静默的——
     * 三条判据（权限、有没有上报过、多久没更新）刚好能把三种断点分开。
     */
    private fun notificationCheck(
        config: Config,
        diagnostics: Diagnostics,
        now: Long,
    ): CheckItem {
        if (!config.statusNotification) {
            return CheckItem(
                "状态通知",
                CheckStatus.OK,
                "未启用（默认关闭，不影响自动开关）",
            )
        }
        if (!diagnostics.notifyPermission) {
            return CheckItem(
                "状态通知",
                CheckStatus.FAIL,
                "已启用，但本应用没有通知权限，通知不会出现。" +
                    "打开应用时会请求授权，也可以去系统设置里手动打开",
            )
        }
        return when {
            diagnostics.notifyAt <= 0L -> CheckItem(
                "状态通知",
                CheckStatus.WARN,
                "已启用，但模块还没有上报过通知内容。切一次前台应用再看",
            )
            now - diagnostics.notifyAt > NOTIFY_STALE_MS -> CheckItem(
                "状态通知",
                CheckStatus.WARN,
                "最后一次更新在 ${ago(diagnostics.notifyAt, now)}，超过 30 分钟没变化。" +
                    "如果刚用过目标应用，说明广播没有送达 App",
            )
            !diagnostics.notifyOn -> CheckItem(
                "状态通知",
                CheckStatus.OK,
                "模块已撤掉通知（当前不满足显示条件）",
            )
            else -> CheckItem(
                "状态通知",
                CheckStatus.OK,
                "最近一次更新在 ${ago(diagnostics.notifyAt, now)} · " +
                    diagnostics.notifyText.ifEmpty { "（无内容）" },
            )
        }
    }

    private fun vpnClientCheck(diagnostics: Diagnostics): CheckItem {
        val pkg = diagnostics.vpnPackage
        if (pkg.isEmpty()) {
            return CheckItem("VPN 客户端", CheckStatus.FAIL, "还没有选择 VPN 客户端")
        }
        // 模块报的就绪状态比 App 自己查的两三个布尔更全（多一项「被冻结 / 被强停过」），
        // 而且它是在真正动手前后观察的，和失败记录对得上
        val readiness = diagnostics.readiness
        if (readiness.isNotEmpty() && readiness != "READY") {
            val advice = DiagnosticsPolicy.readinessAdvice(readiness)
            return CheckItem(
                "VPN 客户端",
                when (readiness) {
                    "NOT_INSTALLED", "NO_VPN_SERVICE" -> CheckStatus.FAIL
                    // 被强停是模块自己会清的，不算问题，只是解释一下
                    "STOPPED" -> CheckStatus.OK
                    else -> CheckStatus.WARN
                },
                buildString {
                    append(pkg).append(" · ").append(readinessLabel(readiness))
                    if (advice.isNotEmpty()) append("\n").append(advice)
                },
                when (readiness) {
                    "DISABLED", "SUSPENDED" -> CheckAction.OpenAppDetails(pkg)
                    "NO_VPN_SERVICE" -> CheckAction.OpenClientAdapter
                    else -> null
                },
            )
        }
        return when {
            diagnostics.vpnInstalled != true -> CheckItem(
                "VPN 客户端",
                CheckStatus.FAIL,
                "$pkg 未安装，或无法查询。重新选择客户端后重试",
            )
            diagnostics.vpnEnabled == false -> CheckItem(
                "VPN 客户端",
                CheckStatus.WARN,
                "$pkg 已被系统禁用，请在应用信息里启用",
                CheckAction.OpenAppDetails(pkg),
            )
            diagnostics.vpnHasService == false -> CheckItem(
                "VPN 客户端",
                CheckStatus.WARN,
                "$pkg 没有声明 VpnService，系统无法用它建立隧道",
                CheckAction.OpenClientAdapter,
            )
            else -> CheckItem(
                "VPN 客户端",
                CheckStatus.OK,
                "$pkg 已安装、启用并声明了 VpnService",
            )
        }
    }

    private fun readinessLabel(name: String): String = when (name) {
        "NOT_INSTALLED" -> "未安装"
        "DISABLED" -> "被系统禁用"
        "NO_VPN_SERVICE" -> "没有声明 VpnService"
        "STOPPED" -> "被强停过（模块会在启动前自动清掉）"
        "SUSPENDED" -> "被系统冻结"
        "UNKNOWN" -> "状态未知"
        else -> name
    }

    private fun vpnTunnelCheck(diagnostics: Diagnostics): CheckItem {
        val actual = if (diagnostics.vpnConnected) "已连接" else "未连接"
        if (diagnostics.activeRule.isEmpty()) {
            return CheckItem("隧道状态", CheckStatus.UNKNOWN, "模块还没有上报规则；系统当前 $actual")
        }
        return when (DiagnosticsPolicy.expectedVpn(diagnostics.activeRule)) {
            DiagnosticsPolicy.ExpectedVpn.ON -> CheckItem(
                "隧道状态",
                if (diagnostics.vpnConnected) CheckStatus.OK else CheckStatus.WARN,
                buildString {
                    if (diagnostics.vpnConnected) {
                        append("规则要求开启，系统 VPN transport 已存在")
                    } else {
                        append("规则要求开启，但系统没有 VPN transport。可能仍在连接，或客户端没有建立隧道")
                        // 模块报的卡点比这句更具体：是在冷却、在等客户端，还是要用户动手
                        if (diagnostics.blocked.isNotBlank()) {
                            append("\n卡在：").append(diagnostics.blocked)
                        }
                    }
                },
                // 没连上时最有用的下一步是去适配页确认启动方式
                if (diagnostics.vpnConnected) null else CheckAction.OpenClientAdapter,
            )
            DiagnosticsPolicy.ExpectedVpn.OFF -> CheckItem(
                "隧道状态",
                if (diagnostics.vpnConnected) CheckStatus.WARN else CheckStatus.OK,
                if (diagnostics.vpnConnected) {
                    "规则要求断开，但系统仍有 VPN transport。可能是其他 VPN，或关闭尚未完成"
                } else {
                    "规则要求断开，系统当前没有 VPN transport"
                },
            )
            DiagnosticsPolicy.ExpectedVpn.UNKNOWN -> CheckItem(
                "隧道状态",
                if (diagnostics.vpnConnected) CheckStatus.OK else CheckStatus.UNKNOWN,
                "系统当前 $actual；当前规则不强制开启或关闭",
            )
        }
    }

    /**
     * 最近一次开启 / 关闭的耗时。
     *
     * 「打开应用要等半天才有网」只能靠体感，看不出慢在客户端建隧道还是模块在重试；
     * 模块每完成一次开关都会把耗时写进设置，这里直接摊开给用户看。
     */
    private fun timingCheck(diagnostics: Diagnostics, now: Long): CheckItem {
        if (diagnostics.lastOnMs <= 0L && diagnostics.lastOffMs <= 0L) {
            return CheckItem("开关耗时", CheckStatus.UNKNOWN, "还没有记录到完整的开关过程")
        }
        val slow = diagnostics.lastOnMs >= SLOW_ON_MS
        return CheckItem(
            "开关耗时",
            if (slow) CheckStatus.WARN else CheckStatus.OK,
            buildString {
                append("上次开启 ")
                append(if (diagnostics.lastOnMs <= 0L) "未记录" else
                    "${formatMs(diagnostics.lastOnMs)}（${ago(diagnostics.lastOnAt, now)}）")
                append(" · 上次关闭 ")
                append(if (diagnostics.lastOffMs <= 0L) "未记录" else
                    "${formatMs(diagnostics.lastOffMs)}（${ago(diagnostics.lastOffAt, now)}）")
                if (slow) {
                    append("\n开启偏慢：看运行日志里是客户端建隧道慢，还是模块在反复重试")
                }
                if (diagnostics.lastOffReason.isNotBlank()) {
                    append("\n上次关闭原因：")
                    append(DiagnosticsPolicy.offReasonLabel(diagnostics.lastOffReason))
                }
            },
        )
    }

    private fun formatMs(ms: Long): String =
        if (ms < 1_000L) "$ms 毫秒" else String.format(Locale.CHINA, "%.1f 秒", ms / 1000.0)

    private fun failureCheck(diagnostics: Diagnostics, now: Long): CheckItem {
        if (diagnostics.lastFailureAt <= 0L) {
            return CheckItem("最近失败", CheckStatus.OK, "没有记录到失败")
        }
        val age = (now - diagnostics.lastFailureAt).coerceAtLeast(0L)
        // 失败记录跨天保留，太旧的只能当参考——否则「六小时前关 VPN 没成功」
        // 会被当成现在拉不起客户端，用户照着去改自启和电池优化，白折腾
        val recent = DiagnosticsPolicy.failureIsRecent(age)
        val base = "${failureLabel(diagnostics)}（${ago(diagnostics.lastFailureAt, now)}）"
        val hints = mutableListOf<String>()
        // 模块算出来的归因优先：它能看到 startForegroundService 的返回值、两秒后进程还在不在，
        // 以及这个客户端有没有成功连上过。App 只能从「阶段 + 次数」猜，猜出来必然更粗
        val moduleHint = diagnostics.lastFailureHint.takeIf { recent && it.isNotBlank() }
        if (moduleHint != null) {
            hints += moduleHint
        } else if (recent && DiagnosticsPolicy.shouldSuggestExternalControl(
                stage = diagnostics.lastFailureStage,
                streak = diagnostics.lastFailureStreak,
                clientMode = diagnostics.clientMode,
            )
        ) {
            hints += if (diagnostics.lastFailureStage == "stop") {
                "这个客户端被固定成 VpnService 方式，模块只能用系统设置去关，关不掉时不会改走外部控制。" +
                    "到首页的「客户端适配」点一次「测试外部控制」，成功后会自动改用这种方式"
            } else {
                "这个客户端被固定成 VpnService 方式，模块不会改走外部控制。" +
                    "到首页的「客户端适配」点一次「测试外部控制」，成功后会自动改用这种方式"
            }
        }
        // 上一条已经说过「换方式」就不再重复；这里只在还没提过时补一句，
        // 说明为什么「设置写了/没写」都连不上
        if (hints.isEmpty() && recent && DiagnosticsPolicy.serviceCannotTunnel(
                clientMode = diagnostics.clientMode,
                vpnPackage = diagnostics.vpnPackage,
                alwaysOnApp = diagnostics.alwaysOnApp,
                vpnConnected = diagnostics.vpnConnected,
                vpnHasService = diagnostics.vpnHasService,
            )
        ) {
            hints += if (diagnostics.alwaysOnApp.isNullOrEmpty()) {
                // 被记过启动方式时模块不写常驻设置，所以这里不能说「常驻 VPN 已指向客户端」
                "这个客户端被记成 VpnService 方式，模块只会直接启动它的服务（不写常驻设置），" +
                    "隧道一直没建立。到「客户端适配」测一次外部控制，成功后会自动改用这种方式"
            } else {
                "系统常驻 VPN 已经指向客户端，隧道却一直没建立。" +
                    "这个客户端可能只认自己的启动广播，到「客户端适配」测一次外部控制"
            }
        }
        return CheckItem(
            "最近失败",
            if (recent) CheckStatus.WARN else CheckStatus.OK,
            buildString {
                append(base)
                if (!recent) append("，是较早前的记录，仅供参考")
                hints.forEach { append("\n").append(it) }
            },
            when {
                // 归因里说了「换启动方式」，按钮就把它送到那儿
                recent && diagnostics.lastFailureCause == "NO_TUNNEL" &&
                    diagnostics.clientMode == "service" -> CheckAction.OpenClientAdapter
                // 被系统挡住的，下一步是去应用信息放开自启动 / 后台限制
                recent && diagnostics.lastFailureCause in SELF_START_CAUSES ->
                    CheckAction.OpenAppDetails(diagnostics.lastFailurePackage)
                recent && hints.isNotEmpty() -> CheckAction.OpenClientAdapter
                recent -> CheckAction.OpenLog
                else -> null
            },
        )
    }

    /** 这两类失败的解法和「自启动 / 后台活动」有关，按钮直接送到应用信息页 */
    private val SELF_START_CAUSES = setOf("REJECTED", "PROCESS_GONE", "NOT_READY")

    private fun configCheck(config: Config): CheckItem {
        val empty = config.targets.isEmpty() &&
            config.offTargets.isEmpty() &&
            config.wifiOn.isEmpty() &&
            config.wifiOff.isEmpty()
        return if (empty) {
            CheckItem("触发条件", CheckStatus.WARN, "还没有配置任何目标应用或 Wi-Fi 规则")
        } else {
            CheckItem(
                "触发条件",
                CheckStatus.OK,
                "目标 ${config.targets.size} 个 · 打开即关闭 ${config.offTargets.size} 个 · " +
                    "Wi-Fi 开 ${config.wifiOn.size} 个 / 关 ${config.wifiOff.size} 个",
            )
        }
    }

    /**
     * system_server 里各类 hook 有没有真的挂上。
     *
     * 「模块已激活」只代表 LSPosed 加载了模块，某些 ROM 上关键方法找不到时，
     * 模块会静默失效——自动开关完全不工作，但心跳和版本都正常。以前只能翻日志里
     * 那一行汇总，现在按重要性分级直接摆在自检里。
     */
    private fun hookCheck(diagnostics: Diagnostics): CheckItem {
        val title = "Hook 挂载"
        if (diagnostics.hooks == null) {
            return CheckItem(
                title,
                CheckStatus.UNKNOWN,
                "模块还没上报 hook 挂载结果。升级过版本的话，重启手机后才有新数据",
                CheckAction.OpenLog,
            )
        }
        if (!DiagnosticsPolicy.hookSnapshotUsable(
                diagnostics.hookSnapshotVersion,
                BuildConfig.VERSION_CODE,
            )
        ) {
            return CheckItem(
                title,
                CheckStatus.UNKNOWN,
                "这份 hook 快照来自旧版本模块（v${diagnostics.hookSnapshotVersion}），" +
                    "App 是 v${BuildConfig.VERSION_CODE}。重启手机后重新检测",
                CheckAction.OpenLog,
            )
        }
        val report = hookReport(diagnostics.hooks)
        return when (report.health) {
            HookHealth.BROKEN -> CheckItem(
                title,
                CheckStatus.FAIL,
                "关键 hook 一个都没挂上：${report.missingCritical.joinToString("、")}。" +
                    "模块收不到应用切换或进程退出，自动开关不会工作。" +
                    "确认 LSPosed 作用域只勾「系统框架」，并重启手机",
                CheckAction.OpenLsposed,
            )
            HookHealth.DEGRADED -> CheckItem(
                title,
                CheckStatus.WARN,
                "${report.degraded.joinToString("、")}没挂上。这个功能会降级：" +
                    "比如划掉任务后要等进程被系统回收才断开 VPN。" +
                    cosmeticHint(report.cosmetic),
                CheckAction.OpenLog,
            )
            HookHealth.OK -> CheckItem(
                title,
                CheckStatus.OK,
                "前台检测、进程死亡、划掉任务等核心 hook 均已挂载。" +
                    cosmeticHint(report.cosmetic),
            )
            HookHealth.UNKNOWN -> CheckItem(
                title,
                CheckStatus.UNKNOWN,
                "没有可用的 hook 挂载数据，重启手机后重新检测",
                CheckAction.OpenLog,
            )
        }
    }

    private fun cosmeticHint(cosmetic: List<String>): String =
        if (cosmetic.isEmpty()) ""
        else " 可选：${cosmetic.joinToString("、")}未挂载，不影响自动开关。"

    /**
     * 模块现在用的配置是从哪来的。
     *
     * 开机早期 App 进程起不来、只能读上次的镜像是正常的；但如果一直退不回
     * provider，用户刚改的设置就没同步到 system_server，得提示他打开一次本应用。
     */
    private fun configSourceCheck(diagnostics: Diagnostics, now: Long): CheckItem {
        val title = "配置来源"
        return when (
            DiagnosticsPolicy.configSourceStatus(
                diagnostics.configSource,
                diagnostics.configMirrorAt,
                now,
            )
        ) {
            DiagnosticsPolicy.ConfigSourceState.FRESH -> CheckItem(
                title,
                CheckStatus.OK,
                "实时读取模块 App 的配置，改动立即生效",
            )
            DiagnosticsPolicy.ConfigSourceState.MIRROR_RECENT -> CheckItem(
                title,
                CheckStatus.OK,
                "模块 App 进程暂时没起来，正在用 ${ago(diagnostics.configMirrorAt, now)}写下的配置镜像。" +
                    "开机初期属正常，打开一次本应用即可切回实时读取",
            )
            DiagnosticsPolicy.ConfigSourceState.MIRROR_STALE -> CheckItem(
                title,
                CheckStatus.WARN,
                "已经连续使用 ${ago(diagnostics.configMirrorAt, now)}写下的配置镜像，" +
                    "模块 App 进程一直起不来，这期间改的设置可能没生效。" +
                    "打开一次本应用，或检查它的自启动 / 后台运行限制",
            )
            DiagnosticsPolicy.ConfigSourceState.MISSING -> CheckItem(
                title,
                CheckStatus.FAIL,
                "模块读不到配置（连镜像也没有），自动开关不会工作。打开一次本应用",
            )
            DiagnosticsPolicy.ConfigSourceState.UNKNOWN -> CheckItem(
                title,
                CheckStatus.UNKNOWN,
                "模块还没上报配置来源；升级过版本的话，重启手机后生效",
            )
        }
    }

    private fun failureLabel(diagnostics: Diagnostics): String {
        if (diagnostics.lastFailureAt <= 0L) return "无"
        val stage = when (diagnostics.lastFailureStage) {
            "enable" -> "拉起 VPN 失败"
            "stop" -> "关闭 VPN 未成功"
            "forceStop" -> "强制停止失败"
            else -> diagnostics.lastFailureStage.ifEmpty { "未知阶段" }
        }
        val pkg = diagnostics.lastFailurePackage
        val streak = if (diagnostics.lastFailureStreak > 0) " · 连续 ${diagnostics.lastFailureStreak} 次" else ""
        return if (pkg.isNotEmpty()) "$stage · $pkg$streak" else "$stage$streak"
    }

    private fun moduleStateLabel(state: DiagnosticsPolicy.ModuleState): String = when (state) {
        DiagnosticsPolicy.ModuleState.CURRENT -> "当前版本运行中"
        DiagnosticsPolicy.ModuleState.OLD_CODE -> "system_server 仍是旧代码"
        DiagnosticsPolicy.ModuleState.STALE -> "心跳已过期"
        DiagnosticsPolicy.ModuleState.NEVER -> "从未注入"
    }

    private fun ruleLabel(rule: String): String = when (rule) {
        "disabled" -> "模块未生效"
        "paused" -> "磁贴已暂停"
        "wifiOff" -> "Wi-Fi 关闭规则"
        "wifiOn" -> "Wi-Fi 开启规则"
        "offTarget" -> "打开即关闭"
        "networkDisabled" -> "网络类型已禁用"
        "userOff" -> "手动关闭后等待重开"
        "target" -> "目标应用在前台"
        "idle" -> "空闲"
        else -> "未上报"
    }

    /**
     * SSID 的三种状态分开写，否则「没连 Wi-Fi」和「读不到名字」看起来一模一样。
     *
     * 名字默认遮成 `Ho***Fi(8)`：这份报告的主要用途是贴进 issue，而 SSID 常常带着
     * 姓名、房号、公司名。设置里可以改成显示完整名称。
     */
    private fun ssidLabel(d: Diagnostics, config: Config): String = when {
        d.currentSsid.isNotEmpty() ->
            com.vpnautoswitch.module.system.SsidMask.of(d.currentSsid, config.fullSsid)
        d.ssidState == "masked" -> "读取失败（系统可能已脱敏）"
        d.ssidState == "off" -> "未连接 Wi-Fi"
        else -> "未上报"
    }

    private fun yesNo(value: Boolean?): String = when (value) {
        true -> "是"
        false -> "否"
        null -> "未知"
    }

    private fun ago(at: Long, now: Long): String {
        if (at <= 0L) return "从未"
        val delta = (now - at).coerceAtLeast(0L)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
        val hours = TimeUnit.MINUTES.toHours(minutes)
        val days = TimeUnit.HOURS.toDays(hours)
        return when {
            delta < 60_000L -> "${delta / 1000} 秒前"
            minutes < 60 -> "$minutes 分钟前"
            hours < 24 -> "$hours 小时前"
            else -> "$days 天前"
        }
    }
}
