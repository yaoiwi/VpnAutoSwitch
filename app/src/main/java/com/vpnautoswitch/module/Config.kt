package com.vpnautoswitch.module

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模块配置。模块 App 负责写入，system_server 侧的 hook 通过 [ConfigProvider] 读取。
 */
data class Config(
    val enabled: Boolean = true,
    val targets: Set<String> = emptySet(),
    /** 打开这些应用时，如果 VPN 正连着就自动断开（优先级高于 [targets]） */
    val offTargets: Set<String> = emptySet(),
    val vpnPackage: String = "",
    val lockdown: Boolean = false,
    val restoreOriginal: Boolean = true,
    /** Wi-Fi 网络下是否生效 */
    val allowWifi: Boolean = true,
    /** 移动数据网络下是否生效 */
    val allowMobile: Boolean = true,
    /** VPN 开/关时是否弹 Toast */
    val toastEnabled: Boolean = true,
    /** 清设置断不掉时，是否强制停止 VPN 应用进程 */
    val forceStopVpn: Boolean = true,
    /** 从最近任务划掉应用时，即使进程仍在运行也断开 VPN */
    val disconnectOnTaskRemoved: Boolean = true,
    /**
     * 目标都在后台时，划掉其中任意一个也断开 VPN。
     *
     * 关掉时只在划掉「正在用的那个」才断开；后台残留的目标会被忽略。
     */
    val disconnectOnAnyDismiss: Boolean = true,
    /** 无法用后台方式拉起时，是否允许短暂打开 VPN 客户端界面（默认不允许） */
    val allowVpnUi: Boolean = false,
    /** 关闭后继续观察几秒，客户端自己把 VPN 拉回来时再关一次 */
    val offGuard: Boolean = true,
    /** 是否隐藏系统「始终开启的 VPN 已断开连接」通知 */
    val hideVpnNotification: Boolean = true,
    /** 底栏是否使用液态玻璃效果 */
    val glassBottomBar: Boolean = true,
    /** 左右滑动内容区切换标签 */
    val swipePaging: Boolean = true,
    /** 切页时的液态玻璃滑动动画 */
    val pageAnimation: Boolean = true,
    /** 悬浮底栏：滚动时自动隐藏 */
    val floatingBottomBar: Boolean = true,
    /** 点击底栏 / 列表项时震动 */
    val hapticFeedback: Boolean = true,
    /** 深浅色：system（跟随系统） / light / dark */
    val darkMode: String = "system",
    /** 连上这些 Wi-Fi 时始终开启 VPN（优先于目标应用判断） */
    val wifiOn: Set<String> = emptySet(),
    /** 连上这些 Wi-Fi 时始终关闭 VPN，且不再自动开启 */
    val wifiOff: Set<String> = emptySet(),
    /** 已选应用列表按名称排序（默认按添加顺序） */
    val sortAppsByName: Boolean = false,
    /** 关闭 VPN 后保留客户端进程（下次打开应用时不用冷启动，代价是客户端常驻后台） */
    val keepClientAlive: Boolean = true,
    /**
     * 关闭时不走温和手段，直接强制停止客户端。
     *
     * 默认关：先发停止广播、点快捷磁贴，实在关不掉才强停。开着「保留客户端进程」时
     * 这两个开关互相矛盾（一个要留进程、一个要杀进程），界面上会置灰后者。
     */
    val forceStopImmediately: Boolean = false,
    /** 锁屏后等待多久断开 VPN（分钟）；需要 [disconnectOnScreenOff] 打开 */
    val screenOffMinutes: Int = 10,
    /** 锁屏超过 [screenOffMinutes] 分钟后断开 VPN（默认关） */
    val disconnectOnScreenOff: Boolean = false
) {

    fun toJson(): String {
        val o = JSONObject()
        o.put("app", "vpn-autoswitch")
        o.put("schema", 1)
        o.put("enabled", enabled)
        o.put("targets", JSONArray(targets.toList()))
        o.put("offTargets", JSONArray(offTargets.toList()))
        o.put("vpn", vpnPackage)
        o.put("lockdown", lockdown)
        o.put("restore", restoreOriginal)
        o.put("allowWifi", allowWifi)
        o.put("allowMobile", allowMobile)
        o.put("toast", toastEnabled)
        o.put("forceStop", forceStopVpn)
        o.put("taskRemoved", disconnectOnTaskRemoved)
        o.put("anyDismiss", disconnectOnAnyDismiss)
        o.put("allowVpnUi", allowVpnUi)
        o.put("offGuard", offGuard)
        o.put("hideVpnNotif", hideVpnNotification)
        o.put("glassBar", glassBottomBar)
        o.put("swipe", swipePaging)
        o.put("pageAnim", pageAnimation)
        o.put("floatingBar", floatingBottomBar)
        o.put("haptic", hapticFeedback)
        o.put("darkMode", darkMode)
        o.put("wifiOn", JSONArray(wifiOn.toList()))
        o.put("wifiOff", JSONArray(wifiOff.toList()))
        o.put("sortApps", sortAppsByName)
        o.put("keepAlive", keepClientAlive)
        o.put("forceStopNow", forceStopImmediately)
        o.put("screenOff", disconnectOnScreenOff)
        o.put("screenOffMin", screenOffMinutes)
        return o.toString()
    }

    companion object {
        const val PREFS_NAME = "vpn_autoswitch_config"
        internal const val KEY_JSON = "config_json"

        fun parse(raw: String?): Config? {
            if (raw.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(raw)
                val set = readSet(o.optJSONArray("targets"))
                val offSet = readSet(o.optJSONArray("offTargets"))
                Config(
                    enabled = o.optBoolean("enabled", true),
                    targets = set,
                    offTargets = offSet,
                    vpnPackage = o.optString("vpn", ""),
                    lockdown = o.optBoolean("lockdown", false),
                    restoreOriginal = o.optBoolean("restore", true),
                    allowWifi = o.optBoolean("allowWifi", true),
                    allowMobile = o.optBoolean("allowMobile", true),
                    toastEnabled = o.optBoolean("toast", true),
                    forceStopVpn = o.optBoolean("forceStop", true),
                    disconnectOnTaskRemoved = o.optBoolean("taskRemoved", true),
                    disconnectOnAnyDismiss = o.optBoolean("anyDismiss", true),
                    allowVpnUi = o.optBoolean("allowVpnUi", false),
                    offGuard = o.optBoolean("offGuard", true),
                    hideVpnNotification = o.optBoolean("hideVpnNotif", true),
                    glassBottomBar = o.optBoolean("glassBar", true),
                    swipePaging = o.optBoolean("swipe", true),
                    pageAnimation = o.optBoolean("pageAnim", true),
                    floatingBottomBar = o.optBoolean("floatingBar", true),
                    hapticFeedback = o.optBoolean("haptic", true),
                    wifiOn = readSet(o.optJSONArray("wifiOn")),
                    wifiOff = readSet(o.optJSONArray("wifiOff")),
                    sortAppsByName = o.optBoolean("sortApps", false),
                    keepClientAlive = o.optBoolean("keepAlive", true),
                    forceStopImmediately = o.optBoolean("forceStopNow", false),
                    disconnectOnScreenOff = o.optBoolean("screenOff", false),
                    screenOffMinutes = o.optInt("screenOffMin", 10).coerceIn(1, 120),
                    darkMode = when (o.optString("darkMode", "system")) {
                        "dark" -> "dark"
                        "light" -> "light"
                        else -> "system"
                    }
                )
            } catch (t: Throwable) {
                null
            }
        }

        private fun readSet(arr: JSONArray?): Set<String> {
            if (arr == null) return emptySet()
            val set = LinkedHashSet<String>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optString(i, "")
                if (p.isNotEmpty()) set.add(p)
            }
            return set
        }

        fun load(ctx: Context): Config {
            val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_JSON, null)
            return parse(raw) ?: Config()
        }

        fun save(ctx: Context, config: Config) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_JSON, config.toJson())
                .commit()
        }
    }
}
