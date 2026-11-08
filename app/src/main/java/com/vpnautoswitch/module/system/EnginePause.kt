package com.vpnautoswitch.module.system

import android.content.Context
import android.provider.Settings
import com.vpnautoswitch.module.ModuleContract

// 暂停联动：快捷磁贴与 App 设置里的暂停开关

internal fun Engine.readPaused(ctx: Context): Boolean = try {
    Settings.Secure.getInt(ctx.contentResolver, ModuleContract.PAUSED_KEY, 0) == 1
} catch (t: Throwable) {
    false
}

/**
 * 快捷磁贴 / App 触发的暂停开关。
 *
 * 暂停只停止自动化，不主动断开当前 VPN；恢复时按当前前台与网络规则重新对账。
 */
internal fun Engine.togglePause(ctx: Context) {
    val next = !automationPaused
    val ok = try {
        Settings.Secure.putInt(
            ctx.contentResolver,
            ModuleContract.PAUSED_KEY,
            if (next) 1 else 0,
        )
        true
    } catch (t: Throwable) {
        false
    }
    if (!ok) {
        log(ctx, "toggle pause failed: settings write rejected")
        return
    }

    automationPaused = next
    if (next) {
        // 丢掉排队中的自动化动作，保持当前 VPN 状态不动
        offToken++
        startToken++
        aliveTargets.clear()
        offAppForeground = null
        stopWatchdog()
        log(ctx, "automation paused by tile")
    } else {
        log(ctx, "automation resumed by tile")
        val cfg = ConfigRepo.get(ctx)
        val foreground = lastForeground
        if (foreground != null &&
            foreground != cfg.vpnPackage &&
            isProcessAlive(ctx, foreground)
        ) {
            handleForeground(foreground)
        } else {
            handleNetworkChanged()
        }
        if (!cfg.enabled || cfg.vpnPackage.isEmpty()) {
            // 配置本身已停用，不做额外动作
        } else if (aliveTargets.isEmpty() && autoSession &&
            !(isWifiOnActive(cfg) && isNetworkAllowed(cfg))
        ) {
            turnOff("automation resumed without target")
        }
    }
    publishRuntime(ctx)
}
