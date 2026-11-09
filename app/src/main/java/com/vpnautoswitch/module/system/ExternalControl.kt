package com.vpnautoswitch.module.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * 通过 VPN 客户端**自己暴露的外部控制入口**来启动 / 停止它。
 *
 * 背景：always-on VPN 只负责「让系统去 bind 它的 VpnService」，至于服务被 bind 之后
 * 要不要真的建立隧道，取决于客户端自己。Clash 系（Clash Meta / Clash for Android）
 * 就没有实现这个逻辑，所以设置写了也不会连上。
 *
 * 它们另外提供了一个导出的 `ExternalControlActivity`，响应：
 *   <包名>.action.START_CLASH
 *   <包名>.action.STOP_CLASH
 *   <包名>.action.TOGGLE_CLASH
 * 这是官方给「快捷方式 / 自动化工具」用的入口，等价于用户在 App 里点连接。
 */
internal object ExternalControl {

    /** 记住哪些客户端需要走外部控制，下次打开应用直接走这条路径，不用再等 always-on 超时 */
    private const val KEY = "vpn_autoswitch_external_pkgs"

    /**
     * 用户在「客户端适配」里手动测过、并确认要用的方式。
     *
     * 和上面的自动记忆分开存：自动记忆每次连上都会改写（这轮靠服务连上就记 service，
     * 下轮靠界面就连记 activity），用户测试出来的选择活不过一次重启。固定之后
     * [remember] 不再覆盖，只有「清除记忆」或再测一次才会改。
     */
    private const val PIN_KEY = "vpn_autoswitch_pinned_modes"

    /** 预热时等 onServiceConnected 的上限：客户端可能是冷启动，给足 6 秒 */
    private const val PREWARM_TIMEOUT_MS = 6_000L

    private val unsupportedStart = HashSet<String>()
    private val unsupportedStop = HashSet<String>()

    /** 查不到 VpnService 的包 → 探测失败的时刻（[SERVICE_PROBE_TTL_MS] 后重试） */
    private val noVpnService = HashMap<String, Long>()

    /** 包 → 上次把它的界面拉到前台的时刻（节流用，[UI_REOPEN_GUARD_MS] 内不重拉） */
    private val uiShownAt = HashMap<String, Long>()

    /** 同一个客户端的界面在这个窗口内最多被拉一次 */
    private const val UI_REOPEN_GUARD_MS = 20_000L

    /** 探测失败结果的保留时长：客户端升级 / 刚装好时 PackageManager 会短暂查不到 */
    private const val SERVICE_PROBE_TTL_MS = 60_000L

    /** 启动服务之后隔多久回来看进程还在不在 */
    private const val SERVICE_CHECK_DELAY_MS = 2_000L
    private var cachedKnown: MutableSet<String>? = null

    /**
     * 已确认的启动方式："activity"（客户端导出的外部控制界面）或 "service"（直接启 VpnService）。
     * 只有**确实靠外部手段连上**过才会记录，避免把 always-on 能连上的客户端误判成外部控制。
     */
    fun modeOf(ctx: Context, pkg: String): String? {
        if (pkg.isEmpty()) return null
        // 用户固定过就用固定的：自动记忆可能被下一轮的成功路径改写
        return pinnedMode(ctx, pkg)
            ?: known(ctx).firstOrNull { it.startsWith("$pkg|") }?.substringAfter('|')
    }

    fun isKnown(ctx: Context, pkg: String): Boolean = modeOf(ctx, pkg) != null

    /** App 进程里可能缓存了旧值；界面刷新时强制重新读 Settings。 */
    fun modeOfFresh(ctx: Context, pkg: String): String? {
        cachedKnown = null
        return modeOf(ctx, pkg)
    }

    fun remember(ctx: Context, pkg: String, mode: String) {
        if (pkg.isEmpty()) return
        // 用户固定过就不再改写，否则「测试外部控制」记下的 activity 会被某一次
        // 靠服务连上的成功覆盖掉，界面上看起来就是「选项自己变了」
        if (pinnedMode(ctx, pkg) != null) return
        val set = known(ctx)
        set.removeAll { it.startsWith("$pkg|") }
        set.add("$pkg|$mode")
        saveKnown(ctx, set)
    }

    /** 用户手动确认过的启动方式；null 表示跟随自动记忆 */
    fun pinnedMode(ctx: Context, pkg: String): String? {
        if (pkg.isEmpty()) return null
        return readPinned(ctx).firstOrNull { it.startsWith("$pkg|") }?.substringAfter('|')
    }

    fun pin(ctx: Context, pkg: String, mode: String) {
        if (pkg.isEmpty() || mode.isEmpty()) return
        val set = readPinned(ctx).toMutableSet()
        set.removeAll { it.startsWith("$pkg|") }
        set.add("$pkg|$mode")
        try {
            Settings.Secure.putString(ctx.contentResolver, PIN_KEY, set.joinToString(","))
        } catch (t: Throwable) {
            // 写不进去只影响下次是否还会被自动记忆覆盖
        }
    }

    fun unpin(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) return
        val set = readPinned(ctx).toMutableSet()
        if (!set.removeAll { it.startsWith("$pkg|") }) return
        try {
            Settings.Secure.putString(ctx.contentResolver, PIN_KEY, set.joinToString(","))
        } catch (t: Throwable) {
            // 忽略
        }
    }

    private fun readPinned(ctx: Context): Set<String> = try {
        Settings.Secure.getString(ctx.contentResolver, PIN_KEY)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.contains('|') }
            ?.toSet()
            ?: emptySet()
    } catch (t: Throwable) {
        emptySet()
    }

    fun forget(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) return
        val set = known(ctx)
        if (!set.removeAll { it.startsWith("$pkg|") }) return
        saveKnown(ctx, set)
    }

    /** 重新测试时清掉“这个客户端不行”的缓存，允许重新探测。 */
    fun resetProbe(pkg: String) {
        if (pkg.isEmpty()) return
        unsupportedStart.remove(pkg)
        unsupportedStop.remove(pkg)
        noVpnService.remove(pkg)
    }

    /**
     * 连续「静默启动没救回来、只能靠界面」的轮数。
     *
     * 1.6.5 起每次连接都先给静默两次机会，对服务起不来的客户端等于每轮白等
     * 两次初始化宽限（约 6~9 秒才看到界面）。连着两轮都只能靠界面，说明它的
     * VpnService 就是不会自己建隧道，下一轮直接开界面。
     */
    private const val SILENT_FAIL_KEY = "vpn_autoswitch_silent_fails"

    /** 连续这么多轮只能靠界面，就不再先试静默 */
    const val SILENT_FAIL_LIMIT = 2

    fun silentFails(ctx: Context, pkg: String): Int {
        if (pkg.isEmpty()) return 0
        return readSilentFails(ctx)[pkg] ?: 0
    }

    fun noteSilentFail(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) return
        val map = readSilentFails(ctx)
        map[pkg] = (map[pkg] ?: 0) + 1
        saveSilentFails(ctx, map)
    }

    /** 有一次静默就把它连上了，说明之前的记录已经过时（客户端升级、换了节点等） */
    fun clearSilentFails(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) return
        val map = readSilentFails(ctx)
        if (map.remove(pkg) == null) return
        saveSilentFails(ctx, map)
    }

    private fun readSilentFails(ctx: Context): MutableMap<String, Int> {
        val map = HashMap<String, Int>()
        try {
            Settings.Secure.getString(ctx.contentResolver, SILENT_FAIL_KEY)
                ?.split(',')
                ?.forEach { entry ->
                    val pkg = entry.substringBefore('=', "").trim()
                    val count = entry.substringAfter('=', "").trim().toIntOrNull()
                    if (pkg.isNotEmpty() && count != null && count > 0) map[pkg] = count
                }
        } catch (t: Throwable) {
            // 读不到就当没记过：最多再多等一轮
        }
        return map
    }

    private fun saveSilentFails(ctx: Context, map: Map<String, Int>) {
        try {
            val text = map.entries.joinToString(",") { "${it.key}=${it.value}" }
            Settings.Secure.putString(ctx.contentResolver, SILENT_FAIL_KEY, text)
        } catch (t: Throwable) {
            // 同上：只影响下一轮是否要重试静默
        }
    }

    private fun saveKnown(ctx: Context, set: MutableSet<String>) {
        try {
            Settings.Secure.putString(ctx.contentResolver, KEY, set.joinToString(","))
        } catch (t: Throwable) {
            // 写不进去只影响下次是否要重试，不影响功能
        }
    }

    private fun known(ctx: Context): MutableSet<String> {
        cachedKnown?.let { return it }
        val set = HashSet<String>()
        try {
            Settings.Secure.getString(ctx.contentResolver, KEY)
                ?.split(',')
                ?.map { it.trim() }
                // 旧版本只存了包名，没有 "|方式"，直接忽略，让该客户端回到 always-on 路径
                ?.filter { it.contains('|') }
                ?.let { set.addAll(it) }
        } catch (t: Throwable) {
            // 忽略
        }
        cachedKnown = set
        return set
    }

    /**
     * 启动 VPN：优先直接启动它的 VpnService（system_server 有权限启动非 exported 组件，
     * 而且不会把前台应用顶到后台）；不行再退回它导出的外部控制 Activity。
     */
    /** 只启动它的 VpnService（完全没有界面） */
    fun startVpnService(ctx: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        // 先记下原样：下面的 unstop 会把 stopped 标记抹掉，等失败之后再查就永远
        // 显示「未停用」，恰好把最有价值的那条线索洗掉了
        val stateBefore = Packages.stateLabel(ctx, pkg)
        // 被强停过的包处于 stopped 状态，startForegroundService 会「成功」返回，
        // 但系统不会真的把它拉起来 —— 日志里就是「started VPN service」连着打三次、
        // 却一直没有隧道。以前清标记放在写 always-on 的那条路径里，比这里晚十秒，
        // 开机后第一次打开目标应用必然踩空。启动之前先清，代价只是一次反射。
        Packages.unstop(pkg)
        val now = SystemClock.elapsedRealtime()
        // 探测失败的结果只保留一分钟：PackageManager 在客户端升级 / 刚装完时会短暂
        // 查不到服务，缓存到进程死亡为止的话，之后就永远「启动」不起来了。
        val probedAt = noVpnService[pkg]
        if (probedAt != null && now - probedAt < SERVICE_PROBE_TTL_MS) return false
        noVpnService.remove(pkg)

        val service = findVpnService(ctx, pkg)
        if (service == null) {
            noVpnService[pkg] = now
            // 必须留一条：否则日志里只有一串「restart VPN service (attempt N)」，
            // 却没有任何 started，看不出启动请求根本没发出去
            ModuleLog.append(
                ctx,
                "no VpnService in $pkg -> ${SERVICE_PROBE_TTL_MS / 1000} 秒内不再启动它",
            )
            return false
        }
        return try {
            // startForegroundService 返回 null 表示系统拒绝了这次启动（最常见的是包仍是
            // stopped 状态，或者被后台限制拦住）。以前不看返回值，一律记「started」，
            // 日志里就会出现「启动了三次、每次都 started，却十几秒没隧道」的假象。
            val started = ctx.startForegroundService(Intent().setComponent(ComponentName(pkg, service)))
            if (started == null) {
                ModuleLog.append(
                    ctx,
                    "start VPN service rejected: $service（拉起前包状态：$stateBefore）",
                )
                false
            } else {
                ModuleLog.append(ctx, "started VPN service $service")
                scheduleServiceCheck(ctx, pkg, service)
                true
            }
        } catch (t: Throwable) {
            // 这里不写进 noVpnService：抛异常是这一次调用的问题（服务已在启动中、
            // 权限瞬时不可用），不代表这个包里没有 VpnService，下次照样要试
            ModuleLog.append(ctx, "start VPN service failed: $t")
            false
        }
    }

    /**
     * 两秒后回来看一眼，确认服务真的活了。
     *
     * `startForegroundService` 返回非 null 只说明系统收下了这次调用，客户端进程有
     * 没有留住服务是另一回事。以前两者在日志里长得一模一样：一行 started，然后什么
     * 都没有；用户和排查的人只能猜是「系统拦的」还是「客户端自己退的」。
     */
    private fun scheduleServiceCheck(ctx: Context, pkg: String, service: String) {
        try {
            Engine.handler.postDelayed({
                val alive = Engine.isProcessAlive(ctx, pkg)
                ModuleLog.append(
                    ctx,
                    if (alive) {
                        "$service 启动 2s 后进程仍在，等隧道"
                    } else {
                        "$service 启动 2s 后进程已退出（请求被接受、进程没留住，客户端自己退了）"
                    },
                )
            }, SERVICE_CHECK_DELAY_MS)
        } catch (t: Throwable) {
            // 工作线程还没接上：跳过这次验证，不影响启动本身
        }
    }

    /** 停掉残留的 VpnService 实例（先把状态清干净，再启动才能建立新隧道） */
    fun stopVpnService(ctx: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val service = findVpnService(ctx, pkg) ?: return false
        return try {
            ctx.stopService(Intent().setComponent(ComponentName(pkg, service)))
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 预热客户端进程：bind 一下它的 VpnService，把进程拉进内存，但不建立隧道。
     *
     * bindService 只会触发 onCreate / onBind，客户端不会 establish——那一步在
     * onStartCommand 或用户点「连接」时才发生，所以不会出现「想关却把它打开了」。
     * 拿到连接后立刻解绑，不留悬挂 binder；失败就当作没预热，下次照旧冷启动。
     */
    fun prewarm(ctx: Context, pkg: String, handler: Handler? = null): Boolean {
        if (pkg.isEmpty()) return false
        // bindService 同样会被 stopped 拒绝，预热等于白做
        Packages.unstop(pkg)
        val service = findVpnService(ctx, pkg)
        if (service == null) {
            ModuleLog.append(ctx, "prewarm: 没找到 $pkg 的 VpnService，跳过")
            return false
        }

        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val connected = java.util.concurrent.atomic.AtomicBoolean(false)
        var conn: ServiceConnection? = null
        // 回调走调用方的工作线程：system_server 的主线程经常被别的事占住，
        // 3 秒都排不上队的话会误判成「没连上」
        val main = handler ?: Handler(Looper.getMainLooper())
        val release = {
            val c = conn
            if (c != null && released.compareAndSet(false, true)) {
                try {
                    ctx.unbindService(c)
                } catch (t: Throwable) {
                    // 已经解绑或从未绑上，忽略
                }
            }
        }

        conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                connected.set(true)
                ModuleLog.append(ctx, "prewarm: $pkg 进程已就绪")
                main.post { release() }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }

        return try {
            val bound = ctx.bindService(
                Intent().setComponent(ComponentName(pkg, service)),
                conn,
                Context.BIND_AUTO_CREATE
            )
            if (!bound) {
                ModuleLog.append(ctx, "prewarm: bind 被系统拒绝（$pkg）")
                released.set(true)
                false
            } else {
                // 客户端可能是冷启动，6 秒还没连上就放弃（也顺便看出进程到底起来没有）
                main.postDelayed({
                    if (!connected.get()) {
                        val alive = processAlive(ctx, pkg)
                        ModuleLog.append(
                            ctx,
                            "prewarm: 6 秒内没连上（客户端进程" +
                                (if (alive) "已启动，服务拒绝了绑定" else "没起来") +
                                "），放弃本次预热",
                        )
                    }
                    release()
                }, PREWARM_TIMEOUT_MS)
                true
            }
        } catch (t: Throwable) {
            released.set(true)
            ModuleLog.append(ctx, "prewarm failed: $t")
            false
        }
    }

    /** bind 卡住时用来判断「进程起来了但服务没让连」还是「进程根本没被拉起」 */
    private fun processAlive(ctx: Context, pkg: String): Boolean = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.runningAppProcesses?.any {
            it.processName == pkg || it.processName.startsWith("$pkg:")
        } == true
    } catch (t: Throwable) {
        false
    }

    /** 会短暂显示客户端界面：仅在用户明确允许时调用 */
    fun start(ctx: Context, pkg: String): Boolean {
        // 实测直接 startForegroundService 启动 TunService 时，服务起来了但隧道不建立
        // （拿不到应用自己的初始化上下文），所以优先走官方导出的外部控制入口。
        if (launch(ctx, pkg, "action.START_CLASH", unsupportedStart)) return true
        return startVpnService(ctx, pkg)
    }

    /**
     * 把客户端界面拉到前台，让它自己连。
     *
     * 这是「允许短暂打开 VPN 客户端界面」真正的兜底。以前那条路径调的还是 [start]：
     * 没有 START_CLASH 入口的客户端（FlClash 改版这类）会被当成「再启动一次服务」，
     * 界面一次都没出来过，最后只留一句「无法在后台拉起」。日志看着像试过了，其实没有。
     */
    fun openUi(ctx: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        // 重连是一轮一轮来的，每轮都把界面顶到最前面，用户会觉得这个应用被劫持了。
        // 刚拉过一次就认为「已经在这条路上」，直接返回成功，让上层别再退回去启动服务。
        val now = SystemClock.elapsedRealtime()
        val shownAt = uiShownAt[pkg] ?: 0L
        if (shownAt != 0L && now - shownAt < UI_REOPEN_GUARD_MS) return true
        val intent = try {
            ctx.packageManager.getLaunchIntentForPackage(pkg)
        } catch (t: Throwable) {
            null
        } ?: run {
            ModuleLog.append(ctx, "$pkg 没有可启动的界面")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            ctx.startActivity(intent)
            uiShownAt[pkg] = now
            ModuleLog.append(ctx, "brought $pkg UI to front")
            true
        } catch (t: Throwable) {
            ModuleLog.append(ctx, "open $pkg UI failed: $t")
            false
        }
    }

    /**
     * 用「看得见」的方式拉起客户端：先试官方外部控制入口（不弹界面），
     * 再试直接启动 VpnService，都起不来才打开应用界面。
     * 只在用户允许短暂打开界面时才该走这条路。
     */
    fun startWithUi(ctx: Context, pkg: String): Boolean = startModeWithUi(ctx, pkg) != null

    /**
     * 同 [startWithUi]，但回报**实际走通的那条路**。
     *
     * 以前这里是「START_CLASH 失败就开界面」，中间那一步 `startVpnService` 被跳过了。
     * FlClash 改版没有 START_CLASH，服务却一启动就能连上（日志 03:28:40 启动、
     * 03:28:44 隧道通），于是每次都被当成「只能靠界面」，把它的界面一次一次拉到前台。
     *
     * @return "activity"（外部控制入口）/ "service"（VpnService）/ "ui"（界面）/ null
     */
    fun startModeWithUi(ctx: Context, pkg: String): String? {
        if (launch(ctx, pkg, "action.START_CLASH", unsupportedStart)) return "activity"
        if (startVpnService(ctx, pkg)) return "service"
        return if (openUi(ctx, pkg)) "ui" else null
    }

    /**
     * 同 [start]，但回报**实际走通的那条路**。
     *
     * 以前调用方一律把成功记成 "activity"，可 [start] 在 `START_CLASH` 失败后会退回
     * 直接启动 VpnService —— 那和界面没有任何关系。FlClash 就是这么被记错的：
     * 日志 03:12:23 只启动了服务、03:12:28 却写下 `external mode 'activity' works`，
     * 下一次进到 activity 分支就跑去弹它的界面。
     *
     * @return "activity"（客户端导出的外部控制入口）/ "service"（直接启动 VpnService）/ null
     */
    fun startMode(ctx: Context, pkg: String): String? {
        if (launch(ctx, pkg, "action.START_CLASH", unsupportedStart)) return "activity"
        return if (startVpnService(ctx, pkg)) "service" else null
    }

    /** 找出包里声明了 BIND_VPN_SERVICE 的服务（VpnService 实现） */
    private fun findVpnService(ctx: Context, pkg: String): String? = try {
        val info = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SERVICES)
        info.services?.firstOrNull {
            it.permission == "android.permission.BIND_VPN_SERVICE"
        }?.name ?: info.services?.firstOrNull {
            it.name.contains("Tun", ignoreCase = true)
        }?.name
    } catch (t: Throwable) {
        null
    }

    fun stop(ctx: Context, pkg: String): Boolean =
        launch(ctx, pkg, "action.STOP_CLASH", unsupportedStop)

    /**
     * 无 UI 的停止请求。Clash 系在进程内注册了 `\.intent.action.CLASH_REQUEST_STOP`
     * 的接收器，VPN 还连着就说明进程一定活着，所以这条广播能被收到。
     */
    fun requestStop(ctx: Context, pkg: String): Boolean = try {
        if (pkg.isEmpty()) {
            false
        } else {
            ctx.sendBroadcast(Intent("$pkg.intent.action.CLASH_REQUEST_STOP").setPackage(pkg))
            true
        }
    } catch (t: Throwable) {
        false
    }

    private fun launch(
        ctx: Context,
        pkg: String,
        suffix: String,
        unsupported: MutableSet<String>
    ): Boolean {
        if (pkg.isEmpty() || pkg in unsupported) return false

        val action = "$pkg.$suffix"
        val intent = Intent(action)
            .setPackage(pkg)
            .addCategory(Intent.CATEGORY_DEFAULT)

        val resolved = try {
            ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (t: Throwable) {
            null
        }
        if (resolved == null) {
            if (unsupported.add(pkg)) {
                // 同一个入口 start / stop 都会查，所以文案不能写死「关闭」——
                // 启动路径上出现「改用后面的手段关闭」会把人带到反方向。
                ModuleLog.append(ctx, "$pkg 没有 $suffix 入口，改用备用方式")
            }
            return false
        }

        return try {
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            ctx.startActivity(intent)
            ModuleLog.append(ctx, "external control: ${resolved.activityInfo.name} ($suffix)")
            true
        } catch (t: Throwable) {
            ModuleLog.append(ctx, "external control failed: $t")
            false
        }
    }
}
