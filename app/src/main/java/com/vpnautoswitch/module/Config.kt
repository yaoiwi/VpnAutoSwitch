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
     * 划掉任务时什么时候断开。
     *
     * [DISMISS_CURRENT] 只认「正在用的那个」，后台残留的目标被忽略；
     * [DISMISS_ANY] 目标都在后台时，划掉任意一个就断；
     * [DISMISS_ALL] 所有目标都退出（或都划掉）之后才断。
     */
    val dismissMode: String = DISMISS_ANY,
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
    /**
     * Material 3 风格下的悬浮底栏。
     *
     * 两套风格各记各的：HyperOS 的下沉玻璃底栏是它的设计语言，M3 规范里底栏本就该贴底，
     * 所以这里默认关闭。用户在 M3 下打开后只影响 M3，切回 HyperOS 不会连带改掉那边的设置。
     */
    val md3FloatingBottomBar: Boolean = false,
    /** 点击底栏 / 列表项时震动 */
    val hapticFeedback: Boolean = true,
    /** 深浅色：system（跟随系统） / light / dark */
    val darkMode: String = "system",
    /** 界面风格：hyper（HyperOS/Miuix，默认） / md3（Material Design 3） */
    val themeStyle: String = THEME_HYPER,
    /**
     * Material 3 是否跟随壁纸取色（莫奈）。
     *
     * 打开后不再用固定的基线紫，而是让系统把它从壁纸里提取的种子色交给我们，
     * 换壁纸、换主题色，整个界面的配色会跟着变。系统低于 Android 12 时没有这套机制，
     * 会退回基线紫。
     */
    val md3Wallpaper: Boolean = true,
    /** 连上这些 Wi-Fi 时始终开启 VPN（优先于目标应用判断） */
    val wifiOn: Set<String> = emptySet(),
    /** 连上这些 Wi-Fi 时始终关闭 VPN，且不再自动开启 */
    val wifiOff: Set<String> = emptySet(),
    /**
     * 单个应用覆盖全局网络条件。
     *
     * 上面两个开关是「默认值」，这里只影响被点名的应用：比如全局允许 Wi-Fi 与移动数据，
     * 但某个下载工具设成「仅 Wi-Fi」，到移动数据下它就不会再把 VPN 拉起来。
     * 值取 [NET_WIFI] / [NET_MOBILE] / [NET_ANY]，不在表里就是跟随全局。
     */
    val networkOverride: Map<String, String> = emptyMap(),
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
    val disconnectOnScreenOff: Boolean = false,
    /**
     * 常驻状态通知。
     *
     * 通知由 system_server 里的模块发出（App 不常驻，没法实时感知状态），
     * 所以通知归属会显示成「Android 系统」，这是权限决定的，不是 bug。
     */
    val statusNotification: Boolean = false
) {

    /** 某个应用生效的网络条件：有覆盖用覆盖，否则看全局 */
    fun networkModeOf(packageName: String): String =
        networkOverride[packageName]?.takeIf { it.isNotEmpty() } ?: NET_FOLLOW

    fun toJson(): String {
        val o = JSONObject()
        o.put("app", "vpn-autoswitch")
        o.put("schema", SCHEMA)
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
        o.put("dismissMode", dismissMode)
        // 旧版模块只认这个布尔：留着，免得它把「任意目标」读成「只断当前应用」
        o.put("anyDismiss", dismissMode != DISMISS_CURRENT)
        o.put("allowVpnUi", allowVpnUi)
        o.put("offGuard", offGuard)
        o.put("hideVpnNotif", hideVpnNotification)
        o.put("glassBar", glassBottomBar)
        o.put("swipe", swipePaging)
        o.put("pageAnim", pageAnimation)
        o.put("floatingBar", floatingBottomBar)
        o.put("md3FloatingBar", md3FloatingBottomBar)
        o.put("haptic", hapticFeedback)
        o.put("darkMode", darkMode)
        o.put("themeStyle", themeStyle)
        o.put("md3Wallpaper", md3Wallpaper)
        o.put("wifiOn", JSONArray(wifiOn.toList()))
        o.put("wifiOff", JSONArray(wifiOff.toList()))
        val ov = JSONObject()
        networkOverride.forEach { (pkg, mode) -> if (mode.isNotEmpty()) ov.put(pkg, mode) }
        o.put("netOverride", ov)
        o.put("statusNotif", statusNotification)
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

        /** 配置结构版本：2 起多了「单应用网络覆盖」与「常驻状态通知」，旧版本读新配置会忽略这些字段 */
        const val SCHEMA = 2

        /** 界面风格：HyperOS / Miuix 那套 */
        const val THEME_HYPER = "hyper"

        /** 界面风格：Material Design 3 */
        const val THEME_MD3 = "md3"

        /** 应用网络条件：跟随全局 */
        const val NET_FOLLOW = ""

        /** 应用网络条件：仅 Wi-Fi 下生效 */
        const val NET_WIFI = "wifi"

        /** 应用网络条件：仅移动数据下生效 */
        const val NET_MOBILE = "mobile"

        /** 应用网络条件：任何网络下都生效（即便全局关了某种网络） */
        const val NET_ANY = "any"

        /** 划掉任务时：只断「正在用的那个」 */
        const val DISMISS_CURRENT = "current"

        /** 划掉任务时：目标都在后台，划掉任意一个就断 */
        const val DISMISS_ANY = "any"

        /** 划掉任务时：所有目标都退出后才断 */
        const val DISMISS_ALL = "all"

        /**
         * 把配置里的两个键归到一个档位上。
         *
         * 新键认得出就用新键；认不出（老配置、手改成乱码）就退回 [legacyAny]，
         * 不要直接吞成默认值——那会让用户明明关过「任意目标」却又被打开。
         */
        fun dismissModeOf(raw: String?, legacyAny: Boolean): String = when (raw) {
            DISMISS_CURRENT, DISMISS_ANY, DISMISS_ALL -> raw
            else -> if (legacyAny) DISMISS_ANY else DISMISS_CURRENT
        }

        private val NET_MODES = setOf(NET_WIFI, NET_MOBILE, NET_ANY)

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
                    dismissMode = dismissModeOf(
                        raw = o.optString("dismissMode", ""),
                        // 老配置没这个键，按原来的布尔还原，别把用户的设置读丢
                        legacyAny = o.optBoolean("anyDismiss", true),
                    ),
                    allowVpnUi = o.optBoolean("allowVpnUi", false),
                    offGuard = o.optBoolean("offGuard", true),
                    hideVpnNotification = o.optBoolean("hideVpnNotif", true),
                    glassBottomBar = o.optBoolean("glassBar", true),
                    swipePaging = o.optBoolean("swipe", true),
                    pageAnimation = o.optBoolean("pageAnim", true),
                    floatingBottomBar = o.optBoolean("floatingBar", true),
                    md3FloatingBottomBar = o.optBoolean("md3FloatingBar", false),
                    hapticFeedback = o.optBoolean("haptic", true),
                    wifiOn = readSet(o.optJSONArray("wifiOn")),
                    wifiOff = readSet(o.optJSONArray("wifiOff")),
                    networkOverride = readOverrides(o.optJSONObject("netOverride")),
                    statusNotification = o.optBoolean("statusNotif", false),
                    sortAppsByName = o.optBoolean("sortApps", false),
                    keepClientAlive = o.optBoolean("keepAlive", true),
                    forceStopImmediately = o.optBoolean("forceStopNow", false),
                    disconnectOnScreenOff = o.optBoolean("screenOff", false),
                    screenOffMinutes = o.optInt("screenOffMin", 10).coerceIn(1, 120),
                    darkMode = when (o.optString("darkMode", "system")) {
                        "dark" -> "dark"
                        "light" -> "light"
                        else -> "system"
                    },
                    themeStyle = if (o.optString("themeStyle", THEME_HYPER) == THEME_MD3) {
                        THEME_MD3
                    } else {
                        THEME_HYPER
                    },
                    md3Wallpaper = o.optBoolean("md3Wallpaper", true)
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

        /** 非法值直接丢掉：宁可退回「跟随全局」，也不要让一个拼错的模式让应用永远不触发 */
        private fun readOverrides(obj: JSONObject?): Map<String, String> {
            if (obj == null) return emptyMap()
            val out = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                val mode = obj.optString(pkg, "")
                if (pkg.isNotEmpty() && mode in NET_MODES) out[pkg] = mode
            }
            return out
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
