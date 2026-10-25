package com.vpnautoswitch.module.ui

import com.vpnautoswitch.module.BuildConfig
import com.vpnautoswitch.module.Config
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
                "上次心跳在 ${ago(diagnostics.heartbeat, now)}，超过 60 秒没有新心跳。" +
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
                if (diagnostics.currentSsid.isNotEmpty()) append(" · ${diagnostics.currentSsid}")
                if (diagnostics.ruleDetail.isNotEmpty()) {
                    append("\n当前：${ruleLabel(diagnostics.activeRule)}，${diagnostics.ruleDetail}")
                } else {
                    append("\n模块还没有上报当前规则")
                }
            },
        )

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

        checks += failureCheck(diagnostics, now)
        checks += configCheck(config)

        return checks
    }

    fun buildText(diagnostics: Diagnostics, config: Config): String {
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        return buildString {
            appendLine("VpnAutoSwitch 状态报告")
            appendLine("App：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("模块：v${diagnostics.moduleVersion} · ${moduleStateLabel(diagnostics.moduleState)}")
            appendLine(
                "心跳：" + if (diagnostics.heartbeat > 0L) {
                    "${fmt.format(Date(diagnostics.heartbeat))}（${ago(diagnostics.heartbeat, now)}）"
                } else {
                    "从未"
                }
            )
            appendLine("网络：${diagnostics.network} · SSID=${diagnostics.currentSsid.ifEmpty { "无" }}")
            appendLine("VPN 包：${config.vpnPackage.ifEmpty { "未选择" }}")
            appendLine("安装：${yesNo(diagnostics.vpnInstalled)} · 启用：${yesNo(diagnostics.vpnEnabled)} · VpnService：${yesNo(diagnostics.vpnHasService)}")
            appendLine("隧道：${if (diagnostics.vpnConnected) "已连接" else "未连接"}")
            appendLine("always-on：${diagnostics.alwaysOnApp ?: "未设置"} · 接管：${if (diagnostics.takenByModule) "是" else "否"}")
            appendLine("当前规则：${ruleLabel(diagnostics.activeRule)} · ${diagnostics.ruleDetail}")
            appendLine("规则更新时间：${if (diagnostics.ruleUpdatedAt > 0) fmt.format(Date(diagnostics.ruleUpdatedAt)) else "未上报"}")
            appendLine("最近失败：${failureLabel(diagnostics)}")
            appendLine("目标应用：${config.targets.size} · 打开即关闭：${config.offTargets.size} · Wi-Fi 开/关：${config.wifiOn.size}/${config.wifiOff.size}")
            appendLine()
            appendLine("检查结果：")
            buildChecks(diagnostics, config).forEach {
                appendLine("[${it.status.name}] ${it.title}：${it.detail.replace("\n", " / ")}")
            }
            appendLine()
            appendLine("最近日志：")
            append(diagnostics.log.lines().filter { it.isNotBlank() }.takeLast(20).joinToString("\n"))
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

    private fun failureCheck(diagnostics: Diagnostics, now: Long): CheckItem {
        if (diagnostics.lastFailureAt <= 0L) {
            return CheckItem("最近失败", CheckStatus.OK, "没有记录到失败")
        }
        val recent = now - diagnostics.lastFailureAt <= TimeUnit.MINUTES.toMillis(10)
        return CheckItem(
            "最近失败",
            if (recent) CheckStatus.WARN else CheckStatus.UNKNOWN,
            "${failureLabel(diagnostics)}（${ago(diagnostics.lastFailureAt, now)}）",
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
