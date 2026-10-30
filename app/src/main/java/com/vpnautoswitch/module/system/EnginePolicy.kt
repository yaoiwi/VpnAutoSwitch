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
     */
    fun allowForceStopOnClose(forceStopEnabled: Boolean, keepClientAlive: Boolean): Boolean =
        forceStopEnabled && !keepClientAlive

    fun stopOrder(
        hasPackage: Boolean,
        allowActivityUi: Boolean,
        hasTile: Boolean,
        forceStopEnabled: Boolean,
    ): List<StopStep> {
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
}
