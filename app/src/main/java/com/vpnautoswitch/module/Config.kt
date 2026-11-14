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
    val statusNotification: Boolean = false,
    /**
     * 日志与诊断报告里显示完整的 Wi-Fi 名称。
     *
     * 默认关：SSID 常常带着姓名、房号、公司名，而排障的标准动作是把日志贴到 issue。
     * 关掉时显示成 `Ho***Fi`，同一个名字在日志里始终是同一个样子，照样能对比「切没切网」。
     */
    val fullSsid: Boolean = false,
    /**
     * 规则组：一个组对应一个客户端。
     *
     * 空列表表示走「默认组」——就是上面那些顶层字段，行为和以前完全一样。
     * 非空时，顶层 [targets] / [wifiOn] / [vpnPackage] 依然算第一组，组列表是
     * 在它之外追加的。这样老配置不用迁移，新配置也不必把三十多个字段复制 N 份。
     */
    val rules: List<VpnRule> = emptyList()
) {

    /** 某个应用生效的网络条件：有覆盖用覆盖，否则看全局 */
    fun networkModeOf(packageName: String): String =
        networkOverride[packageName]?.takeIf { it.isNotEmpty() } ?: NET_FOLLOW

    /**
     * 把配置解析成「以某个客户端为准」的等效配置。
     *
     * 引擎整套逻辑都是按单客户端写的，多组只在入口处做一次翻译：把该组的
     * target / wifiOn / vpnPackage 换到顶层，其余三十多个开关保持全局。
     * 这样关旧客户端、开新客户端都只需要 `forClient(包名)`，不用把每个字段都改成按组存。
     *
     * @param ruleId 已知是哪一组时传进来。同一个客户端被两个组用的时候，
     *   只按包名找会拿到第一组的应用清单，切换过去就会算错目标。
     */
    fun forClient(packageName: String, ruleId: String? = null): Config {
        if (packageName.isEmpty() && ruleId == null) return this
        // 空串是「默认组」的 id，不是「没指定」。不区分的话会落到按包名猜的分支，
        // 默认组和某个规则组共用同一客户端时就会拿到错的应用清单
        if (ruleId != null && ruleId.isEmpty()) {
            return if (packageName.isEmpty()) this else copy(vpnPackage = packageName)
        }
        val rule = (ruleId?.let { id -> rules.firstOrNull { it.id == id && it.enabled } })
            ?: rules.firstOrNull { it.vpnPackage == packageName && it.enabled }
        if (rule == null) {
            // 没有对应的组：要么是默认组，要么是用户手动开的别的客户端
            return if (packageName.isEmpty()) this else copy(vpnPackage = packageName)
        }
        return copy(
            vpnPackage = rule.vpnPackage,
            targets = rule.targets,
            wifiOn = rule.wifiOn,
        )
    }

    /**
     * 默认组：顶层那套配置在切换队列里的表示。
     *
     * id 是空串（见 [forClient]），标签为空，其余照抄顶层。有它之后「切回默认组」
     * 和「切到某个规则组」走同一条路，不用在控制链里到处判 if。
     */
    fun defaultRule(): VpnRule = VpnRule(
        id = "",
        label = "",
        vpnPackage = vpnPackage,
        targets = targets,
        wifiOn = wifiOn,
    )

    /** 配置里出现过的所有客户端包名：默认组 + 每个规则组，用来在重启后认出「现在开着谁」 */
    fun knownClients(): Set<String> {
        val out = LinkedHashSet<String>(rules.size + 1)
        if (vpnPackage.isNotEmpty()) out.add(vpnPackage)
        rules.forEach { if (it.vpnPackage.isNotEmpty()) out.add(it.vpnPackage) }
        return out
    }

    /**
     * 有没有能用的客户端。
     *
     * 多组之后「没选客户端」不能只看顶层 [vpnPackage]：只配了规则组、没配默认组
     * 是合法配置，用顶层判会把模块当成没配好、直接关掉正在跑的 VPN。
     */
    fun hasAnyClient(): Boolean = knownClients().isNotEmpty()

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
        o.put("fullSsid", fullSsid)
        if (rules.isNotEmpty()) {
            val arr = JSONArray()
            rules.forEach { arr.put(it.toJson()) }
            o.put("rules", arr)
        }
        return o.toString()
    }

    companion object {
        const val PREFS_NAME = "vpn_autoswitch_config"
        internal const val KEY_JSON = "config_json"

        /**
         * 配置结构版本。
         *
         * 2：多了「单应用网络覆盖」与「常驻状态通知」。
         * 3：多了 [Config.fullSsid]。旧版本读到新配置只会忽略这个键，行为不变。
         * 4：多了 [Config.rules]。键缺省时和 3 完全一样；旧模块读到只会忽略，
         *   降级成「只认默认组」，不会误把别的组的应用当成自己的目标。
         */
        const val SCHEMA = 4

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
                    fullSsid = o.optBoolean("fullSsid", false),
                    rules = readRules(o.optJSONArray("rules")),
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

        /**
         * 规则组：id 或客户端为空的一律丢掉。
         *
         * 这两个字段缺一个，这一组就没法被切过来（认不出、也拉不起来），
         * 留在列表里只会让「第几个组」这种序号和界面上的对不上。
         */
        private fun readRules(arr: JSONArray?): List<VpnRule> {
            if (arr == null) return emptyList()
            val out = ArrayList<VpnRule>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val pkg = o.optString("vpn", "")
                if (id.isEmpty() || pkg.isEmpty()) continue
                out.add(
                    VpnRule(
                        id = id,
                        label = o.optString("label", ""),
                        enabled = o.optBoolean("on", true),
                        vpnPackage = pkg,
                        targets = readSet(o.optJSONArray("targets")),
                        wifiOn = readSet(o.optJSONArray("wifiOn")),
                    ),
                )
            }
            return out
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

/**
 * 一个规则组：一组应用 / 一个 Wi-Fi，对应一个 VPN 客户端。
 *
 * 只放「跟组有关」的四个字段。toast、强停、划掉任务、锁屏断开这些全都还是全局的——
 * 它们描述的是「模块怎么干活」，不是「用哪个客户端」，按组存只会让用户配 N 遍。
 *
 * 命中条件是「或」：应用在前台，或者当前 Wi-Fi 在这个组的 [wifiOn] 里。
 * 后者不需要配任何应用，场景就是「连上家里的 Wi-Fi 就全局走旁路由」。
 */
data class VpnRule(
    /** 稳定 id，改名换客户端都不变；切换编排和诊断靠它认人 */
    val id: String,
    /** 用户起的名字，例如「科学上网」「内网穿透」；空的时候界面上退化成客户端名 */
    val label: String = "",
    /** 关掉只是不参与匹配，配置留着，不用重新配 */
    val enabled: Boolean = true,
    val vpnPackage: String = "",
    val targets: Set<String> = emptySet(),
    val wifiOn: Set<String> = emptySet(),
) {

    /** 界面上怎么称呼这一组：没起名就用客户端包名，总比「未命名」有信息量 */
    fun displayName(): String = label.takeIf { it.isNotBlank() } ?: vpnPackage

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("label", label)
        o.put("on", enabled)
        o.put("vpn", vpnPackage)
        o.put("targets", JSONArray(targets.toList()))
        o.put("wifiOn", JSONArray(wifiOn.toList()))
        return o
    }

    companion object {
        /** 新建一组时用的 id：时间戳足够，用户不会在一毫秒内建两个组 */
        fun newId(at: Long = System.currentTimeMillis()): String = "g$at"
    }
}
