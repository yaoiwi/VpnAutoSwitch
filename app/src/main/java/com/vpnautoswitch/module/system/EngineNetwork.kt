package com.vpnautoswitch.module.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings
import com.vpnautoswitch.module.Config

// 网络与 Wi-Fi：默认网络回调、SSID 读取、网络类型判定

internal fun Engine.registerNetworkCallback(ctx: Context) {
    if (networkCallback != null) return
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            lastTransport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                else -> 3
            }
            refreshSsid(ctx, lastTransport == 1)
            handler.post {
                handleNetworkChanged()
                publishRuntime(ctx)
            }
        }

        override fun onLost(network: Network) {
            handler.post {
                handleNetworkChanged()
                publishRuntime(ctx)
            }
        }
    }
    try {
        cm.registerDefaultNetworkCallback(callback, handler)
        networkCallback = callback
        log(ctx, "network callback registered")
    } catch (t: Throwable) {
        log(ctx, "register network callback failed: $t")
    }
}

internal fun Engine.isNetworkAllowed(cfg: Config, pkg: String? = null): Boolean =
    EnginePolicy.isNetworkAllowedFor(
        lastTransport,
        pkg?.let { cfg.networkModeOf(it) }.orEmpty(),
        cfg.allowWifi,
        cfg.allowMobile,
    )

/**
 * 存活目标里是否还有在当前网络下能生效的。
 *
 * 没有存活目标时退回全局判定：此时多半是 Wi-Fi 规则或用户手动开启的场景，
 * 全局开关才是唯一说得清的判据。
 */
internal fun Engine.anyTargetAllowedOnNetwork(cfg: Config): Boolean {
    if (aliveTargets.isEmpty()) return isNetworkAllowed(cfg)
    return EnginePolicy.anyTargetAllowed(
        lastTransport,
        aliveTargets,
        cfg.networkOverride,
        cfg.allowWifi,
        cfg.allowMobile,
    )
}

internal fun Engine.isWifiOnActive(cfg: Config): Boolean {
    val ssid = currentSsid ?: return false
    return lastTransport == 1 && cfg.wifiOn.contains(ssid)
}

/**
 * 读取当前 Wi-Fi 名称并写进 Settings（App 侧没有定位权限，读不到 SSID）。
 *
 * 模块跑在 system_server，正常情况下 `connectionInfo.ssid` 不会脱敏；
 * 但 Android 13 起系统加了 SSID 脱敏，部分 ROM 对 system uid 也一并处理，
 * 读回来是 `<unknown ssid>`。这时退回按 networkId 查已配置网络——
 * 那条路径读的是用户自己保存的网络名，不受定位权限影响。
 */
internal fun Engine.refreshSsid(ctx: Context, isWifi: Boolean): String? {
    val raw = if (!isWifi) null else readSsid(ctx)
    val clean = raw?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
    currentSsid = clean
    val state = when {
        !isWifi -> "off"
        clean != null -> "ok"
        else -> "masked"
    }
    try {
        Settings.Secure.putString(ctx.contentResolver, SSID_KEY, clean ?: "")
        Settings.Secure.putString(ctx.contentResolver, SSID_STATE_KEY, state)
    } catch (t: Throwable) {
        // 忽略
    }
    return clean
}

private fun Engine.readSsid(ctx: Context): String? {
    val wm = try {
        ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    } catch (t: Throwable) {
        null
    } ?: return null
    val info = try {
        wm.connectionInfo
    } catch (t: Throwable) {
        null
    }
    val direct = info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
    if (direct != null) return direct

    // 兜底：连接信息里只剩 networkId（或带上 <unknown ssid>）时，去已保存网络里反查
    val netId = info?.networkId ?: -1
    if (netId < 0) return null
    return try {
        @Suppress("DEPRECATION")
        wm.configuredNetworks
            ?.firstOrNull { it.networkId == netId }
            ?.SSID
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
    } catch (t: Throwable) {
        // Android 13+ 对普通应用要定位权限；system_server 一般放行，被拦就当作读不到
        null
    }
}

internal fun Engine.handleNetworkChanged() {
    val ctx = context() ?: return
    val cfg = ConfigRepo.getOrNull(ctx)
    if (cfg == null) {
        noteConfigMissing(ctx, "network")
        return
    }
    if (!cfg.enabled) return
    if (automationPaused) return

    // 指定 Wi-Fi 规则优先于「网络类型总开关」
    val ssid = refreshSsid(ctx, lastTransport == 1)
    if (!ssid.isNullOrEmpty() && cfg.wifiOff.contains(ssid)) {
        wifiSuppressed = true
        if (VpnSwitcher.isActive(ctx.contentResolver)) {
            log(ctx, "wifi \"$ssid\" -> turn off")
            turnOff("wifi off: $ssid")
        } else {
            log(ctx, "wifi \"$ssid\" -> keep off")
        }
        stopWatchdog()
        return
    }
    wifiSuppressed = false
    if (!ssid.isNullOrEmpty() && cfg.wifiOn.contains(ssid)) {
        log(ctx, "wifi \"$ssid\" -> turn on")
        // 这是一个新的开启意图，取消此前排队的关闭任务
        offToken++
        ensureVpn(ctx, cfg)
        startWatchdog()
        return
    }

    if (!anyTargetAllowedOnNetwork(cfg)) {
        if (VpnSwitcher.isActive(ctx.contentResolver)) {
            log(ctx, "network type disabled -> turn off")
            turnOff("network type disabled")
        }
        return
    }
    if (aliveTargets.isNotEmpty() && offAppForeground == null) {
        ensureVpn(ctx, cfg)
        startWatchdog()
    }
}

/** 系统对 SSID 脱敏时返回的占位值 */
private const val UNKNOWN_SSID = "<unknown ssid>"
