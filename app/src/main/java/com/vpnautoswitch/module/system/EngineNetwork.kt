package com.vpnautoswitch.module.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.provider.Settings
import com.vpnautoswitch.module.Config

// 网络与 Wi-Fi：默认网络回调、SSID 读取、网络类型判定

internal fun Engine.registerNetworkCallback(ctx: Context) {
    if (networkCallback != null) {
        // 重复进入（开机重试）时不会再有 registered 那行日志，看起来像「回调没建起来」。
        // 顺手确认一次当前网络：开机早期注册成功、回调却迟迟不来时，lastTransport
        // 会一直停在 0，Wi-Fi 规则就整条失效。
        if (probeNetwork(ctx)) {
            log(ctx, "network callback alive, current ${transportLabel()} ssid=${currentSsid.orEmpty()}")
            handler.post {
                handleNetworkChanged()
                publishRuntime(ctx)
            }
        }
        return
    }
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // VPN 作为默认网络时 caps 只有 TRANSPORT_VPN，真正的上行是 Wi-Fi 还是移动数据
            // 要另外找。以前这里直接 return，等于 VPN 一连上就把网络类型冻在上一次的值，
            // 客户端自启并自动连接的用户，「指定 Wi-Fi」规则从来没生效过。
            val transport = transportOf(cm, caps)
            if (transport == 0) return
            // 信号强弱也会走这个回调，每次都写日志会把 500 行的缓冲冲干净，
            // 只在类型或 SSID 真的变了的时候记一行。
            val changed = transport != lastTransport
            lastTransport = transport
            val beforeSsid = currentSsid
            refreshSsid(ctx, transport == 1)
            if (changed || beforeSsid != currentSsid) {
                log(ctx, "network -> ${transportLabel()} ssid=${currentSsid.orEmpty()}")
            }
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
        // 注册只保证「之后的变化」，注册这一刻网络已经连好时会不会补一次回调，各家 ROM
        // 不一样。这里主动看一眼当前网络，把 lastTransport / SSID 先填上。
        if (probeNetwork(ctx)) {
            log(ctx, "network now ${transportLabel()} ssid=${currentSsid.orEmpty()}")
            handler.post {
                handleNetworkChanged()
                publishRuntime(ctx)
            }
        }
    } catch (t: Throwable) {
        log(ctx, "register network callback failed: $t")
    }
}

/**
 * 把 [NetworkCapabilities] 换算成模块内部使用的传输类型。
 *
 * 返回 0 表示这次认不出来（VPN 网络又找不到底层），调用方应当保留旧值而不是清零。
 */
private fun transportOf(cm: ConnectivityManager, caps: NetworkCapabilities): Int = when {
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> underlyingTransport(cm)
    else -> 3
}

/**
 * VPN 网络没有自己的传输类型，去所有网络里找一条非 VPN 的 Wi-Fi / 移动数据。
 *
 * 优先 Wi-Fi：同一个 VPN 下面可能同时挂着两个上行（Wi-Fi 和蜂窝），模块关心的是
 * 「用户现在走的是哪个」，有 Wi-Fi 就按 Wi-Fi 算。
 */
private fun underlyingTransport(cm: ConnectivityManager): Int {
    return try {
        var mobile = false
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return 1
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) mobile = true
        }
        if (mobile) 2 else 0
    } catch (t: Throwable) {
        0
    }
}

/**
 * 主动读一次默认网络，不依赖回调。
 *
 * @return 是否读到了可用的传输类型
 */
internal fun Engine.probeNetwork(ctx: Context): Boolean {
    // 切应用时会连着进来好几次，500ms 内的重复探测没有意义
    val now = SystemClock.elapsedRealtime()
    if (now - lastProbeAt < PROBE_MIN_INTERVAL_MS) return lastTransport != 0
    lastProbeAt = now
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val caps = try {
        cm.getNetworkCapabilities(cm.activeNetwork)
    } catch (t: Throwable) {
        null
    } ?: return false
    val transport = transportOf(cm, caps)
    if (transport == 0) return false
    lastTransport = transport
    refreshSsid(ctx, transport == 1)
    return true
}

private const val PROBE_MIN_INTERVAL_MS = 500L

internal fun Engine.transportLabel(): String = when (lastTransport) {
    1 -> "wifi"
    2 -> "mobile"
    3 -> "other"
    else -> "unknown"
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
    val state = when {
        !isWifi -> "off"
        clean != null -> "ok"
        else -> "masked"
    }
    val changed = clean != currentSsid || state != ssidState
    currentSsid = clean
    ssidState = state
    // 默认网络回调在信号强弱变化时也会触发，值没变就不必再写一遍 Settings：
    // 每次写入都会唤醒所有观察者，锁屏待机时这是实打实的耗电
    if (!changed) return clean
    try {
        Settings.Secure.putString(ctx.contentResolver, SSID_KEY, clean ?: "")
        Settings.Secure.putString(ctx.contentResolver, SSID_STATE_KEY, state)
    } catch (t: Throwable) {
        // 忽略
    }
    return clean
}

/**
 * 重新算一遍「当前 Wi-Fi 是否在关闭名单里」，只更新状态，不做任何开关动作。
 *
 * 网络回调没来、或者配置比网络晚到时，靠它按当下这一刻的网络补判断，
 * 而不是继续沿用上一次留下的 [wifiSuppressed]。
 *
 * @return 命中的 SSID，没命中返回 null
 */
internal fun Engine.refreshWifiSuppression(ctx: Context, cfg: Config): String? {
    val ssid = refreshSsid(ctx, lastTransport == 1)
    val hit = ssid?.takeIf { cfg.wifiOff.contains(it) }
    wifiSuppressed = hit != null
    return hit
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
    // 兜底：回调可能没来（或被 ROM 吞掉），lastTransport 会停在旧值上——开机时是 0，
    // 也可能是「先连移动数据、后连 Wi-Fi」留下的 2。两种情况都会让 SSID 读不到，
    // Wi-Fi 规则整条失效，所以这里每次都按当下网络重算，不做「是不是 0」的假设。
    probeNetwork(ctx)
    val cfg = ConfigRepo.getOrNull(ctx)
    if (cfg == null) {
        noteConfigMissing(ctx, "network")
        return
    }
    if (!cfg.enabled) return
    if (automationPaused) return

    // 指定 Wi-Fi 规则优先于「网络类型总开关」
    val hit = refreshWifiSuppression(ctx, cfg)
    if (hit != null) {
        if (VpnSwitcher.isActive(ctx.contentResolver)) {
            log(ctx, "wifi \"$hit\" -> turn off")
            turnOff("wifi off: $hit")
        } else {
            log(ctx, "wifi \"$hit\" -> keep off")
        }
        stopWatchdog()
        return
    }
    val ssid = currentSsid
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
