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
