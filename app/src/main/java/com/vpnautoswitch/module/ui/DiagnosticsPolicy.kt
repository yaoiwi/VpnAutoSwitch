package com.vpnautoswitch.module.ui

import com.vpnautoswitch.module.system.HookHealth

/** 纯诊断判断，和 UI 分离，便于固定边界行为。 */
object DiagnosticsPolicy {

    /**
     * 心跳间隔 30 秒（空闲时 60 秒），判定阈值留三倍余量：
     * 一次丢失不会误报「模块未运行」。
     */
    const val HEARTBEAT_FRESH_MS = 90_000L

    enum class ModuleState {
        /** 心跳新鲜且版本一致 */
        CURRENT,

        /** 心跳新鲜，但 system_server 仍是旧代码 */
        OLD_CODE,

        /** 以前注入过，但当前没有新鲜心跳 */
        STALE,

        /** 从未收到心跳 */
        NEVER;
    }

    fun isHeartbeatFresh(heartbeat: Long, now: Long): Boolean =
        heartbeat > 0L && now >= heartbeat && now - heartbeat <= HEARTBEAT_FRESH_MS

    fun heartbeatAge(heartbeat: Long, now: Long): Long =
        if (heartbeat <= 0L || now < heartbeat) 0L else now - heartbeat

    fun moduleState(
        heartbeat: Long,
        moduleVersion: Int,
        appVersion: Int,
        now: Long,
    ): ModuleState = when {
        !isHeartbeatFresh(heartbeat, now) ->
            if (heartbeat > 0L) ModuleState.STALE else ModuleState.NEVER
        moduleVersion != appVersion -> ModuleState.OLD_CODE
        else -> ModuleState.CURRENT
    }

    /** 当前规则对 VPN 的预期状态；UNKNOWN 表示规则不强制开或关。 */
    enum class ExpectedVpn { ON, OFF, UNKNOWN }

    fun expectedVpn(activeRule: String): ExpectedVpn = when (activeRule) {
        "target", "wifiOn" -> ExpectedVpn.ON
        "offTarget", "wifiOff", "networkDisabled", "userOff" -> ExpectedVpn.OFF
        else -> ExpectedVpn.UNKNOWN
    }

    /**
     * 规则与实际隧道状态是否不一致。
     *
     * 返回 null 表示规则不强制开/关，无法判断；true 表示不一致。
     * 这里不能把「连接中」当成失败，UI 只按提醒处理。
     */
    fun vpnMismatch(activeRule: String, vpnConnected: Boolean): Boolean? =
        when (expectedVpn(activeRule)) {
            ExpectedVpn.ON -> !vpnConnected
            ExpectedVpn.OFF -> vpnConnected
            ExpectedVpn.UNKNOWN -> null
        }

    /** 规则 key 转成一句人能读懂的话 */
    fun ruleLabel(rule: String): String = when (rule) {
        "target" -> "目标应用 → 应连接"
        "wifiOn" -> "Wi-Fi 规则 → 应连接"
        "offTarget" -> "打开即关闭 → 应断开"
        "wifiOff" -> "Wi-Fi 规则 → 应断开"
        "networkDisabled" -> "当前网络已停用自动开关"
        "userOff" -> "手动断开 → 等待重新打开"
        "paused" -> "已暂停 → 不干预"
        "disabled" -> "未启用或未选客户端"
        "idle" -> "没有触发条件"
        "" -> "尚未收到规则"
        else -> rule
    }

    /** 日志最后一条非空行，已经自带 MM-dd HH:mm:ss 前缀 */
    fun lastEvent(log: String): String =
        log.lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()

    // ------------------------------------------------------------ hook 快照

    /**
     * hook 快照能不能用来描述「当前」的模块。
     *
     * 快照只在 system_server 加载时写一次，带着当时的 versionCode。用户升级 App 但还没重启
     * 手机时，system_server 里跑的还是旧代码，这份快照说明不了新版本挂上了什么，
     * 只能提示重启。
     */
    fun hookSnapshotUsable(snapshotVersion: Int, appVersion: Int): Boolean =
        snapshotVersion > 0 && snapshotVersion == appVersion

    // ------------------------------------------------------------ 启动方式

    /**
     * 「最近失败」要不要建议换一种启动方式。
     *
     * 只看三个条件：确实是拉起失败、连续失败了至少两次、客户端被固定成 VpnService 方式。
     * 固定成 service 时模块不会走外部控制兜底，重试再多次也是同一条路；
     * 而偶发一次失败可能只是网络慢、客户端还在初始化，不该让人去改设置。
     *
     * Clash Meta 这类客户端在后台只认 `action.START_CLASH`，VpnService 方式拉起来
     * 不会建隧道——issue #18 就是这么卡住的。
     */
    fun shouldSuggestExternalControl(
        stage: String,
        streak: Int,
        clientMode: String,
    ): Boolean = (stage == "enable" || stage == "stop") && streak >= 2 && clientMode == "service"

    /**
     * 固定成 service 方式、隧道却建不起来。
     *
     * 判据是「启动方式被固定成 service」+「现在确实没有隧道」，而不是要求系统常驻 VPN
     * 已经指向客户端——后者只在模块接管常驻设置时才成立。一旦这个客户端被记过启动方式，
     * 模块走的是 external-only 分支，压根不写 always-on（见 EngineStart 里
     * `mode != null` 那一段），此时 always-on 为空恰恰是「走的就是这条路」的证据。
     *
     * issue #18 就是这么被漏掉的：客户端被记成 service、always-on 一直是「未设置」，
     * 旧判据要求 always-on 等于客户端，于是永远不成立，提示一次都没出现过。
     *
     * 传进来的 always-on 若是**别的**客户端，说明这轮根本不是我们在管，不报。
     */
    fun serviceCannotTunnel(
        clientMode: String,
        vpnPackage: String,
        alwaysOnApp: String?,
        vpnConnected: Boolean,
        vpnHasService: Boolean?,
    ): Boolean = clientMode == "service" &&
        vpnPackage.isNotEmpty() &&
        vpnHasService != false &&
        !vpnConnected &&
        (alwaysOnApp == vpnPackage || alwaysOnApp.isNullOrEmpty())

    // ------------------------------------------------------------ 常驻设置

    /**
     * 系统常驻 VPN 为空时，什么情况下不算问题。
     *
     * 常驻设置是模块记「该连哪个客户端」的地方，但有两种情况本来就不会写：
     * 客户端被记成外部控制方式（模块直接发广播，用不上这项），或者隧道已经
     * 建立（常驻只决定以后能不能自动恢复，不影响当前连接）。
     *
     * 以前只认「模块未接管 + 规则不要常驻」这一种，于是 external-only 的机器
     * 永远显示「未知」，下面还跟着一句「可能正在切换」——而它其实已经连着。
     */
    fun alwaysOnNotNeeded(
        clientMode: String,
        vpnConnected: Boolean,
        activeRule: String,
        takenByModule: Boolean,
        needsAlwaysOn: Boolean,
    ): Boolean = clientMode == "activity" ||
        (vpnConnected && needsAlwaysOn) ||
        (activeRule.isNotEmpty() && !takenByModule && !needsAlwaysOn)

    // ------------------------------------------------------------ 失败时效

    /**
     * 超过这么久之前的失败，不再拿来当「现在的问题」。
     *
     * 失败记录跟着模块一起跨天保留：早上关 VPN 没成功一次，晚上再开报告还写着
     * 「连续 3 次拉不起客户端」，用户照着去改自启和电池优化，白折腾。
     * 十分钟足够覆盖一轮「打开应用 → 重试几次」的真实卡壳。
     */
    const val FAILURE_RECENT_MS = 10L * 60 * 1000

    fun failureIsRecent(ageMs: Long): Boolean = ageMs in 0..FAILURE_RECENT_MS

    // ------------------------------------------------------------ 配置来源

    /** 镜像超过这么久还在使用，就提示「改的设置可能没生效」 */
    const val CONFIG_MIRROR_STALE_MS = 30L * 60 * 1000

    enum class ConfigSourceState {
        /** 实时从模块 App 的 provider 读到 */
        FRESH,

        /** 刚退回镜像，属于开机初期的正常状态 */
        MIRROR_RECENT,

        /** 长时间只能读镜像，App 进程可能一直起不来 */
        MIRROR_STALE,

        /** 连镜像也没有 */
        MISSING,

        /** 模块没上报过（旧代码 / 没注入） */
        UNKNOWN;
    }

    fun configSourceStatus(source: String, mirrorAt: Long, now: Long): ConfigSourceState = when {
        source.isEmpty() -> ConfigSourceState.UNKNOWN
        source == "provider" -> ConfigSourceState.FRESH
        source == "none" -> ConfigSourceState.MISSING
        source != "mirror" -> ConfigSourceState.UNKNOWN
        mirrorAt <= 0L || now < mirrorAt -> ConfigSourceState.MIRROR_RECENT
        now - mirrorAt > CONFIG_MIRROR_STALE_MS -> ConfigSourceState.MIRROR_STALE
        else -> ConfigSourceState.MIRROR_RECENT
    }

    enum class Tone { NORMAL, WARN, MUTED }

    /** 首页状态卡要显示的四行因果链 */
    data class StatusSummary(
        val headline: String,
        val subtitle: String,
        val foreground: String,
        val rule: String,
        val actual: String,
        val actualTone: Tone,
        val lastAction: String,
        /**
         * 「卡在哪一步」：规则要求连上、隧道却没起来时，模块自己报的阻断原因。
         * 空字符串表示这一刻没什么可解释的，界面不显示这一行。
         */
        val blocked: String = "",
        val blockedTone: Tone = Tone.MUTED,
    )

    fun summarize(
        heartbeat: Long,
        moduleVersion: Int,
        appVersion: Int,
        paused: Boolean,
        activeRule: String,
        ruleDetail: String,
        foreground: String,
        vpnConnected: Boolean,
        network: String,
        log: String,
        now: Long,
        blocked: String = "",
        readiness: String = "",
        blockNeedsUser: Boolean = false,
    ): StatusSummary {
        val state = moduleState(heartbeat, moduleVersion, appVersion, now)
        val fresh = state == ModuleState.CURRENT || state == ModuleState.OLD_CODE
        val age = heartbeatAge(heartbeat, now)

        val headline = when {
            !fresh && heartbeat == 0L -> "等待注入"
            !fresh -> "模块未运行"
            state == ModuleState.OLD_CODE -> "运行中（旧代码）"
            paused -> "已暂停"
            else -> "运行中"
        }

        val subtitle = when {
            !fresh && heartbeat == 0L ->
                "超过 90 秒没有收到系统框架心跳，去 LSPosed 里检查作用域并重启"
            !fresh -> "心跳已中断，最后一次是 ${formatAge(age)}前"
            state == ModuleState.OLD_CODE ->
                "App 是 v$appVersion，系统框架仍是 v$moduleVersion，重启手机后生效"
            paused -> "快捷磁贴暂停了自动联动，点「恢复联动」或磁贴恢复"
            else -> "心跳 ${formatAge(age)}前 · 当前网络 $network"
        }

        val hasForeground = foreground.isNotBlank()
        val wantsVpn = expectedVpn(activeRule) == ExpectedVpn.ON
        val mismatch = vpnMismatch(activeRule, vpnConnected) == true

        return StatusSummary(
            headline = headline,
            subtitle = subtitle,
            foreground = when {
                // 模块没在跑时，残留的前台包名不可信
                !fresh -> "未知"
                hasForeground -> foreground
                else -> "无（当前没有目标应用在前台）"
            },
            rule = when {
                !fresh -> "未知"
                ruleDetail.isNotBlank() -> ruleLabel(activeRule) + " · " + ruleDetail
                else -> ruleLabel(activeRule)
            },
            actual = when {
                !fresh -> "未知"
                vpnConnected -> "已连接"
                else -> "未连接"
            },
            actualTone = when {
                !fresh -> Tone.MUTED
                mismatch -> Tone.WARN
                vpnConnected && wantsVpn -> Tone.NORMAL
                else -> Tone.MUTED
            },
            lastAction = lastEvent(log).ifBlank { "暂无记录" },
            // 「命中规则 + 未连接」时补一句卡在哪：模块自己报的优先，
            // 老版本模块不上报这一项，就退回用客户端就绪状态兜一句
            blocked = when {
                !fresh || vpnConnected -> ""
                blocked.isNotBlank() -> blocked
                else -> readinessAdvice(readiness)
            },
            blockedTone = when {
                !fresh || vpnConnected -> Tone.MUTED
                blockNeedsUser -> Tone.WARN
                // 客户端本身不可用，即便模块没细说也值得标出来
                readinessAdvice(readiness).isNotEmpty() -> Tone.WARN
                else -> Tone.MUTED
            },
        )
    }

    /** 就绪状态的文案复用模块侧那一份，避免同一个原因在 App 和日志里两种说法 */
    fun readinessAdvice(name: String): String {
        if (name.isEmpty() || name == "READY") return ""
        val policy = com.vpnautoswitch.module.system.ReadinessPolicy
        val state = com.vpnautoswitch.module.system.ClientReadiness.values()
            .firstOrNull { it.name == name } ?: return ""
        // STOPPED 模块每次启动前都会自己清掉，不算卡住；UNKNOWN 说不清，都不占这一行
        if (!policy.needsUser(state)) return ""
        return policy.advice(state)
    }

    private fun formatAge(ms: Long): String = when {
        ms < 1_000L -> "刚刚"
        ms < 60_000L -> "${ms / 1_000L} 秒"
        ms < 3_600_000L -> "${ms / 60_000L} 分钟"
        else -> "${ms / 3_600_000L} 小时"
    }

    // ------------------------------------------------------------ 一句话结论

    /**
     * 报告里已经有十来项检查，但用户最想先知道的是「哪里不对、下一步做什么」。
     * 逐条读完再自己拼结论，对不熟悉这个模块的人是件难事。
     */
    data class VerdictInput(
        val moduleState: ModuleState,
        val hookHealth: HookHealth?,
        val vpnInstalled: Boolean?,
        val vpnHasService: Boolean?,
        val vpnConnected: Boolean,
        val alwaysOnApp: String?,
        val vpnPackage: String,
        val failureStreak: Int,
        val paused: Boolean,
        /** 最近一次失败发生在哪个阶段：enable / stop / forceStop */
        val failureStage: String = "",
        /** 距今多久；超出 [FAILURE_RECENT_MS] 就不再当作「现在的问题」 */
        val failureAgeMs: Long = Long.MAX_VALUE,
        /** 当前规则是否要求连上 VPN；规则不强制时为 true，避免把「没连」说成正常 */
        val expectsOn: Boolean = true,
        /** 这个客户端被记住的启动方式：service / activity / "" */
        val clientMode: String = "",
    )

    /**
     * 按「越靠前越要先解决」的顺序挑一句。
     *
     * 顺序不是随手排的：模块没跑起来时，后面所有状态都不可信；hook 没挂上时，
     * 界面看着一切正常但功能其实不工作；客户端装不上是配置问题；最后才轮到
     * 「正在切换，过几秒再看」这种临时状态。
     *
     * 失败相关的几条必须先校验阶段和时效：失败记录会跨天留着，六小时前「关闭 VPN
     * 没成功」的 streak 如果直接套进「拉不起客户端」的文案，用户会去改一堆用不上的设置。
     */
    fun conclusion(input: VerdictInput): String {
        val streak = input.failureStreak
        val recentFailure = streak > 0 && failureIsRecent(input.failureAgeMs)
        return when {
            input.moduleState == ModuleState.NEVER ->
                "模块还没运行过。在 LSPosed 里只勾「系统框架」，然后重启手机。"
            input.moduleState == ModuleState.OLD_CODE ->
                "系统里跑的还是旧版本模块。重启手机让新版本生效。"
            input.moduleState == ModuleState.STALE ->
                "模块心跳停了，可能被系统回收。重启手机；反复出现的话把这份报告发出来。"
            input.hookHealth == HookHealth.BROKEN ->
                "模块在跑，但关键 hook 没挂上，自动开关会失效。重启手机；还不行就检查 LSPosed 作用域是不是只有「系统框架」。"
            input.hookHealth == HookHealth.DEGRADED ->
                "进程死亡检测正常，但「划掉最近任务」那条 hook 没挂上：上滑清理不会触发断开，按返回键退出仍然有效。"
            input.vpnInstalled == false ->
                "选的 VPN 客户端没装。去设置里重新选一个。"
            input.vpnHasService == false ->
                "这个客户端没有声明 VpnService，模块拉不起来。换一个客户端。"
            input.paused ->
                "自动联动被快捷开关暂停了，恢复后才会自动开关。"
            // 排在「连续 N 次拉不起客户端」之前：固定成 service 时失败原因不是
            // 自启或电池优化，而是这条路本身走不通，给的下一步也不一样
            serviceCannotTunnel(
                clientMode = input.clientMode,
                vpnPackage = input.vpnPackage,
                alwaysOnApp = input.alwaysOnApp,
                vpnConnected = input.vpnConnected,
                vpnHasService = input.vpnHasService,
            ) ->
                "启动方式被固定成 VpnService，隧道一直没建立。这个客户端可能只认自己的启动广播" +
                    "——到「客户端适配」测一次外部控制，成功后模块会自动改用这种方式。"
            recentFailure && streak >= 2 && input.expectsOn && !input.vpnConnected &&
                input.failureStage == "enable" ->
                "连续 $streak 次拉不起客户端。看日志里「拉起前包状态」：被强停就要放自启，后台受限就要关电池优化。"
            recentFailure && !input.expectsOn && input.vpnConnected && input.failureStage == "stop" ->
                "规则要求断开，但最近 $streak 次都没关掉。到「客户端适配」测一次外部控制，成功后模块会改走这条路关。"
            recentFailure && streak >= 2 && input.expectsOn && !input.vpnConnected ->
                "最近 $streak 次切换失败（${failureStageLabel(input.failureStage)}）。看日志确认卡在哪一步。"
            input.expectsOn && !input.vpnConnected &&
                input.vpnPackage.isNotEmpty() && input.alwaysOnApp == input.vpnPackage ->
                "系统常驻 VPN 已经指向客户端，但隧道没建立。可能还在连；一直这样就在客户端里点一次连接，先确认它自己能连上。"
            !input.expectsOn && input.vpnConnected ->
                "规则要求断开，系统里仍有 VPN 隧道。可能是别的 VPN，也可能关闭还没完成。"
            input.expectsOn && !input.vpnConnected ->
                "规则要求开启，系统还没指向客户端。模块正在切换，过几秒刷新看看。"
            else -> "各项正常。"
        }
    }

    private fun failureStageLabel(stage: String): String = when (stage) {
        "enable" -> "拉起客户端"
        "stop" -> "关闭 VPN"
        "forceStop" -> "强制停止客户端"
        else -> "开关 VPN"
    }

    /**
     * 把关闭原因原文翻成报告里的一句话。
     *
     * 原文在 system_server 侧拼，形如 `com.chrome.canary task removed`：
     * 直接贴进报告用户还得自己断句，报「VPN 自己断了」时也说不清是哪条规则。
     * 认不出来的句式原样返回——宁可难看，也别把信息丢掉。
     */
    fun offReasonLabel(raw: String): String {
        val reason = raw.trim()
        if (reason.isEmpty()) return ""
        return when {
            reason.endsWith(TASK_REMOVED) ->
                "划掉任务（${reason.removeSuffix(TASK_REMOVED)}）"
            reason.startsWith("opened ") ->
                "打开即关闭（${reason.removePrefix("opened ")}）"
            reason.startsWith("wifi off:") ->
                "Wi-Fi 关闭：${reason.removePrefix("wifi off:").trim()}"
            reason == "wifi off" -> "Wi-Fi 关闭"
            reason == "network type disabled" -> "当前网络类型不允许"
            reason == "screen off" -> "息屏"
            reason == "module disabled" -> "模块被停用"
            reason == "automation resumed without target" -> "恢复自动化时没有目标应用"
            reason == "disable" -> "手动关闭"
            else -> reason
        }
    }

    private const val TASK_REMOVED = " task removed"
}

