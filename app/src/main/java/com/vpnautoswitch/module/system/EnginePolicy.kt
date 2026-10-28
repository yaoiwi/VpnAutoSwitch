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
    fun broadcastWaitMs(isServiceMode: Boolean): Long = if (isServiceMode) 800L else 2_000L

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

    fun reapplyInterval(
        reapplyCount: Int,
        maxFastReapply: Int = 4,
        fastMs: Long = 12_000L,
        slowMs: Long = 60_000L,
    ): Long = if (reapplyCount >= maxFastReapply) slowMs else fastMs

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
