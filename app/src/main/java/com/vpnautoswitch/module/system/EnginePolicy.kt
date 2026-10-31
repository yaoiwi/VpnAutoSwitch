package com.vpnautoswitch.module.system

/** 不依赖 Android 的核心判断，便于固定旧行为并覆盖时序边界。 */
internal object EnginePolicy {

    enum class ForegroundKind { OFF_TARGET, TARGET, IGNORE }

    fun foregroundKind(
        packageName: String,
        targets: Set<String>,
        offTargets: Set<String>,
    ): ForegroundKind = when {
        offTargets.contains(packageName) -> ForegroundKind.OFF_TARGET
        targets.contains(packageName) -> ForegroundKind.TARGET
        else -> ForegroundKind.IGNORE
    }

    fun isFreshOpen(lastForeground: String?, packageName: String, vpnPackage: String): Boolean =
        lastForeground != packageName && lastForeground != vpnPackage

    fun shouldResumeAfterUserOff(freshOpen: Boolean): Boolean = freshOpen

    /**
     * 是不是「刚关掉」的这一小段时间。
     *
     * 断开后客户端常常自己重连，或系统按常驻设置把它拉回来；那一下不是用户打开的，
     * 再提示「VPN 已开启」会和他刚点的「关闭」正好相反。[sinceOffMs] 为负（时钟回拨）也不认。
     */
    fun isFreshOff(sinceOffMs: Long, windowMs: Long = 3_000L): Boolean =
        sinceOffMs >= 0 && sinceOffMs < windowMs

    /** 连上后不到这么久就掉，算「客户端这次没连成」，而不是用户手动断开 */
    const val STARTUP_FLAP_MS = 2_000L

    /**
     * 目标应用还停在前台时的抖动窗口。
     *
     * 用户开着目标应用、刚连上就掉，意图很明确就是「要连上」，这时候把掉落当成
     * 手动断开是最容易误判的：日志里 15:02:57 连上、15:02:59 掉，按 2 秒窗口正好卡在
     * 边界上判成「保持关闭」，于是开着 Twitter 干等 18 秒才有网。
     * 窗口放到 6 秒，同时把重试次数照旧限死，不会和客户端无限拉扯。
     */
    const val TARGET_FLAP_MS = 6_000L

    /** 连上超过这么久才算「稳定」；不到这个时长不清零抖动重试次数，免得连不稳的客户端无限重试 */
    const val STABLE_CONNECTION_MS = 8_000L

    /**
     * 隧道刚连上就掉：这是客户端建隧道失败（有的客户端会先 establish 再断开），
     * 不是用户点了关闭。
     *
     * 这种一次性的抖动以前会被当成手动断开，于是 `userOff` 一直挂着，
     * 用户得退出再重新打开应用才会再连——日志里就是连上 1 秒后
     * `vpn dropped -> stay off until a target app is reopened`，然后干等几十秒。
     * 现在按「重试」处理，最多 [maxRetries] 次；真的连不稳才放弃。
     */
    fun isStartupFlap(
        connectedForMs: Long,
        retries: Int,
        maxRetries: Int,
        windowMs: Long = STARTUP_FLAP_MS,
    ): Boolean = connectedForMs >= 0 && connectedForMs < windowMs && retries < maxRetries

    /**
     * 补偿重试要绕开正常冷却：把「上次动手时刻」往前挪到只剩 [retryMs] 冷却的位置。
     *
     * 外部控制模式的冷却有 6 秒，抖动一次就要再等 6 秒才轮到下一次尝试，
     * 用户感知是「开了应用要等十几秒才有网」。抖动后的重试只等几百毫秒。
     */
    fun rewindForRetry(now: Long, lastApplyAt: Long, cooldownMs: Long, retryMs: Long): Long =
        minOf(lastApplyAt, now - cooldownMs + retryMs)

    /** 模块版本变化（含首次安装）时清空旧日志，保证只看得到当前版本的记录。 */
    fun shouldClearLogsOnBoot(previousVersion: Int, currentVersion: Int): Boolean =
        previousVersion != currentVersion

    fun isNetworkAllowed(transport: Int, allowWifi: Boolean, allowMobile: Boolean): Boolean =
        when (transport) {
            1 -> allowWifi
            2 -> allowMobile
            else -> true
        }

    fun shouldScheduleOffAfterDeath(removedTarget: Boolean, remainingTargets: Int): Boolean =
        removedTarget && remainingTargets == 0

    /**
     * 离开一个目标后，剩下的是不是全是「挂在后台的残留进程」。
     *
     * 这里不看「离开的是不是刚在用的那个」，而是看现在有没有别的目标还停在前台：
     * 有就说明用户还在用别的目标，会话要留着；没有就说明目标全在后台，可以收掉。
     */
    fun anotherTargetInForeground(
        foreground: String?,
        leavingPackage: String,
        aliveTargets: Set<String>,
    ): Boolean = foreground != null && foreground != leavingPackage && aliveTargets.contains(foreground)

    /**
     * 离开的是不是「刚在用的那个」，并且还有别的目标挂在后台。
     *
     * 这种情况要结束整段会话：否则后台残留的进程会让 VPN 一直连着，
     * 用户划掉当前应用却看不到断开。
     *
     * [dismissAny] 打开时，只要目标都在后台，划掉任意一个也算会话结束；
     * 关掉时只认「划掉正在用的那个」，后台目标被回收不影响会话。
     */
    fun shouldEndSessionOnLeave(
        removedWasForeground: Boolean,
        remainingTargets: Int,
        dismissAny: Boolean = false,
        anotherTargetInForeground: Boolean = false,
    ): Boolean = remainingTargets > 0 && (removedWasForeground || (dismissAny && !anotherTargetInForeground))

    /**
     * 关后守卫是否继续观察。
     *
     * 只在「没有接管常驻设置」时才有意义：always-on 模式关掉设置就断了，
     * 只有靠重启 VpnService 拉起的外部控制模式，才可能被客户端自己再拉回来。
     */
    fun shouldGuard(
        guardEnabled: Boolean,
        tookOwnership: Boolean,
        attempts: Int,
        maxAttempts: Int,
        closable: Boolean,
    ): Boolean = guardEnabled && !tookOwnership && closable && attempts <= maxAttempts

    /** 观察间隔递增，前密后疏，避免 8 秒里反复唤醒 */
    fun nextGuardInterval(currentMs: Long): Long =
        (currentMs * 1.6f).toLong().coerceAtMost(2_400L)

    /**
     * 快捷磁贴能不能点。
     *
     * 磁贴是**切换**语义，不是「关闭」：VPN 已经断开时再点一次会把它打开。
     * 关闭链和关后守卫各自都会走一遍磁贴，两条链挨得太近时就会互相抵消，
     * 日志里表现为「关掉又起来」和重复的 `vpn is down (qs tile)`。
     */
    fun tileToggleAllowed(now: Long, lastToggleAt: Long, guardMs: Long): Boolean =
        lastToggleAt == 0L || now - lastToggleAt >= guardMs

    /** 关闭链里的一步，顺序由 [stopOrder] 决定 */
    enum class StopStep { BROADCAST, ACTIVITY, TILE, FORCE_STOP }

    /**
     * 关闭链的步骤顺序。
     *
     * 只决定「允许尝试哪些步骤」，不判断运行时是否真的成功；
     * 这样关闭逻辑可以脱离 Android 环境做回归测试。
     */
    /**
     * 关闭时第一阶段是否允许直接强制停止客户端。
     *
     * 开了「保留客户端进程」就先只用温和手段（停止广播 / 快捷磁贴），
     * 让进程活着；确实关不掉时 Engine 再单独补一次强停。
     * 用户主动开了「直接强制停止」时以他的选择为准——那个开关本身就意味着不要留进程。
     */
    fun allowForceStopOnClose(
        forceStopEnabled: Boolean,
        keepClientAlive: Boolean,
        directForceStop: Boolean = false,
    ): Boolean = forceStopEnabled && (directForceStop || !keepClientAlive)

    /**
     * 关闭链结束后要不要接力启动关后守卫。
     *
     * 两个条件是「什么时候需要盯」：没接管常驻设置（接管了系统自己会 unbind），
     * 以及关闭前隧道确实是通的（本来就没连，没什么好守的）。
     * 用户手动关过的那条隧道不守——他已经明确表达了「不要它」，再盯着关第二次是越权。
     */
    fun shouldHandOffToGuard(
        guardEnabled: Boolean,
        tookOwnership: Boolean,
        wasActive: Boolean,
        wasUserOff: Boolean,
    ): Boolean = guardEnabled && !tookOwnership && wasActive && !wasUserOff

    /**
     * 关闭链正在跑的时候，守卫不许动手。
     *
     * 守卫和主链用的是同一套手段（停止广播 → 磁贴 → 强停），同时跑会互相作废对方排队的复查，
     * 日志里就是同一秒两条 verify、磁贴被点两次、强停计数从 1 重来。
     * [busyMs] 是兜底：主链某条路径被代次作废后没人释放标志，超时后守卫自行接管。
     */
    fun guardMayAct(busy: Boolean, busyForMs: Long, maxBusyMs: Long): Boolean =
        !busy || busyForMs >= maxBusyMs

    /**
     * 锁屏到点后是否断开。
     *
     * [screenOn] 在计时期间又亮屏了就不要再断；[configuredMinutes] 只用于展示与兜底校验，
     * 真正的计时由调用方的定时器负责。
     */
    fun shouldDisconnectOnScreenOff(
        enabled: Boolean,
        screenOn: Boolean,
        hasTunnel: Boolean,
        elapsedMs: Long,
        configuredMinutes: Int,
    ): Boolean =
        enabled &&
            !screenOn &&
            hasTunnel &&
            elapsedMs >= configuredMinutes.coerceIn(1, 120) * 60_000L

    fun stopOrder(
        hasPackage: Boolean,
        allowActivityUi: Boolean,
        hasTile: Boolean,
        forceStopEnabled: Boolean,
        directForceStop: Boolean = false,
    ): List<StopStep> {
        // 「直接强制停止」跳过所有温和手段：用户嫌中间过程慢或客户端不响应时用
        if (directForceStop && forceStopEnabled) return listOf(StopStep.FORCE_STOP)
        if (!hasPackage) {
            return if (forceStopEnabled) listOf(StopStep.FORCE_STOP) else emptyList()
        }
        val steps = mutableListOf(StopStep.BROADCAST)
        if (allowActivityUi) steps += StopStep.ACTIVITY
        if (hasTile) steps += StopStep.TILE
        if (forceStopEnabled) steps += StopStep.FORCE_STOP
        return steps
    }

    /** 停止广播的等待时长：service 模式大多不响应，少等一会儿 */
    fun broadcastWaitMs(isServiceMode: Boolean): Long = if (isServiceMode) 700L else 1_200L

    /**
     * 是否处理“从最近任务划掉”。
     *
     * 只有目标应用、功能开启、没有暂停/关闭规则压制，并且这个包确实在存活集合里时才处理，
     * 避免把任务恢复、分屏里另一个任务等场景误判成退出。
     */
    fun shouldHandleTaskRemoval(
        enabled: Boolean,
        featureEnabled: Boolean,
        paused: Boolean,
        offAppForeground: Boolean,
        wifiSuppressed: Boolean,
        target: Boolean,
        aliveTarget: Boolean,
    ): Boolean =
        enabled &&
            featureEnabled &&
            !paused &&
            !offAppForeground &&
            !wifiSuppressed &&
            target &&
            aliveTarget

    /** null 表示进程列表不可信，不能当成全部死亡。 */
    fun shouldEnsureForProcessState(anyTargetAlive: Boolean?): Boolean = anyTargetAlive != false

    /**
     * 重写常驻设置前要等多久。
     *
     * 首次（[reapplyCount] 为 0）用 [firstMs]：这时往往只是客户端还没把隧道建好，
     * 等一个完整的建隧道时间就够，没必要按「防打断」的 12 秒硬等。
     * 连续失败以后才拉长，避免反复 unbind 把正在建隧道的过程打断。
     */
    fun reapplyInterval(
        reapplyCount: Int,
        maxFastReapply: Int = 4,
        fastMs: Long = 12_000L,
        slowMs: Long = 60_000L,
        firstMs: Long = fastMs,
    ): Long = when {
        reapplyCount <= 0 -> firstMs
        reapplyCount >= maxFastReapply -> slowMs
        else -> fastMs
    }

    /**
     * 看门狗下一拍的间隔。
     *
     * 已经发起连接但隧道还没起来时用短间隔，免得「第一次没连上、第二次要等 5 秒」；
     * 连续快跑 [maxFastTicks] 拍仍未连上就退回常规间隔，避免长期高频轮询耗电。
     */
    fun reconcileDelay(
        connected: Boolean,
        connectingTicks: Int,
        maxFastTicks: Int = 8,
        fastMs: Long = 900L,
        normalMs: Long = 5_000L,
    ): Long = if (!connected && connectingTicks < maxFastTicks) fastMs else normalMs

    fun cooldownReady(now: Long, lastAt: Long, cooldownMs: Long): Boolean =
        now - lastAt >= cooldownMs

    /**
     * 外部控制模式下，是否该「等客户端把隧道建完」而不是再发一次启动请求。
     *
     * 客户端进程还活着，说明上一次启动请求已经被系统接受，隧道多半正在建；
     * 这时候再 startService，不少客户端会把它当成一次新的连接，把正在建的那条推倒重来
     * （日志里就是「启动两次 → 7~11 秒才通，通了又掉」）。
     * 只在「本轮已经启动过、进程还在、距开始不超过 [graceMs]」时等待，
     * 超过 [graceMs] 后照常重试，免得客户端真的卡死时一直干等。
     */
    fun waitForClientInit(
        alreadyStarted: Boolean,
        sinceStartMs: Long,
        processAlive: Boolean,
        graceMs: Long,
    ): Boolean = alreadyStarted && processAlive && sinceStartMs in 0 until graceMs

    fun shouldRunDelayedStart(
        token: Int,
        currentToken: Int,
        scheduledVpnPackage: String,
        enabled: Boolean,
        currentVpnPackage: String,
        offAppForeground: Boolean,
        userOff: Boolean,
        wifiSuppressed: Boolean,
        networkAllowed: Boolean,
        hasAliveTarget: Boolean,
        wifiOnActive: Boolean,
    ): Boolean =
        token == currentToken &&
            enabled &&
            currentVpnPackage.isNotEmpty() &&
            currentVpnPackage == scheduledVpnPackage &&
            !offAppForeground &&
            !userOff &&
            !wifiSuppressed &&
            networkAllowed &&
            (hasAliveTarget || wifiOnActive)

    fun shouldRunDelayedOff(
        token: Int,
        currentToken: Int,
        hasAliveTarget: Boolean,
        wifiOnActive: Boolean,
    ): Boolean = token == currentToken && !hasAliveTarget && !wifiOnActive

    fun isCloseChainValid(
        generation: Int,
        currentGeneration: Int,
        expectedVpnPackage: String,
        currentVpnPackage: String,
        canTouchVpn: Boolean,
    ): Boolean =
        generation == currentGeneration &&
            currentVpnPackage == expectedVpnPackage &&
            canTouchVpn

    /** 预热前的判定结果：要么现在动手，要么再等等，要么这一轮就别做 */
    enum class PrewarmGate { GO, WAIT_CHAIN, WAIT_GUARD, WAIT_TIME, SKIP_TRANSPORT, SKIP_ALIVE, SKIP_FORCE_STOP }

    /**
     * 「保留客户端进程」能不能现在就 bind。
     *
     * 顺序就是优先级：主链没跑完不动手，守卫还在盯不动手，时间没到不动手；
     * 隧道还连着、进程还活着、刚强停过，都属于这一轮不该预热。
     * 预热会把客户端从后台叫醒，对「关闭后继续抑制」是纯干扰，所以这两条链必须先收尾。
     */
    fun prewarmGate(
        chainRunning: Boolean,
        now: Long,
        guardUntil: Long,
        notBefore: Long,
        hasTransport: Boolean,
        processAlive: Boolean,
        sinceForceStopMs: Long,
        forceStopGuardMs: Long,
    ): PrewarmGate = when {
        chainRunning -> PrewarmGate.WAIT_CHAIN
        now < guardUntil -> PrewarmGate.WAIT_GUARD
        now < notBefore -> PrewarmGate.WAIT_TIME
        hasTransport -> PrewarmGate.SKIP_TRANSPORT
        processAlive -> PrewarmGate.SKIP_ALIVE
        sinceForceStopMs < forceStopGuardMs -> PrewarmGate.SKIP_FORCE_STOP
        else -> PrewarmGate.GO
    }
}
