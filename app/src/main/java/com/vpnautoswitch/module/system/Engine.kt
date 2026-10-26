package com.vpnautoswitch.module.system

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import com.vpnautoswitch.module.Config
import com.vpnautoswitch.module.ModuleContract
import de.robv.android.xposed.XposedBridge

/**
 * 状态机：所有逻辑都在独立工作线程执行，绝不在 AMS 的锁里做跨进程调用。
 *
 * 核心思路：不信任任何一个「缓存状态」，而是每 5 秒对账一次——
 *   目标应用还活着 + 网络允许  → VPN 必须是开着的（用户手动关掉也会被拉回来）
 *   目标应用任务被移除 / 进程没了 → VPN 立刻关掉
 */
internal object Engine {

    private const val TAG = "VpnAutoSwitch"
    const val HEARTBEAT_KEY = "vpn_autoswitch_heartbeat"

    /** 当前加载进 system_server 的模块版本号，App 用它判断「是不是旧代码」 */
    const val MODULE_VERSION_KEY = "vpn_autoswitch_module_version"

    /** 当前 Wi-Fi 名称，写给 App 显示（App 自己没有定位权限，读不到 SSID） */
    const val SSID_KEY = "vpn_autoswitch_ssid"

    /** App 通过这个 action 请求重启（受 signature 权限保护） */
    /** App 请求清空运行日志（App 没有 WRITE_SECURE_SETTINGS，只能让 system_server 代删） */
    const val ACTION_CLEAR_LOG = "com.vpnautoswitch.module.action.CLEAR_LOG"
    private const val REBOOT_PERMISSION = "com.vpnautoswitch.module.permission.REBOOT"

    private const val RECONCILE_INTERVAL_MS = 5_000L

    /** 独立心跳：不依赖目标应用或看门狗，App 用它判断模块是否仍在运行 */
    private const val HEARTBEAT_INTERVAL_MS = 15_000L

    /**
     * 重写 always-on 设置会先清空再写回，等于让系统 unbind + rebind 一次 VpnService。
     * 客户端（尤其 Clash 系）建立隧道要好几秒，写得太频繁会一直打断它，
     * 表现就是「connected 永远 false，偶尔才成功」——所以这里给足冷却时间。
     */
    private const val REAPPLY_GUARD_MS = 12_000L
    private const val REAPPLY_SLOW_MS = 60_000L
    private const val MAX_FAST_REAPPLY = 4
    private const val TILE_AFTER_REAPPLY = 2
    private const val TILE_COOLDOWN_MS = 30_000L
    private const val MAX_TILE_ATTEMPTS = 3

    /** 客户端不响应 always-on 时，改用它的外部控制入口（START_CLASH / STOP_CLASH） */
    private const val EXTERNAL_COOLDOWN_MS = 20_000L
    private const val MAX_EXTERNAL_ATTEMPTS = 3

    /** 已经确认要用外部控制的客户端，重试间隔可以短一些 */
    private const val EXTERNAL_ONLY_GUARD_MS = 6_000L

    /** 目标进程刚死时先等一会儿再关 VPN，避免应用秒重启导致来回抖 */
    private const val OFF_DEBOUNCE_MS = 2_500L

    /** 强制停止后隧道可能还要几秒才消失：分批复查，仍连着就再停一次 */
    private const val FORCE_STOP_RECHECK_MS = 2_000L
    private const val MAX_FORCE_STOP_ATTEMPTS = 3
    private const val FAILURE_HINT_AT = 5

    /** 初始化重试期间最多缓存的事件数，防止异常 ROM 下无限增长 */
    private const val MAX_PENDING_EVENTS = 64

    private val thread = HandlerThread("VpnAutoSwitch").apply {
        priority = Thread.NORM_PRIORITY
        start()
    }
    private val handler = Handler(thread.looper)
    private var heartbeatStarted = false

    private val heartbeat = object : Runnable {
        override fun run() {
            val ctx = context()
            if (ctx != null) {
                beat(ctx)
                publishRuntime(ctx)
            }
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    @Volatile
    private var appContext: Context? = null

    private val aliveTargets = HashSet<String>()

    /**
     * 当前正在前台的「打开即关闭」应用。
     * 非空期间不再自动开启 VPN，直到它自己退出进程、或用户回到目标应用。
     */
    private var offAppForeground: String? = null

    /**
     * 这一轮 VPN 是模块自己拉起来的（而不是用户手动连的）。
     * 关掉后清零；看门狗只在它为 true 时才敢主动断开，避免误杀用户手动连的 VPN。
     */
    private var autoSession = false

    /**
     * 用户手动关掉了 VPN（下拉磁贴 / 在 VPN 客户端里点断开）。
     * 置 true 后不再自动重连，直到他重新打开目标应用（不是应用内部的页面切换）。
     */
    private var userOff = false

    /** 最近出现在前台的包：用来区分「重新打开应用」和「应用内部 resume」 */
    private var lastForeground: String? = null
    private var watchdogRunning = false
    private var lastApplyAt = 0L
    private var lastTileClickAt = 0L
    private var tileAttempts = 0
    private var reapplyCount = 0
    private var externalAttempts = 0
    private var clientTestToken = 0
    private var lastExternalAt = 0L
    /** 本轮接管是否已经提示过「VPN 已开启」，避免设置写完但隧道没起来就误报 */
    private var connectedNotified = false
    private var wasConnected = false
    private var offToken = 0
    private var startToken = 0

    /** 每次关闭验证链的代次；新的开启意图或新的关闭链都会推进它 */
    private var offGeneration = 0

    /** 快捷磁贴暂停自动联动；只影响自动化，不改用户配置 */
    private var automationPaused = false

    /** 初始化还没完成时按顺序缓存事件，避免先操作 VPN、随后又被 boot 清理掉 */
    private sealed interface PendingEvent {
        data class Foreground(val packageName: String) : PendingEvent
        data class Died(val packageName: String) : PendingEvent
        data class TaskRemoved(val packageName: String) : PendingEvent
    }

    private val pendingEvents = ArrayDeque<PendingEvent>()

    /** 本轮是通过哪种外部方式发起启动的，连上后据此记住该客户端 */
    private var pendingExternalMode: String? = null

    private var failureStreak = 0
    private var failureLogged = false

    /** 当前连着的 Wi-Fi 名称（不带引号）；非 Wi-Fi 时为 null */
    private var currentSsid: String? = null

    /** 当前 Wi-Fi 命中「指定 Wi-Fi 关 VPN」规则：期间不再自动开启 */
    private var wifiSuppressed = false

    /** 当前默认网络的传输类型：0 未知 / 1 Wi-Fi / 2 移动数据 / 3 其他 */
    private var lastTransport = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var rebootReceiverRegistered = false
    private var booted = false

    fun attach(ctx: Context?) {
        if (ctx != null) appContext = ctx
        handler.post { onBoot(0) }
    }

    fun onForeground(packageName: String) {
        handler.post { dispatchForeground(packageName) }
    }

    fun onProcessDied(packageName: String) {
        handler.post { dispatchProcessDied(packageName) }
    }

    fun onTaskRemoved(packageName: String) {
        handler.post { dispatchTaskRemoved(packageName) }
    }

    private fun dispatchForeground(packageName: String) {
        if (!booted) {
            enqueue(PendingEvent.Foreground(packageName))
            return
        }
        handleForeground(packageName)
        publishRuntime(context())
    }

    private fun dispatchProcessDied(packageName: String) {
        if (!booted) {
            enqueue(PendingEvent.Died(packageName))
            return
        }
        handleProcessDied(packageName)
        publishRuntime(context())
    }

    private fun dispatchTaskRemoved(packageName: String) {
        if (!booted) {
            enqueue(PendingEvent.TaskRemoved(packageName))
            return
        }
        handleTaskRemoved(packageName)
        publishRuntime(context())
    }

    private fun enqueue(event: PendingEvent) {
        if (pendingEvents.size >= MAX_PENDING_EVENTS) pendingEvents.removeFirst()
        pendingEvents.addLast(event)
    }

    private fun replayPendingEvents() {
        while (pendingEvents.isNotEmpty()) {
            when (val event = pendingEvents.removeFirst()) {
                is PendingEvent.Foreground -> handleForeground(event.packageName)
                is PendingEvent.Died -> handleProcessDied(event.packageName)
                is PendingEvent.TaskRemoved -> handleTaskRemoved(event.packageName)
            }
        }
    }

    // ---------------------------------------------------------------- 启动

    /**
     * 初始化。
     *
     * LSPosed 注入 system_server 非常早，这时候 ActivityManagerService 还没注册，
     * ContextImpl 里拿到的 IActivityManager 是 null，于是
     *   - Settings.Secure 读写（getContentProvider）
     *   - registerReceiver
     * 都会抛 NullPointerException。日志里就是那两条
     * `heartbeat failed` / `register reboot receiver failed`。
     *
     * 所以这里不能「失败就算了」，必须每 2 秒重试：等 AMS 上线（通常开机后 5~20 秒），
     * 心跳、广播接收器、网络回调才真正建立起来。
     */
    private fun onBoot(attempt: Int) {
        val ctx = context()
        val previousVersion = if (ctx == null) {
            0
        } else {
            try {
                Settings.Secure.getInt(ctx.contentResolver, MODULE_VERSION_KEY, 0)
            } catch (t: Throwable) {
                0
            }
        }
        if (ctx == null || !beat(ctx)) {
            retryBoot(attempt)
            return
        }
        if (booted) return

        // 升级后的第一轮启动清空旧日志，保证应用内只看到当前版本的记录
        if (EnginePolicy.shouldClearLogsOnBoot(
                previousVersion,
                com.vpnautoswitch.module.BuildConfig.VERSION_CODE,
            )
        ) {
            ModuleLog.clear(ctx)
            if (previousVersion > 0) {
                log(
                    ctx,
                    "upgraded from v$previousVersion to v${com.vpnautoswitch.module.BuildConfig.VERSION_CODE}, " +
                        "old logs cleared"
                )
            }
        }

        offAppForeground = null
        cleanupStaleState(ctx)
        registerNetworkCallback(ctx)
        if (!registerRebootReceiver(ctx)) {
            retryBoot(attempt)
            return
        }
        booted = true
        automationPaused = readPaused(ctx)
        log(ctx, "module ready (death hooks + watchdog)")
        startHeartbeat()
        replayPendingEvents()
        publishRuntime(ctx)
    }

    private fun readPaused(ctx: Context): Boolean = try {
        Settings.Secure.getInt(ctx.contentResolver, ModuleContract.PAUSED_KEY, 0) == 1
    } catch (t: Throwable) {
        false
    }

    /**
     * 快捷磁贴 / App 触发的暂停开关。
     *
     * 暂停只停止自动化，不主动断开当前 VPN；恢复时按当前前台与网络规则重新对账。
     */
    private fun togglePause(ctx: Context) {
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

    /**
     * App 触发的客户端启动方式测试。
     *
     * 只测试“外部控制 Activity”和“直接启动 VpnService”，不测试 always-on：
     * 测试 always-on 会改写系统常驻 VPN 设置，可能打断用户当前连接。
     */
    private fun testClient(ctx: Context, pkg: String, mode: String) {
        val token = ++clientTestToken
        val cfg = ConfigRepo.get(ctx)
        when {
            pkg.isEmpty() -> {
                RuntimeDiagnostics.publishClientTest(ctx, pkg, mode, "failure", "没有选择 VPN 客户端")
                return
            }
            cfg.vpnPackage != pkg -> {
                RuntimeDiagnostics.publishClientTest(
                    ctx, pkg, mode, "failure", "VPN 客户端已变化，请重新打开适配向导"
                )
                return
            }
            mode != "activity" && mode != "service" -> {
                RuntimeDiagnostics.publishClientTest(ctx, pkg, mode, "failure", "未知的测试方式")
                return
            }
            hasVpnTransport(ctx) -> {
                RuntimeDiagnostics.publishClientTest(
                    ctx, pkg, mode, "failure", "当前已有 VPN 连接，请先断开后再测试"
                )
                return
            }
        }

        RuntimeDiagnostics.publishClientTest(
            ctx, pkg, mode, "running",
            if (mode == "activity") "正在请求客户端外部控制…" else "正在启动 VpnService…",
        )
        ExternalControl.resetProbe(pkg)
        log(ctx, "client test start: $pkg / $mode")

        val started = if (mode == "activity") {
            ExternalControl.start(ctx, pkg)
        } else {
            ExternalControl.startVpnService(ctx, pkg)
        }
        if (!started) {
            val detail = if (mode == "activity") {
                "客户端没有可用的外部控制入口"
            } else {
                "无法启动客户端的 VpnService"
            }
            RuntimeDiagnostics.publishClientTest(ctx, pkg, mode, "failure", detail)
            log(ctx, "client test failed to start: $detail")
            return
        }

        pollClientTest(ctx, pkg, mode, token, 0)
    }

    private fun pollClientTest(
        ctx: Context,
        pkg: String,
        mode: String,
        token: Int,
        attempt: Int,
    ) {
        if (token != clientTestToken) return
        if (hasVpnTransport(ctx)) {
            ExternalControl.remember(ctx, pkg, mode)
            RuntimeDiagnostics.publishClientTest(
                ctx, pkg, mode, "success",
                if (mode == "activity") "外部控制成功，已记住该方式" else "VpnService 启动成功，已记住该方式",
            )
            log(ctx, "client test success: $pkg / $mode")
            return
        }
        if (attempt >= 30) {
            RuntimeDiagnostics.publishClientTest(
                ctx, pkg, mode, "failure", "15 秒内没有检测到 VPN transport",
            )
            log(ctx, "client test timeout: $pkg / $mode")
            return
        }
        handler.postDelayed({
            pollClientTest(ctx, pkg, mode, token, attempt + 1)
        }, 500L)
    }

    private fun forgetClientMode(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) return
        ExternalControl.forget(ctx, pkg)
        RuntimeDiagnostics.publishClientTest(ctx, pkg, "", "idle", "已清除记住的启动方式")
        log(ctx, "client mode forgotten: $pkg")
    }

    private fun startHeartbeat() {
        if (heartbeatStarted) return
        heartbeatStarted = true
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
    }

    private fun retryBoot(attempt: Int) {
        if (attempt < 90) {
            handler.postDelayed({ onBoot(attempt + 1) }, 2_000L)
        } else {
            XposedBridge.log("[$TAG] boot init gave up after $attempt attempts")
        }
    }

    /** @return true 表示 Settings 写入成功，说明 AMS / ContentProvider 已经就绪 */
    private fun beat(ctx: Context): Boolean {
        return try {
            Settings.Secure.putLong(ctx.contentResolver, HEARTBEAT_KEY, System.currentTimeMillis())
            Settings.Secure.putInt(
                ctx.contentResolver,
                MODULE_VERSION_KEY,
                com.vpnautoswitch.module.BuildConfig.VERSION_CODE
            )
            true
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] heartbeat failed (AMS not ready?): $t")
            false
        }
    }

    private fun log(ctx: Context?, message: String) {
        XposedBridge.log("[$TAG] $message")
        ModuleLog.append(ctx, message)
    }

    private fun cleanupStaleState(ctx: Context) {
        try {
            if (VpnSwitcher.isActive(ctx.contentResolver)) {
                val cfg = ConfigRepo.get(ctx)
                VpnSwitcher.disable(ctx, cfg.restoreOriginal, cfg.vpnPackage)
                log(ctx, "stale vpn state cleaned on boot")
            }
        } catch (t: Throwable) {
            log(ctx, "cleanup failed: $t")
        }
    }

    // ---------------------------------------------------------------- 网络

    private fun registerNetworkCallback(ctx: Context) {
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

    /**
     * 安装/更新模块后 system_server 里还是旧代码，必须重启才能生效。
     * App 侧点按钮时如果模块已经加载，就由这里直接重启，省掉用户手动操作。
     */
    private fun registerRebootReceiver(ctx: Context): Boolean {
        if (rebootReceiverRegistered) return true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_CLEAR_LOG -> {
                        // 不写日志，否则刚清完又冒出一行
                        ModuleLog.clear(ctx)
                    }
                    ModuleContract.ACTION_TOGGLE_PAUSE -> togglePause(ctx)
                    ModuleContract.ACTION_TEST_CLIENT -> testClient(
                        ctx,
                        intent.getStringExtra(ModuleContract.EXTRA_CLIENT_PACKAGE).orEmpty(),
                        intent.getStringExtra(ModuleContract.EXTRA_CLIENT_MODE).orEmpty(),
                    )
                    ModuleContract.ACTION_FORGET_CLIENT -> forgetClientMode(
                        ctx,
                        intent.getStringExtra(ModuleContract.EXTRA_CLIENT_PACKAGE).orEmpty(),
                    )
                }
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_CLEAR_LOG)
                addAction(ModuleContract.ACTION_TOGGLE_PAUSE)
                addAction(ModuleContract.ACTION_TEST_CLIENT)
                addAction(ModuleContract.ACTION_FORGET_CLIENT)
            }
            // Android 14+（targetSdk 34 起）注册非系统广播的接收器必须显式声明导出标志，
            // 否则 registerReceiver 会直接抛 SecurityException —— 表现就是 App 发什么广播都没人收，
            // 重启按钮点了完全没反应。system_server 的 targetSdk 是平台版本，必然命中这条限制。
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(
                    receiver,
                    filter,
                    REBOOT_PERMISSION,
                    handler,
                    Context.RECEIVER_EXPORTED,
                )
            } else {
                ctx.registerReceiver(receiver, filter, REBOOT_PERMISSION, handler)
            }
            rebootReceiverRegistered = true
            log(ctx, "reboot receiver registered")
            return true
        } catch (t: Throwable) {
            log(ctx, "register reboot receiver failed: $t")
            return false
        }
    }

    private fun isNetworkAllowed(cfg: Config): Boolean =
        EnginePolicy.isNetworkAllowed(lastTransport, cfg.allowWifi, cfg.allowMobile)

    private fun isWifiOnActive(cfg: Config): Boolean {
        val ssid = currentSsid ?: return false
        return lastTransport == 1 && cfg.wifiOn.contains(ssid)
    }

    /** 只描述当前命中的规则，不参与任何控制决策。 */
    private fun currentRule(cfg: Config): Pair<String, String> {
        if (!cfg.enabled || cfg.vpnPackage.isEmpty()) {
            return "disabled" to "模块已关闭或未选择 VPN 客户端"
        }
        if (automationPaused) {
            return "paused" to "快捷磁贴已暂停自动联动"
        }
        if (wifiSuppressed) {
            return "wifiOff" to (currentSsid?.let { "Wi-Fi \"$it\" 命中关闭规则" } ?: "命中关闭 Wi-Fi 规则")
        }
        offAppForeground?.let { pkg ->
            return "offTarget" to "打开 $pkg 时保持断开"
        }
        if (isWifiOnActive(cfg)) {
            return "wifiOn" to "Wi-Fi \"${currentSsid.orEmpty()}\" 命中开启规则"
        }
        if (!isNetworkAllowed(cfg)) {
            return "networkDisabled" to "当前网络类型已关闭自动开关"
        }
        if (userOff) {
            return "userOff" to "已手动关闭，等待重新打开目标应用"
        }
        if (aliveTargets.isNotEmpty()) {
            val sample = aliveTargets.take(3).joinToString("、")
            val more = if (aliveTargets.size > 3) " 等 ${aliveTargets.size} 个" else ""
            return "target" to "目标应用在前台：$sample$more"
        }
        return "idle" to "当前没有目标应用或触发条件"
    }

    private fun publishRuntime(ctx: Context?) {
        val context = ctx ?: return
        try {
            val cfg = ConfigRepo.get(context)
            val (rule, detail) = currentRule(cfg)
            RuntimeDiagnostics.publishRuntime(context, rule, detail, lastTransport)
        } catch (t: Throwable) {
            // 诊断失败不能影响控制逻辑
        }
    }

    /**
     * 读取当前 Wi-Fi 名称并写进 Settings（App 侧没有定位权限，读不到 SSID）。
     * 模块跑在 system_server，可以直接拿 WifiManager 的连接信息。
     */
    private fun refreshSsid(ctx: Context, isWifi: Boolean): String? {
        val ssid = if (!isWifi) {
            null
        } else {
            try {
                val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wm?.connectionInfo?.ssid?.trim('"')
            } catch (t: Throwable) {
                null
            }
        }
        val clean = ssid?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        currentSsid = clean
        try {
            Settings.Secure.putString(ctx.contentResolver, SSID_KEY, clean ?: "")
        } catch (t: Throwable) {
            // 忽略
        }
        return clean
    }

    private fun handleNetworkChanged() {
        val ctx = context() ?: return
        val cfg = ConfigRepo.get(ctx)
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

        if (!isNetworkAllowed(cfg)) {
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

    // ---------------------------------------------------------------- 事件

    private fun handleForeground(pkg: String) {
        val ctx = context() ?: return
        beat(ctx)
        val cfg = ConfigRepo.get(ctx)
        // 上一次前台不是它 => 这次是「重新打开」，而不是应用内部换页 / 弹窗后 resume。
        // 从 VPN 客户端本身退回来也不算：用户多半是刚在里面点了断开，别立刻给他连回去。
        val freshOpen = EnginePolicy.isFreshOpen(lastForeground, pkg, cfg.vpnPackage)
        lastForeground = pkg
        val foregroundKind = EnginePolicy.foregroundKind(pkg, cfg.targets, cfg.offTargets)

        if (!cfg.enabled || cfg.vpnPackage.isEmpty()) {
            offAppForeground = null
            startToken++
            if (VpnSwitcher.isActive(ctx.contentResolver)) {
                aliveTargets.clear()
                turnOff("module disabled")
            }
            return
        }

        if (automationPaused) return

        // 打开「关闭 VPN」列表里的应用：VPN 只要还连着就断开，
        // 并且在它退出之前一直压制自动开启（避免看门狗 5 秒后又把 VPN 拉起来）。
        if (foregroundKind == EnginePolicy.ForegroundKind.OFF_TARGET) {
            val first = offAppForeground != pkg
            offAppForeground = pkg
            offToken++
            // 即使 VPN 当前没连上，也要让 600ms 后准备启动的服务失效
            startToken++
            if (first) log(ctx, "$pkg -> foreground (off trigger)")
            if (VpnSwitcher.isActive(ctx.contentResolver) || hasVpnTransport(ctx)) {
                turnOff("opened $pkg")
            } else {
                stopWatchdog()
                if (first) log(ctx, "vpn already off, keep off while $pkg is open")
            }
            return
        }

        if (foregroundKind != EnginePolicy.ForegroundKind.TARGET) return

        // 当前 Wi-Fi 在「关闭 VPN」列表里：不自动开启
        if (wifiSuppressed) {
            log(ctx, "$pkg -> foreground, but wifi rule keeps vpn off")
            return
        }

        if (offAppForeground != null) {
            offAppForeground = null
            log(ctx, "back to $pkg, auto enable resumed")
        }

        // 目标又回到前台，取消上一次「进程结束」排队的关闭
        offToken++
        aliveTargets.add(pkg)

        if (userOff) {
            if (!EnginePolicy.shouldResumeAfterUserOff(freshOpen)) {
                // 应用内部的页面切换 / 从系统面板返回，不算「重新打开」，继续让 VPN 保持关闭
                return
            }
            userOff = false
            log(ctx, "$pkg reopened -> auto enable resumed")
        }
        // 是「重新打开」就当作一次全新的连接请求，别被上一轮的掉线状态挡住
        if (freshOpen) wasConnected = false

        log(ctx, "$pkg -> foreground")
        if (isNetworkAllowed(cfg)) {
            ensureVpn(ctx, cfg)
        } else {
            log(ctx, "network type disabled, skip enabling")
        }
        startWatchdog()
    }

    private fun handleProcessDied(pkg: String) {
        if (automationPaused) return
        if (pkg == offAppForeground) {
            offAppForeground = null
            log(context(), "$pkg (off trigger) exited")
        }
        val removed = aliveTargets.remove(pkg)
        if (!removed) return
        val ctx = context()
        log(ctx, "$pkg -> process died")
        if (!EnginePolicy.shouldScheduleOffAfterDeath(removed, aliveTargets.size)) return
        // 应用有时只是进程重启（被系统回收后立刻恢复），等一下再决定要不要关，
        // 否则会出现「刚连上就被关、关了又重连」的抖动。
        scheduleOff(ctx, "$pkg died")
    }

    /**
     * 用户从最近任务划掉应用。和进程死亡不同，这时进程可能还活着（例如有前台服务）。
     * 只有在开关开启、目标确实在存活集合里时才接管，避免任务恢复 / 分屏误判。
     */
    private fun handleTaskRemoved(pkg: String) {
        val ctx = context() ?: return
        val cfg = ConfigRepo.get(ctx)
        if (!EnginePolicy.shouldHandleTaskRemoval(
                enabled = cfg.enabled,
                featureEnabled = cfg.disconnectOnTaskRemoved,
                paused = automationPaused,
                offAppForeground = offAppForeground != null,
                wifiSuppressed = wifiSuppressed,
                target = cfg.targets.contains(pkg),
                aliveTarget = aliveTargets.contains(pkg),
            )
        ) return

        aliveTargets.remove(pkg)
        if (lastForeground == pkg) lastForeground = null
        log(ctx, "$pkg -> task removed")
        if (EnginePolicy.shouldScheduleOffAfterDeath(true, aliveTargets.size)) {
            scheduleOff(ctx, "$pkg task removed")
        }
    }

    private fun scheduleOff(ctx: Context?, reason: String) {
        val context = ctx ?: return
        val token = ++offToken
        handler.postDelayed({
            if (token != offToken) return@postDelayed
            val cfg = ConfigRepo.get(context)
            val wifiOnActive = cfg.enabled && isWifiOnActive(cfg)
            if (!EnginePolicy.shouldRunDelayedOff(
                    token,
                    offToken,
                    aliveTargets.isNotEmpty(),
                    wifiOnActive,
                )
            ) return@postDelayed
            turnOff(reason)
        }, OFF_DEBOUNCE_MS)
    }

    /**
     * 停止并稍后重启客户端的 VpnService。
     *
     * 600ms 窗口里可能又打开了「关闭 VPN」应用、切了网络、改了 VPN 包或进入手动关闭状态，
     * 所以执行前必须重新读取配置并逐项确认，不能只看启动序号。
     */
    private fun scheduleDelayedServiceStart(ctx: Context, cfg: Config) {
        if (cfg.vpnPackage.isEmpty()) return
        val scheduledPackage = cfg.vpnPackage
        pendingExternalMode = "service"
        ExternalControl.stopVpnService(ctx, scheduledPackage)
        val token = ++startToken
        handler.postDelayed({
            val current = ConfigRepo.get(ctx)
            val wifiOnActive = current.enabled && isWifiOnActive(current)
            val allowed = EnginePolicy.shouldRunDelayedStart(
                token = token,
                currentToken = startToken,
                scheduledVpnPackage = scheduledPackage,
                enabled = current.enabled,
                currentVpnPackage = current.vpnPackage,
                offAppForeground = offAppForeground != null,
                userOff = userOff,
                wifiSuppressed = wifiSuppressed,
                networkAllowed = isNetworkAllowed(current),
                hasAliveTarget = aliveTargets.isNotEmpty(),
                wifiOnActive = wifiOnActive,
            )
            if (!allowed) {
                log(ctx, "delayed service start cancelled (state changed)")
                return@postDelayed
            }
            ExternalControl.startVpnService(ctx, scheduledPackage)
        }, 600L)
    }

    // ---------------------------------------------------------------- VPN 控制

    private fun ensureVpn(ctx: Context, cfg: Config) {
        if (automationPaused) return
        // 任何新的授权开启意图都让此前排队的关闭验证链失效
        offGeneration++
        val settingPointsToUs = VpnSwitcher.currentAlwaysOn(ctx) == cfg.vpnPackage
        val active = VpnSwitcher.isActive(ctx.contentResolver)
        val connected = hasVpnTransport(ctx)

        if (connected) {
            // 已经连上了，说明用户「保持关闭」的意图已经结束（例如他自己又手动连上了）
            userOff = false
            // 设置仍指向我们，说明这轮是模块接管的，看门狗可以负责关掉它
            if (active || settingPointsToUs) autoSession = true
            if (failureStreak > 0) log(ctx, "vpn connected (after $failureStreak failed attempts)")
            // 只有「确实靠外部手段连上」才记录，always-on 能连上的客户端不该被误判
            pendingExternalMode?.let { mode ->
                ExternalControl.remember(ctx, cfg.vpnPackage, mode)
                log(ctx, "external mode '$mode' works for ${cfg.vpnPackage}, remembered")
            }
            pendingExternalMode = null
            if (!connectedNotified) {
                connectedNotified = true
                toast(ctx, "VPN 已开启", cfg.toastEnabled)
            }
            failureStreak = 0
            failureLogged = false
            tileAttempts = 0
            reapplyCount = 0
            externalAttempts = 0
            wasConnected = true
            return
        }

        // 从「已连接」掉下来：可能是用户手动关了（磁贴 / 客户端里断开），也可能是系统掐断。
        // 按你的要求，这时不自动重连——保持断开，等他重新打开目标应用时再连。
        if (wasConnected) {
            wasConnected = false
            externalAttempts = 0
            reapplyCount = 0
            connectedNotified = false
            autoSession = false
            userOff = true
            startToken++
            stopWatchdog()
            log(ctx, "vpn dropped -> stay off until a target app is reopened")
            return
        }

        // 已经被用户手动关掉，等下次打开目标应用
        if (userOff) return

        // 从这里往下模块真的要动手了，记下这轮是模块拉起的
        autoSession = true

        val now = SystemClock.elapsedRealtime()

        val externalMode = ExternalControl.modeOf(ctx, cfg.vpnPackage)

        // 已经确认这个客户端不响应 always-on：不再写常驻 VPN 设置（避免 unbind 干扰）
        if (externalMode != null) {
            if (cfg.forceStopVpn) Packages.unstop(cfg.vpnPackage)

            if (!EnginePolicy.cooldownReady(now, lastApplyAt, EXTERNAL_ONLY_GUARD_MS)) return
            if (externalAttempts >= MAX_EXTERNAL_ATTEMPTS &&
                !EnginePolicy.cooldownReady(now, lastApplyAt, REAPPLY_SLOW_MS)
            ) return

            lastApplyAt = now
            externalAttempts++

            if (externalMode == "activity" && cfg.allowVpnUi &&
                ExternalControl.start(ctx, cfg.vpnPackage)
            ) {
                pendingExternalMode = "activity"
                log(ctx, "external-only: start via external control (remembered)")
            } else if (externalMode == "service" || externalAttempts <= 2) {
                log(ctx, "external-only: restart VPN service without UI (attempt $externalAttempts)")
                scheduleDelayedServiceStart(ctx, cfg)
            } else if (cfg.allowVpnUi && ExternalControl.start(ctx, cfg.vpnPackage)) {
                pendingExternalMode = "activity"
                log(ctx, "external-only: start via client UI (allowed in settings)")
            } else {
                log(
                    ctx,
                    "external-only: 无法在后台拉起 ${cfg.vpnPackage}，" +
                        "请在客户端里打开「自动连接」，或在「高级」里允许短暂打开客户端界面"
                )
            }
            return
        }

        val interval = EnginePolicy.reapplyInterval(reapplyCount, MAX_FAST_REAPPLY, REAPPLY_GUARD_MS, REAPPLY_SLOW_MS)
        if (!EnginePolicy.cooldownReady(now, lastApplyAt, interval)) return

        if (active && settingPointsToUs) {
            // 上一次关闭时如果强制停止过客户端，它会被系统标记成 stopped，
            // 之后 always-on 再怎么设置也不会 bind，先把这个标记清掉。
            if (cfg.forceStopVpn && reapplyCount == 0) {
                if (Packages.unstop(cfg.vpnPackage)) {
                    log(ctx, "cleared force-stop state for ${cfg.vpnPackage}")
                }
            }
            // 设置生效但隧道起不来：先请客户端自己启动（Clash 系只有这条能通）
            if (tryExternalStart(ctx, cfg, now)) {
                lastApplyAt = now
                return
            }
            if (reapplyCount >= TILE_AFTER_REAPPLY && tryQuickTile(ctx, cfg, now)) {
                lastApplyAt = now
                return
            }
            log(ctx, "vpn is down while setting points to us -> reapply")
            VpnSwitcher.reapply(ctx, cfg.vpnPackage, cfg.lockdown)
            lastApplyAt = now
            reapplyCount++
            noteFailure(ctx, cfg)
            return
        }

        // 全新接管前同样清一次 stopped 标记，否则可能写了设置也拉不起来
        if (cfg.forceStopVpn) Packages.unstop(cfg.vpnPackage)

        val ok = VpnSwitcher.enable(ctx, cfg.vpnPackage, cfg.lockdown)
        lastApplyAt = now
        reapplyCount = 0
        tileAttempts = 0
        externalAttempts = 0
        connectedNotified = false
        log(ctx, "apply vpn ok=$ok (active=$active, pointsToUs=$settingPointsToUs, connected=$connected)")
        // 之前确认过这个客户端不响应 always-on → 直接按记住的方式启动
        if (ok) {
            when (ExternalControl.modeOf(ctx, cfg.vpnPackage)) {
                "activity" -> if (ExternalControl.start(ctx, cfg.vpnPackage)) {
                    pendingExternalMode = "activity"
                    externalAttempts++
                    lastExternalAt = now
                    log(ctx, "known client: start via external control")
                }
                "service" -> {
                    pendingExternalMode = "service"
                    externalAttempts++
                    lastExternalAt = now
                    log(ctx, "known client: start VPN service (no ui)")
                    scheduleDelayedServiceStart(ctx, cfg)
                }
            }
        }
        if (!ok) noteFailure(ctx, cfg)
    }

    /**
     * 让客户端自己启动 VPN（Clash 系导出的 `ACTION_START_CLASH` 入口）。
     * @return 是否已经发起（发起了就不再重复写 always-on 设置）
     */
    private fun tryExternalStart(ctx: Context, cfg: Config, now: Long): Boolean {
        if (cfg.vpnPackage.isEmpty()) return false
        if (externalAttempts >= MAX_EXTERNAL_ATTEMPTS) return false
        if (!EnginePolicy.cooldownReady(now, lastExternalAt, EXTERNAL_COOLDOWN_MS)) return false
        // 先给 always-on 一次机会，别一上来就拉 Activity
        if (reapplyCount < 1) return false

        if (!ExternalControl.start(ctx, cfg.vpnPackage)) {
            externalAttempts = MAX_EXTERNAL_ATTEMPTS
            return false
        }
        externalAttempts++
        lastExternalAt = now
        pendingExternalMode = "activity"
        log(ctx, "always-on did not connect -> external control START")
        return true
    }

    /**
     * always-on 设置生效了但隧道起不来时，改为点 VPN 客户端自己的快捷磁贴。
     * @return 是否已经点了磁贴（点了就不再重复写设置）
     */
    private fun tryQuickTile(ctx: Context, cfg: Config, now: Long): Boolean {
        if (tileAttempts >= MAX_TILE_ATTEMPTS) return false
        if (!EnginePolicy.cooldownReady(now, lastTileClickAt, TILE_COOLDOWN_MS)) return false

        val tile = QuickTiles.find(ctx, cfg.vpnPackage)
        if (tile == null) {
            if (tileAttempts == 0) {
                log(ctx, "${cfg.vpnPackage} has no QS tile, cannot auto start it")
            }
            tileAttempts = MAX_TILE_ATTEMPTS
            return false
        }

        lastTileClickAt = now
        tileAttempts++
        log(ctx, "always-on did not connect, click QS tile (attempt $tileAttempts)")
        return QuickTiles.click(ctx, tile)
    }

    private fun noteFailure(ctx: Context, cfg: Config) {
        failureStreak++
        RuntimeDiagnostics.publishFailure(ctx, "enable", cfg.vpnPackage, failureStreak)
        if (!failureLogged && failureStreak >= FAILURE_HINT_AT) {
            failureLogged = true
            log(
                ctx,
                "已连续 $failureStreak 次没能建立 VPN：${cfg.vpnPackage} 不响应系统常驻 VPN，" +
                    "也点不了它的快捷磁贴。请在客户端里打开「自动连接」，或换用 v2rayNG / Surfboard / WireGuard"
            )
        }
    }

    private fun turnOff(reason: String) {
        val ctx = context() ?: return
        beat(ctx)
        val cfg = ConfigRepo.get(ctx)
        val generation = ++offGeneration
        // 取消尚未执行的关闭任务，避免同一次关闭派生多条验证链
        offToken++
        // 手动连上的 VPN 没有接管标记，也要提示「已关闭」
        val wasActive = VpnSwitcher.isActive(ctx.contentResolver) || hasVpnTransport(ctx)
        VpnSwitcher.disable(ctx, cfg.restoreOriginal, cfg.vpnPackage)
        // 外部控制模式没有接管标记，但历史遗留的 always-on 仍会不断把客户端 bind 起来
        VpnSwitcher.clearIfPointsTo(ctx, cfg.vpnPackage)
        reapplyCount = 0
        tileAttempts = 0
        externalAttempts = 0
        connectedNotified = false
        wasConnected = false
        stopWatchdog()
        startToken++
        pendingExternalMode = null
        autoSession = false
        userOff = false
        log(ctx, "vpn OFF ($reason)")
        if (wasActive) toast(ctx, "VPN 已关闭", cfg.toastEnabled)

        // 清掉 always-on 未必能断开「用户手动连上的」VPN，稍等一下确认
        handler.postDelayed({ verifyOff(ctx, cfg.vpnPackage, generation) }, 1_200L)
        publishRuntime(ctx)
    }

    /** 目标应用回到前台（压制解除）时不要动它；只是因为「打开即关闭」而断开时，继续关干净 */
    private fun canTouchVpn(ctx: Context): Boolean =
        !VpnSwitcher.isActive(ctx.contentResolver) &&
            !(aliveTargets.isNotEmpty() && offAppForeground == null)

    /** 关闭链仍然有效：代次没变、VPN 包没换、当前仍允许操作 VPN。 */
    private fun closeChainValid(ctx: Context, expectedPackage: String, generation: Int): Boolean {
        val current = ConfigRepo.get(ctx)
        return EnginePolicy.isCloseChainValid(
            generation = generation,
            currentGeneration = offGeneration,
            expectedVpnPackage = expectedPackage,
            currentVpnPackage = current.vpnPackage,
            canTouchVpn = canTouchVpn(ctx),
        )
    }

    /**
     * 关闭后校验：如果隧道还在（典型场景：用户在 VPN 客户端里手动连上的，
     * 清 always-on 设置并不会断开它），就依次尝试「停止请求 → 快捷磁贴 → 强制停止」。
     */
    private fun verifyOff(ctx: Context, expectedPackage: String, generation: Int) {
        if (!closeChainValid(ctx, expectedPackage, generation)) return
        if (!hasVpnTransport(ctx)) {
            log(ctx, "verify: vpn is down")
            return
        }

        // 1) 无 UI：给客户端发它自己的停止广播（Clash 系支持）。
        //    已经确认是 service 模式的客户端（v2rayNG 等）根本没有这个接收器，
        //    发了也是白发，直接跳过，别白等一轮。
        val clashLike = expectedPackage.isNotEmpty() &&
            ExternalControl.modeOf(ctx, expectedPackage) != "service"
        if (clashLike && ExternalControl.requestStop(ctx, expectedPackage)) {
            // 广播只是「请求」，多数客户端会忽略它：轮询 2 秒，隧道一断就立刻收工
            awaitDown(ctx, expectedPackage, generation, 2_000L) { down ->
                if (down) {
                    log(ctx, "verify: vpn is down (client stopped)")
                } else {
                    verifyOffStep(ctx, expectedPackage, generation, true)
                }
            }
            return
        }
        verifyOffStep(ctx, expectedPackage, generation, true)
    }

    /** 每 400ms 看一次隧道是否断开，最多等 [deadlineMs]，避免固定 sleep 白等 */
    private fun awaitDown(
        ctx: Context,
        expectedPackage: String,
        generation: Int,
        deadlineMs: Long,
        onDone: (Boolean) -> Unit,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val poll = object : Runnable {
            override fun run() {
                if (!closeChainValid(ctx, expectedPackage, generation)) return
                if (!hasVpnTransport(ctx)) {
                    onDone(true)
                    return
                }
                if (SystemClock.elapsedRealtime() - startedAt >= deadlineMs) {
                    onDone(false)
                    return
                }
                handler.postDelayed(this, 400L)
            }
        }
        handler.postDelayed(poll, 400L)
    }

    private fun verifyOffStep(
        ctx: Context,
        expectedPackage: String,
        generation: Int,
        allowActivity: Boolean,
    ) {
        if (!closeChainValid(ctx, expectedPackage, generation)) return
        val cfg = ConfigRepo.get(ctx)
        if (!hasVpnTransport(ctx)) {
            log(ctx, "verify: vpn is down")
            return
        }

        // 2) 客户端导出的 STOP_CLASH 入口
        if (allowActivity && cfg.allowVpnUi && expectedPackage.isNotEmpty() &&
            ExternalControl.stop(ctx, expectedPackage)
        ) {
            awaitDown(ctx, expectedPackage, generation, 2_500L) { down ->
                if (down) {
                    log(ctx, "verify: vpn is down (client stop activity)")
                } else {
                    verifyOffStep(ctx, expectedPackage, generation, false)
                }
            }
            return
        }

        // 3) 快捷磁贴
        val tile = if (expectedPackage.isNotEmpty()) QuickTiles.find(ctx, expectedPackage) else null
        if (tile != null && QuickTiles.click(ctx, tile)) {
            awaitDown(ctx, expectedPackage, generation, 2_000L) { down ->
                if (down) {
                    log(ctx, "verify: vpn is down (qs tile)")
                } else {
                    finishVerifyOff(ctx, expectedPackage, generation, 0)
                }
            }
            return
        }
        finishVerifyOff(ctx, expectedPackage, generation, 0)
    }

    /**
     * 强制停止客户端。
     *
     * 有些设备上进程被杀了，VPN 网络还要几秒才真正消失；以前只查一次就下结论，
     * 日志里会出现「still up after force stop」但其实马上就断了。现在分批复查，
     * 仍然连着就再停一次，最多三次，最后如实报告。
     */
    private fun finishVerifyOff(
        ctx: Context,
        expectedPackage: String,
        generation: Int,
        attempt: Int,
    ) {
        if (!closeChainValid(ctx, expectedPackage, generation)) return
        val cfg = ConfigRepo.get(ctx)
        if (!hasVpnTransport(ctx)) {
            log(
                ctx,
                if (attempt == 0) "verify: vpn is down" else "verify: disconnected after force stop"
            )
            return
        }
        if (!cfg.forceStopVpn || expectedPackage.isEmpty()) {
            log(ctx, "verify: vpn still up, force stop disabled")
            return
        }
        if (attempt >= MAX_FORCE_STOP_ATTEMPTS) {
            log(ctx, "verify: still up after $attempt force stops, giving up")
            RuntimeDiagnostics.publishFailure(ctx, "stop", expectedPackage, attempt)
            return
        }

        // 少数 ROM / 客户端会把 always-on 又写回来，动手前再清一次
        VpnSwitcher.clearIfPointsTo(ctx, expectedPackage)
        log(
            ctx,
            "verify: vpn still up -> force stop $expectedPackage " +
                "(${attempt + 1}/$MAX_FORCE_STOP_ATTEMPTS)"
        )
        val ok = forceStop(ctx, expectedPackage)
        if (!ok) return
        // 这里**不要**立刻 unstop：stopped 标记会阻止系统再次 bind 它的 VpnService，
        // 正是我们想要的「关掉就真的关掉」。下次需要启动时 ensureVpn 会再清除。
        Packages.forget(expectedPackage)
        log(ctx, "force stopped, stopped-state kept (will clear before next start)")

        handler.postDelayed({
            if (!closeChainValid(ctx, expectedPackage, generation)) return@postDelayed
            if (!hasVpnTransport(ctx)) {
                log(ctx, "verify: disconnected after force stop")
            } else {
                // alive=true 说明进程又被谁拉起来了（常驻服务 / 系统 bind），再停一次
                log(
                    ctx,
                    "verify: still up after force stop, app alive=" +
                        "${isProcessAlive(ctx, expectedPackage)}"
                )
                finishVerifyOff(ctx, expectedPackage, generation, attempt + 1)
            }
        }, FORCE_STOP_RECHECK_MS * (attempt + 1))
    }

    private fun forceStop(ctx: Context, pkg: String): Boolean = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.javaClass.getMethod("forceStopPackage", String::class.java).invoke(am, pkg)
        true
    } catch (t: Throwable) {
        log(ctx, "force stop failed: $t")
        RuntimeDiagnostics.publishFailure(ctx, "forceStop", pkg, 0)
        false
    }

    private fun hasVpnTransport(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (t: Throwable) {
        true
    }

    private fun toast(ctx: Context, message: String, enabled: Boolean) {
        if (!enabled) return
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] toast failed: $t")
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] toast post failed: $t")
        }
    }

    // ---------------------------------------------------------------- 对账（看门狗）

    /**
     * 每 5 秒对账一次：
     *  - 目标应用进程没了 → 关 VPN
     *  - 目标应用还活着   → 保证 VPN 是开着的（手动关掉、被系统断开都会被拉回来）
     */
    private val watchdog = object : Runnable {
        override fun run() {
            val ctx = context() ?: return
            val cfg = ConfigRepo.get(ctx)

            if (!cfg.enabled || cfg.vpnPackage.isEmpty()) {
                if (VpnSwitcher.isActive(ctx.contentResolver)) turnOff("module disabled")
                aliveTargets.clear()
                watchdogRunning = false
                return
            }

            if (automationPaused) {
                watchdogRunning = false
                return
            }

            if (wifiSuppressed) {
                if (VpnSwitcher.isActive(ctx.contentResolver)) turnOff("wifi off")
                watchdogRunning = false
                return
            }

            if (aliveTargets.isEmpty()) {
                // 兜底：目标进程都没了，VPN 却还开着（例如进程死亡事件被系统吞了），
                // 只要这轮是模块拉起的、且没有「打开即关闭」的应用在压制，就直接关掉。
                if (autoSession && offAppForeground == null &&
                    (VpnSwitcher.isActive(ctx.contentResolver) || hasVpnTransport(ctx))
                ) {
                    log(ctx, "reconcile: no target alive but vpn still on -> schedule off")
                    scheduleOff(ctx, "no target alive")
                }
                watchdogRunning = false
                return
            }

            // 「打开即关闭」的应用还活着就不再拉起 VPN；它退出后恢复自动开启
            // 用户手动关掉了 VPN：他要求保持关闭，看门狗不再轮询
            if (userOff) {
                watchdogRunning = false
                return
            }

            val suppressor = offAppForeground
            if (suppressor != null) {
                if (isProcessAlive(ctx, suppressor)) {
                    watchdogRunning = false
                    return
                }
                offAppForeground = null
                log(ctx, "$suppressor is gone, auto vpn available again")
            }

            if (!isNetworkAllowed(cfg)) {
                if (VpnSwitcher.isActive(ctx.contentResolver)) {
                    log(ctx, "reconcile: network type disabled -> off")
                    turnOff("network type disabled")
                    // turnOff 已经停掉并移除了本看门狗，不能再把自己排回去
                    return
                }
                handler.postDelayed(this, RECONCILE_INTERVAL_MS)
                return
            }

            if (!EnginePolicy.shouldEnsureForProcessState(anyTargetAlive(ctx))) {
                log(ctx, "reconcile: target process gone -> off")
                aliveTargets.clear()
                scheduleOff(ctx, "watchdog")
                watchdogRunning = false
            } else {
                if (!VpnSwitcher.isActive(ctx.contentResolver) &&
                    !ExternalControl.isKnown(ctx, cfg.vpnPackage)
                ) {
                    log(ctx, "reconcile: vpn is off but target alive -> enable")
                }
                ensureVpn(ctx, cfg)
                handler.postDelayed(this, RECONCILE_INTERVAL_MS)
            }
        }
    }

    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        handler.postDelayed(watchdog, RECONCILE_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        watchdogRunning = false
        handler.removeCallbacks(watchdog)
    }

    private fun isProcessAlive(ctx: Context, pkg: String): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val processes = try {
            am.runningAppProcesses
        } catch (t: Throwable) {
            null
        } ?: return false
        return processes.any { it.processName == pkg || it.processName.startsWith("$pkg:") }
    }

    /**
     * @return true 还在运行；false 已经结束；null 无法判断（列表被系统裁剪时）
     */
    private fun anyTargetAlive(ctx: Context): Boolean? {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val processes = try {
            am.runningAppProcesses
        } catch (t: Throwable) {
            null
        } ?: return null

        if (processes.none { it.uid != Process.myUid() }) return null

        return aliveTargets.any { pkg ->
            processes.any { it.processName == pkg || it.processName.startsWith("$pkg:") }
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun context(): Context? {
        appContext?.let { return it }
        val resolved = try {
            val cls = Class.forName("android.app.ActivityThread")
            val current = cls.getMethod("currentActivityThread").invoke(null)
            cls.getMethod("getSystemContext").invoke(current) as? Context
        } catch (t: Throwable) {
            null
        }
        if (resolved != null) {
            appContext = resolved
            XposedBridge.log("[$TAG] system context resolved lazily")
        }
        return resolved
    }
}
