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

internal data class CheckItem(
    val title: String,
    val status: CheckStatus,
    val detail: String,
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
            )
            DiagnosticsPolicy.ModuleState.STALE -> CheckItem(
                "模块运行",
                CheckStatus.FAIL,
                "上次心跳在 ${ago(diagnostics.heartbeat, now)}，超过 90 秒没有新心跳。" +
                    "检查 LSPosed 是否启用模块、作用域是否只有「系统框架」",
            )
            DiagnosticsPolicy.ModuleState.NEVER -> CheckItem(
                "模块运行",
                CheckStatus.FAIL,
                "从未收到心跳。请在 LSPosed 中启用模块，作用域只勾「系统框架」，然后重启手机",
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
                append(" · ").append(ssidLabel(diagnostics))
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
        checks += CheckItem(
            "始终开启的 VPN",
            when {
                diagnostics.alwaysOnApp.isNullOrEmpty() -> CheckStatus.UNKNOWN
                needsAlwaysOn && diagnostics.alwaysOnApp != config.vpnPackage -> CheckStatus.WARN
                else -> CheckStatus.OK
            },
            buildString {
                append("系统当前指向：")
                append(diagnostics.alwaysOnApp?.takeIf { it.isNotEmpty() } ?: "未设置")
                if (needsAlwaysOn && diagnostics.alwaysOnApp != config.vpnPackage) {
                    append("\n规则要求开启 VPN，但设置尚未指向 ${config.vpnPackage}，可能正在切换")
                }
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
            appendLine("网络：${diagnostics.network} · SSID=${ssidLabel(diagnostics)}")
            appendLine("VPN 包：${config.vpnPackage.ifEmpty { "未选择" }}")
            appendLine("安装：${yesNo(diagnostics.vpnInstalled)} · 启用：${yesNo(diagnostics.vpnEnabled)} · VpnService：${yesNo(diagnostics.vpnHasService)}")
            appendLine("隧道：${if (diagnostics.vpnConnected) "已连接" else "未连接"}")
            appendLine("always-on：${diagnostics.alwaysOnApp ?: "未设置"} · 接管：${if (diagnostics.takenByModule) "是" else "否"}")
            appendLine("当前规则：${ruleLabel(diagnostics.activeRule)} · ${diagnostics.ruleDetail}")
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
        return when {
            pkg.isEmpty() -> CheckItem("VPN 客户端", CheckStatus.FAIL, "还没有选择 VPN 客户端")
            diagnostics.vpnInstalled != true -> CheckItem(
                "VPN 客户端",
                CheckStatus.FAIL,
                "$pkg 未安装，或无法查询。重新选择客户端后重试",
            )
            diagnostics.vpnEnabled == false -> CheckItem(
                "VPN 客户端",
                CheckStatus.WARN,
                "$pkg 已被系统禁用，请在应用信息里启用",
            )
            diagnostics.vpnHasService == false -> CheckItem(
                "VPN 客户端",
                CheckStatus.WARN,
                "$pkg 没有声明 VpnService，系统无法用它建立隧道",
            )
            else -> CheckItem(
                "VPN 客户端",
                CheckStatus.OK,
                "$pkg 已安装、启用并声明了 VpnService",
            )
        }
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
                if (diagnostics.vpnConnected) {
                    "规则要求开启，系统 VPN transport 已存在"
                } else {
                    "规则要求开启，但系统没有 VPN transport。可能仍在连接，或客户端没有建立隧道"
                },
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
            },
        )
    }

    private fun formatMs(ms: Long): String =
        if (ms < 1_000L) "$ms 毫秒" else String.format(Locale.CHINA, "%.1f 秒", ms / 1000.0)

    private fun failureCheck(diagnostics: Diagnostics, now: Long): CheckItem {
        if (diagnostics.lastFailureAt <= 0L) {
            return CheckItem("最近失败", CheckStatus.OK, "没有记录到失败")
        }
        val recent = now - diagnostics.lastFailureAt <= TimeUnit.MINUTES.toMillis(10)
        val base = "${failureLabel(diagnostics)}（${ago(diagnostics.lastFailureAt, now)}）"
        // 拉起失败且这个客户端只会用 VpnService 方式时，光说「失败了」没有用——
        // 用户重试一百次也是同一条路，得告诉他去哪儿换个方式
        val hint = if (
            DiagnosticsPolicy.shouldSuggestExternalControl(
                stage = diagnostics.lastFailureStage,
                streak = diagnostics.lastFailureStreak,
                clientMode = diagnostics.clientMode,
            )
        ) {
            "这个客户端被固定成 VpnService 方式，模块不会改走外部控制。" +
                "到首页的「客户端适配」点一次「测试外部控制」，成功后会自动改用这种方式"
        } else {
            null
        }
        return CheckItem(
            "最近失败",
            if (recent) CheckStatus.WARN else CheckStatus.UNKNOWN,
            if (hint == null) base else "$base\n$hint",
        )
    }

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
            )
            HookHealth.DEGRADED -> CheckItem(
                title,
                CheckStatus.WARN,
                "${report.degraded.joinToString("、")}没挂上。这个功能会降级：" +
                    "比如划掉任务后要等进程被系统回收才断开 VPN。" +
                    cosmeticHint(report.cosmetic),
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

    /** SSID 的三种状态分开写，否则「没连 Wi-Fi」和「读不到名字」看起来一模一样 */
    private fun ssidLabel(d: Diagnostics): String = when {
        d.currentSsid.isNotEmpty() -> d.currentSsid
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
