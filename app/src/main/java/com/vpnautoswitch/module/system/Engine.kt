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
import com.vpnautoswitch.module.BuildConfig
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
    /** 跟随 applicationId，和清单里的 signature 权限保持一致 */
    private val REBOOT_PERMISSION = "${BuildConfig.APPLICATION_ID}.permission.REBOOT"

    private const val RECONCILE_INTERVAL_MS = 5_000L

    /**
     * 已经发起连接、隧道还没起来时的对账间隔。
     *
     * 原来固定 5 秒一拍：第一次尝试没连上，用户就要多等 5 秒才轮到第二次。
     * 连接窗口里改成 0.9 秒一拍，连上或拍数用尽后自动退回 [RECONCILE_INTERVAL_MS]。
     */
    private const val RECONCILE_CONNECTING_MS = 900L
    private const val RECONCILE_CONNECTING_TICKS = 8

    /** 同一次启动会连着走几次 startActivity，同一个包在这个窗口内只提前探一次 */
    private const val PREWARM_DEDUPE_MS = 1_200L

    /** 首次重写常驻设置前的等待：给客户端一个建隧道的时间，又不至于等满 12 秒 */
    private const val REAPPLY_FIRST_MS = 4_000L

    /** 关后守卫：窗口总长与最多重关次数 */
    private const val GUARD_WINDOW_MS = 8_000L
    private const val MAX_GUARD_ATTEMPTS = 2

    private var offGuardToken = 0

    /** 独立心跳：不依赖目标应用或看门狗，App 用它判断模块是否仍在运行 */
    private const val HEARTBEAT_ACTIVE_MS = 30_000L

    /** 没有目标应用、也没有模块自己的会话时降频：少写一次就少唤醒一次设置观察者 */
    private const val HEARTBEAT_IDLE_MS = 60_000L

    /** 版本号兜底重写的间隔（按心跳次数算）：设置被外部清掉时还能自己补回来 */
    private const val VERSION_REWRITE_TICKS = 20

    /**
     * 心跳的最短写入间隔。
     *
     * 应用内换页、弹窗、从系统面板返回都会各来一次 activityResumed，
     * 每次写一遍心跳纯属浪费：SettingsProvider 每写一次就唤醒所有观察者。
     * App 的判活阈值是 90 秒，3 秒节流完全不影响判断。
     */
    private const val BEAT_MIN_INTERVAL_MS = 3_000L

    /** 运行时快照内容没变时，最多每 5 秒重写一次 */
    private const val RUNTIME_REWRITE_MS = 5_000L

    /**
     * 重写 always-on 设置会先清空再写回，等于让系统 unbind + rebind 一次 VpnService。
     * 客户端（尤其 Clash 系）建立隧道要好几秒，写得太频繁会一直打断它，
     * 表现就是「connected 永远 false，偶尔才成功」——所以这里给足冷却时间。
     */
    private const val REAPPLY_GUARD_MS = 12_000L
    private const val REAPPLY_SLOW_MS = 60_000L
    private const val MAX_FAST_REAPPLY = 4
    private const val TILE_AFTER_REAPPLY = 1
    private const val TILE_COOLDOWN_MS = 30_000L
    private const val MAX_TILE_ATTEMPTS = 3

    /** 客户端不响应 always-on 时，改用它的外部控制入口（START_CLASH / STOP_CLASH） */
    private const val EXTERNAL_COOLDOWN_MS = 20_000L
    private const val MAX_EXTERNAL_ATTEMPTS = 3

    /** 已经确认要用外部控制的客户端，重试间隔可以短一些 */
    private const val EXTERNAL_ONLY_GUARD_MS = 6_000L

    /**
     * 关闭前的去抖。
     *
     * 划掉任务、看门狗确认没有目标存活时，用户意图已经很明确，0.5 秒就够；
     * 进程死亡有可能是系统回收后立刻重启，多等一会儿免得来回抖。
     */
    private const val OFF_FAST_MS = 500L
    private const val OFF_SLOW_MS = 1_500L

    /**
     * 刚连上就掉的补偿重试。
     *
     * 有的客户端先 establish 再因为「这次没连成」断开，隧道只活一秒左右。
     * 以前这被当成用户手动关闭，直接挂上 userOff，得退出应用再打开才会重连。
     * 现在自动补一次，最多 [MAX_FLAP_RETRIES] 次，且只等 [FLAP_RETRY_MS]。
     */
    private const val FLAP_RETRY_MS = 250L
    private const val MAX_FLAP_RETRIES = 2

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

    /** 心跳计数，用来决定哪一次心跳顺手重写一次版本号 */
    private var versionTicks = 0

    private val heartbeat = object : Runnable {
        override fun run() {
            val ctx = context()
            if (ctx != null) {
                beat(ctx)
                publishRuntime(ctx)
            }
            handler.postDelayed(this, heartbeatDelay())
        }
    }

    /**
     * 空闲时把心跳放慢一档。15 秒一次、每次写两个键，一天下来近万次设置写入，
     * 每次都会让 SettingsProvider 的观察者（系统设置、其他模块）跟着醒一次。
     */
    private fun heartbeatDelay(): Long {
        val idle = aliveTargets.isEmpty() && offAppForeground == null && !autoSession
        return if (idle) HEARTBEAT_IDLE_MS else HEARTBEAT_ACTIVE_MS
    }

    @Volatile
    private var appContext: Context? = null

    private val aliveTargets = HashSet<String>()

    /**
     * 最近一个真正回到前台的目标应用。
     *
     * [lastForeground] 会被任何应用（含桌面）覆盖，划掉任务的回调又可能晚于桌面 resume，
     * 用它判断「用户刚收起的是不是正在用的那个」会看走眼，所以单独记一份。
     */
    private var lastForegroundTarget: String? = null

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

    /** 连接窗口里已经快轮询了几拍，连上就清零 */
    private var connectingTicks = 0

    /** 上一次提前连接的包与时间，用来给同一次启动去重 */
    private var lastPrewarmPkg: String? = null
    private var lastPrewarmAt = 0L
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

    /** 本轮第一次看到「已连接」的时刻，用来分辨「刚连上就掉」和「用了一会儿才掉」 */
    private var connectedAt = 0L

    /** 本轮开始动手连接的时刻；连上后算出「打开应用多久有网」写进诊断 */
    private var connectStartedAt = 0L

    /** 本轮开始关闭的时刻；确认隧道真的没了就算出「关闭花了多久」 */
    private var offStartedAt = 0L

    /** 刚连上就掉的补偿重试次数；稳定连上后清零 */
    private var flapRetries = 0

    private var offToken = 0
    private var startToken = 0

    /**
     * 上一次「断开」发生的时刻（模块主动关，或检测到用户手动断开）。
     *
     * 断开后一两秒里 VPN 常常又被客户端自己重连、或被「始终开启的 VPN」拉回来；
     * 那一下不是用户打开的，不能再提示「VPN 已开启」，否则他刚点了关闭却看到已开启。
     */
    private var lastOffAt = 0L

    /** 心跳与运行时快照的写入节流，见 [BEAT_MIN_INTERVAL_MS] / [RUNTIME_REWRITE_MS] */
    private var lastBeatAt = 0L
    private var lastRuntimeAt = 0L
    private var lastRuntimeKey: String? = null

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

    /**
     * 应用刚开始启动（Activity 还没 resumed）时的探测。
     *
     * activityResumed 要等应用画出第一帧才回调，那时才开始连 VPN，
     * 用户看到的就是「应用已经打开了，网还没通」。这里在 startActivity 阶段先看一眼：
     * 是目标应用、VPN 又没连上，就提前把常驻设置写上，让系统在应用启动的同时 bind VpnService。
     */
    fun onAppLaunching(packageName: String) {
        if (packageName.isEmpty()) return
        handler.post { prewarm(packageName) }
    }

    private fun prewarm(pkg: String) {
        val ctx = context() ?: return
        val cfg = ConfigRepo.get(ctx)
        if (!cfg.enabled || cfg.vpnPackage.isEmpty()) return
        if (pkg == cfg.vpnPackage || pkg == BuildConfig.APPLICATION_ID) return
        if (!cfg.targets.contains(pkg)) return
        if (automationPaused || userOff || wifiSuppressed || offAppForeground != null) return
        if (!isNetworkAllowed(cfg)) return
        // 已经连着就不用插手，resumed 之后走正常流程
        if (hasVpnTransport(ctx)) return

        // 同一次启动会连着走好几次 startActivity，同一个包只提前探一次
        val now = SystemClock.elapsedRealtime()
        if (pkg == lastPrewarmPkg && now - lastPrewarmAt < PREWARM_DEDUPE_MS) return
        lastPrewarmPkg = pkg
        lastPrewarmAt = now

        log(ctx, "$pkg launching -> prewarm")
        ensureVpn(ctx, cfg)
        // 点了又立刻退出时没人收尾，交给看门狗发现「没有目标存活」后再关
        startWatchdog()
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
        // 应用内换页会连续触发前台回调，每页都写一次心跳没有意义
        val now = SystemClock.elapsedRealtime()
        if (now - lastBeatAt < BEAT_MIN_INTERVAL_MS) return true
        lastBeatAt = now
        return try {
            Settings.Secure.putLong(ctx.contentResolver, HEARTBEAT_KEY, System.currentTimeMillis())
            // 版本号只在变化时写。原来每次心跳都重复写同一个值，
            // 对 SettingsProvider 来说仍是一次实打实的写入 + 通知。
            if (versionTicks == 0) {
                Settings.Secure.putInt(
                    ctx.contentResolver,
                    MODULE_VERSION_KEY,
                    com.vpnautoswitch.module.BuildConfig.VERSION_CODE
                )
            }
            versionTicks = (versionTicks + 1) % VERSION_REWRITE_TICKS
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
            // 快照里带时间戳，写一次内容就「变」一次；换成自己比对关键字段，
            // 没人看诊断时就不必每次前台回调都写一遍设置
            val key = "$rule|$detail|$lastTransport|${lastForeground.orEmpty()}"
            val now = SystemClock.elapsedRealtime()
            if (key == lastRuntimeKey && now - lastRuntimeAt < RUNTIME_REWRITE_MS) return
            lastRuntimeKey = key
            lastRuntimeAt = now
            RuntimeDiagnostics.publishRuntime(context, rule, detail, lastTransport, lastForeground)
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
        lastForegroundTarget = pkg

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
        val wasForeground = lastForegroundTarget == pkg
        // 别的目标还停在前台时不能收会话，否则正在用的那个会被一起断掉
        val anotherInFront = EnginePolicy.anotherTargetInForeground(lastForeground, pkg, aliveTargets)
        val removed = aliveTargets.remove(pkg)
        if (!removed) return
        val ctx = context()
        log(ctx, "$pkg -> process died")
        if (lastForeground == pkg) lastForeground = null
        if (wasForeground) lastForegroundTarget = null
        // 死的是刚才在用的那个，或者开着「划掉任意目标即断开」：其它目标只是挂在后台的残留进程，
        // 算这次会话结束。如果它只是被系统回收后重启，下面的 1.5 秒防抖里会重新回到前台并取消这次关闭。
        val dismissAny = ctx != null && ConfigRepo.get(ctx).disconnectOnAnyDismiss
        if (EnginePolicy.shouldEndSessionOnLeave(
                removedWasForeground = wasForeground,
                remainingTargets = aliveTargets.size,
                dismissAny = dismissAny,
                anotherTargetInForeground = anotherInFront,
            )
        ) {
            aliveTargets.clear()
            log(
                ctx,
                if (wasForeground) "other targets only in background -> end session"
                else "a background target left -> end session",
            )
        }
        if (!EnginePolicy.shouldScheduleOffAfterDeath(true, aliveTargets.size)) return
        // 应用有时只是进程重启（被系统回收后立刻恢复），等一下再决定要不要关，
        // 否则会出现「刚连上就被关、关了又重连」的抖动。
        scheduleOff(ctx, "$pkg died", OFF_SLOW_MS)
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

        val wasForeground = lastForegroundTarget == pkg
        // 另一目标正停在前台时保留会话，避免把用户还在用的那个一起断掉
        val anotherInFront = EnginePolicy.anotherTargetInForeground(lastForeground, pkg, aliveTargets)
        aliveTargets.remove(pkg)
        if (lastForeground == pkg) lastForeground = null
        if (wasForeground) lastForegroundTarget = null
        log(ctx, "$pkg -> task removed")
        // 划掉的正好是刚在用的那个，或者开着「划掉任意目标即断开」：其它目标只是挂在后台的进程，
        // 算这次会话结束。否则它们的存活会让 VPN 一直被拽住，划掉当前应用也不断开。
        if (EnginePolicy.shouldEndSessionOnLeave(
                removedWasForeground = wasForeground,
                remainingTargets = aliveTargets.size,
                dismissAny = cfg.disconnectOnAnyDismiss,
                anotherTargetInForeground = anotherInFront,
            )
        ) {
            aliveTargets.clear()
            log(
                ctx,
                if (wasForeground) "other targets only in background -> end session"
                else "a background target was dismissed -> end session",
            )
        }
        if (EnginePolicy.shouldScheduleOffAfterDeath(true, aliveTargets.size)) {
            scheduleOff(ctx, "$pkg task removed")
        }
    }

    private fun scheduleOff(ctx: Context?, reason: String, debounceMs: Long = OFF_FAST_MS) {
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
        }, debounceMs)
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
            // 原来等 600ms 是为了让 stopService 先落地；250ms 足够，用户感知上是半秒差距
        }, 250L)
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
            val nowUp = SystemClock.elapsedRealtime()
            if (connectedAt == 0L) connectedAt = nowUp
            // 连稳了才清掉抖动重试的计数：否则「连上—掉—连上—掉」会一直重连下去
            if (nowUp - connectedAt >= EnginePolicy.STARTUP_FLAP_MS) flapRetries = 0
            // 从「开始动手」到「看到隧道」，就是用户感知的「打开应用多久有网」。
            // 刚连上就掉的抖动这里也会记一次，随后的重连会覆盖成真实值。
            if (connectStartedAt != 0L) {
                val cost = nowUp - connectStartedAt
                connectStartedAt = 0L
                RuntimeDiagnostics.publishTiming(ctx, "on", cost)
            }
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
                // 刚断开的一两秒内又连上：多半是客户端自动重连，或系统按常驻设置把它拉了回来。
                // 用户刚点的是「关闭」，这时提示「已开启」与他的操作正好相反，宁可不说。
                if (!EnginePolicy.isFreshOff(SystemClock.elapsedRealtime() - lastOffAt)) {
                    toast(ctx, "VPN 已开启", cfg.toastEnabled)
                } else {
                    log(ctx, "vpn came back right after off -> skip the 'enabled' toast")
                }
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
            val now = SystemClock.elapsedRealtime()
            val connectedFor = if (connectedAt == 0L) Long.MAX_VALUE else now - connectedAt
            wasConnected = false
            connectedAt = 0L

            // 刚连上就掉：先怀疑「这次没连成」，不要当成用户手动断开。
            // 否则 userOff 一挂上，用户得退出应用再打开才会重连（日志里干等几十秒就是这个）。
            if (autoSession && EnginePolicy.isStartupFlap(
                    connectedForMs = connectedFor,
                    retries = flapRetries,
                    maxRetries = MAX_FLAP_RETRIES,
                )
            ) {
                flapRetries++
                externalAttempts = 0
                // 补偿重试不走 6 秒的外部控制冷却：把上次动手时刻往前挪，几百毫秒后就能再试
                lastApplyAt = EnginePolicy.rewindForRetry(
                    now = now,
                    lastApplyAt = lastApplyAt,
                    cooldownMs = EXTERNAL_ONLY_GUARD_MS,
                    retryMs = FLAP_RETRY_MS,
                )
                log(
                    ctx,
                    "vpn dropped after ${connectedFor}ms -> retry " +
                        "($flapRetries/$MAX_FLAP_RETRIES)",
                )
                // 看门狗继续跑，下一拍就会重新发起连接
                return
            }

            externalAttempts = 0
            reapplyCount = 0
            connectedNotified = false
            autoSession = false
            userOff = true
            startToken++
            stopWatchdog()
            lastOffAt = now
            connectStartedAt = 0L
            // 常驻设置还指着客户端时，系统/客户端往往几秒内就把它重新拉起来，
            // 用户手动点断开，看到的却是「断一下又连上」。这里按关闭时的同一套还原逻辑收尾。
            if (active || settingPointsToUs) {
                val cleared = if (active) {
                    VpnSwitcher.disable(ctx, cfg.restoreOriginal, cfg.vpnPackage)
                } else {
                    VpnSwitcher.clearIfPointsTo(ctx, cfg.vpnPackage)
                }
                log(ctx, "vpn dropped, always-on cleared=$cleared so it stays off")
            }
            log(ctx, "vpn dropped -> stay off until a target app is reopened")
            return
        }

        // 已经被用户手动关掉，等下次打开目标应用
        if (userOff) return

        // 从这里往下模块真的要动手了，记下这轮是模块拉起的
        autoSession = true

        val now = SystemClock.elapsedRealtime()
        // 计时的起点放在「模块决定动手」这一刻：前面被冷却挡住的那些调用不算，
        // 否则「打开应用多久有网」会把这半秒的等待也算进去
        if (connectStartedAt == 0L) connectStartedAt = now

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

        val interval = EnginePolicy.reapplyInterval(
            reapplyCount = reapplyCount,
            maxFastReapply = MAX_FAST_REAPPLY,
            fastMs = REAPPLY_GUARD_MS,
            slowMs = REAPPLY_SLOW_MS,
            firstMs = REAPPLY_FIRST_MS,
        )
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
        // 外部控制模式（重启 VpnService）不会写 always_on，因此 disable 会被跳过；
        // 用返回值区分「真的关掉了设置」和「只是请求客户端自己断开」
        val settingCleared = VpnSwitcher.disable(ctx, cfg.restoreOriginal, cfg.vpnPackage)
        val staleCleared = VpnSwitcher.clearIfPointsTo(ctx, cfg.vpnPackage)
        val tookOwnership = settingCleared || staleCleared
        reapplyCount = 0
        tileAttempts = 0
        externalAttempts = 0
        connectedNotified = false
        wasConnected = false
        connectedAt = 0L
        connectStartedAt = 0L
        flapRetries = 0
        offStartedAt = SystemClock.elapsedRealtime()
        lastOffAt = offStartedAt
        stopWatchdog()
        startToken++
        pendingExternalMode = null
        autoSession = false
        userOff = false
        log(
            ctx,
            if (tookOwnership) "vpn OFF ($reason)"
            else "off requested ($reason, 未接管常驻设置)",
        )
        if (wasActive) toast(ctx, "VPN 已关闭", cfg.toastEnabled)

        // 清掉 always-on 未必能断开「用户手动连上的」VPN，稍等一下确认。
        // 外部控制模式没有设置项可清，直接把校验提前，尽快进入停止链路。
        // 清掉常驻设置后系统很快会 unbind，1.2 秒是白等；外部控制模式本来就没有设置项，直接查
        val verifyDelay = if (tookOwnership) 600L else 300L
        handler.postDelayed({
            verifyOff(
                ctx, cfg.vpnPackage, generation,
                // 开了「保留客户端进程」就先只走温和手段，实在关不掉再强停
                forceStopAllowed = EnginePolicy.allowForceStopOnClose(
                    forceStopEnabled = cfg.forceStopVpn,
                    keepClientAlive = cfg.keepClientAlive,
                ),
            )
        }, verifyDelay)
        // 外部控制模式下客户端可能自己把 VPN 拉回来，关掉后盯一小会儿
        if (!tookOwnership && wasActive) {
            startOffGuard(ctx, cfg, generation, ++offGuardToken)
        }
        // 保留客户端进程：等到隧道真的断了再 bind 一次 VpnService，把进程拉热。
        // 进程还在就什么都不做；这样下次点开目标应用不用等冷启动。
        if (cfg.keepClientAlive && cfg.vpnPackage.isNotEmpty()) {
            handler.postDelayed({
                if (!closeChainValid(ctx, cfg.vpnPackage, generation)) return@postDelayed
                if (hasVpnTransport(ctx)) return@postDelayed
                if (isProcessAlive(ctx, cfg.vpnPackage)) return@postDelayed
                // 上一轮强停可能留下了 stopped 标记，先清掉再 bind，否则拉不起来
                Packages.unstop(cfg.vpnPackage)
                ExternalControl.prewarm(ctx, cfg.vpnPackage)
            }, 1_500L)
        }
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
     * 关闭链确认隧道已经消失时，把「从请求关闭到真的断开」的耗时写进诊断。
     *
     * 只记第一次确认：关后守卫发现客户端又把 VPN 拉起来时会再关一次，
     * 那属于另一轮，不该把第一轮的耗时覆盖成很短的假数据。
     */
    private fun noteVpnDown(ctx: Context) {
        val started = offStartedAt
        if (started == 0L) return
        offStartedAt = 0L
        RuntimeDiagnostics.publishTiming(ctx, "off", SystemClock.elapsedRealtime() - started)
    }

    /**
     * 关闭后校验：如果隧道还在（典型场景：用户在 VPN 客户端里手动连上的，
     * 清 always-on 设置并不会断开它），就依次尝试「停止请求 → 快捷磁贴 → 强制停止」。
     */
    private fun verifyOff(
        ctx: Context,
        expectedPackage: String,
        generation: Int,
        forceStopAllowed: Boolean = true,
    ) {
        if (!closeChainValid(ctx, expectedPackage, generation)) return
        if (!hasVpnTransport(ctx)) {
            noteVpnDown(ctx)
            log(ctx, "verify: vpn is down")
            return
        }

        val cfg = ConfigRepo.get(ctx)
        val hasTile = expectedPackage.isNotEmpty() && QuickTiles.find(ctx, expectedPackage) != null
        val plan = EnginePolicy.stopOrder(
            hasPackage = expectedPackage.isNotEmpty(),
            allowActivityUi = cfg.allowVpnUi,
            hasTile = hasTile,
            forceStopEnabled = cfg.forceStopVpn && forceStopAllowed,
        )

        // 1) 无 UI：给客户端发它自己的停止广播（Clash 系支持）。
        //    service 模式也试一次——某些客户端（mihomo / Clash.Meta）虽然只能靠
        //    VpnService 启动，但仍然实现了 STOP 广播，发一下比直接强杀更温和。
        val isServiceMode = expectedPackage.isNotEmpty() &&
            ExternalControl.modeOf(ctx, expectedPackage) == "service"
        if (EnginePolicy.StopStep.BROADCAST in plan &&
            ExternalControl.requestStop(ctx, expectedPackage)
        ) {
            awaitDown(
                ctx, expectedPackage, generation,
                EnginePolicy.broadcastWaitMs(isServiceMode),
            ) { down ->
                if (down) {
                    noteVpnDown(ctx)
                    log(ctx, "verify: vpn is down (client stopped)")
                } else {
                    verifyOffStep(ctx, expectedPackage, generation, true, plan)
                }
            }
            return
        }
        verifyOffStep(ctx, expectedPackage, generation, true, plan)
    }

    /**
     * 关后守卫。
     *
     * 外部控制模式（重启 VpnService）关掉之后，客户端可能被自己的服务、JobScheduler
     * 或系统重新拉起来。这里在 8 秒窗口内盯几次，发现隧道又起来就再关一次；
     * 连续多次仍回来就放弃并记录，避免和客户端互相拉扯。
     *
     * 省电设计：只在没有接管常驻设置时启动；间隔递增（600ms 起，最慢 2.4s），
     * 整个窗口只查 5 次左右；窗口结束不留下任何定时器。
     */
    private fun startOffGuard(ctx: Context, cfg: Config, generation: Int, token: Int) {
        if (!cfg.offGuard || cfg.vpnPackage.isEmpty()) return
        var attempts = 0
        var interval = 600L
        var elapsed = 0L

        fun tick() {
            if (token != offGuardToken) return
            if (elapsed >= GUARD_WINDOW_MS) return
            handler.postDelayed({
                elapsed += interval
                if (token != offGuardToken) return@postDelayed
                if (!closeChainValid(ctx, cfg.vpnPackage, generation)) return@postDelayed

                if (!hasVpnTransport(ctx)) {
                    interval = EnginePolicy.nextGuardInterval(interval)
                    tick()
                    return@postDelayed
                }

                attempts++
                if (!EnginePolicy.shouldGuard(
                        guardEnabled = cfg.offGuard,
                        tookOwnership = false,
                        attempts = attempts,
                        maxAttempts = MAX_GUARD_ATTEMPTS,
                        closable = true,
                    )
                ) {
                    log(
                        ctx,
                        "guard: 客户端已 $attempts 次重新拉起 VPN，停止抑制，" +
                            "请检查客户端的「自动连接」设置",
                    )
                    return@postDelayed
                }
                log(ctx, "guard: vpn came back -> re-off ($attempts/$MAX_GUARD_ATTEMPTS)")
                // 走完整关闭链（停止广播 → 磁贴 → 强停），而不是只点磁贴：
                // 磁贴是切换语义，连续点两次会把 VPN 又打开
                verifyOff(ctx, cfg.vpnPackage, generation)
                // 等关闭链跑完再查（广播 2s + 磁贴 2s），避免在 transport 还没刷新时重复动手
                interval = 3_500L
                tick()
            }, interval)
        }
        tick()
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
        plan: List<EnginePolicy.StopStep>,
    ) {
        if (!closeChainValid(ctx, expectedPackage, generation)) return
        val cfg = ConfigRepo.get(ctx)
        if (!hasVpnTransport(ctx)) {
            noteVpnDown(ctx)
            log(ctx, "verify: vpn is down")
            return
        }

        // 2) 客户端导出的 STOP_CLASH 入口
        if (allowActivity && EnginePolicy.StopStep.ACTIVITY in plan &&
            cfg.allowVpnUi && expectedPackage.isNotEmpty() &&
            ExternalControl.stop(ctx, expectedPackage)
        ) {
            awaitDown(ctx, expectedPackage, generation, 2_500L) { down ->
                if (down) {
                    noteVpnDown(ctx)
                    log(ctx, "verify: vpn is down (client stop activity)")
                } else {
                    verifyOffStep(ctx, expectedPackage, generation, false, plan)
                }
            }
            return
        }

        // 3) 快捷磁贴
        val tile = if (EnginePolicy.StopStep.TILE in plan) {
            QuickTiles.find(ctx, expectedPackage)
        } else {
            null
        }
        if (tile != null && QuickTiles.click(ctx, tile)) {
            awaitDown(ctx, expectedPackage, generation, 2_000L) { down ->
                if (down) {
                    noteVpnDown(ctx)
                    log(ctx, "verify: vpn is down (qs tile)")
                } else {
                    finishVerifyOff(ctx, expectedPackage, generation, 0, plan)
                }
            }
            return
        }
        finishVerifyOff(ctx, expectedPackage, generation, 0, plan)
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
        plan: List<EnginePolicy.StopStep>,
    ) {
        if (!closeChainValid(ctx, expectedPackage, generation)) return
        val cfg = ConfigRepo.get(ctx)
        if (!hasVpnTransport(ctx)) {
            noteVpnDown(ctx)
            log(
                ctx,
                if (attempt == 0) "verify: vpn is down" else "verify: disconnected after force stop"
            )
            return
        }
        if (EnginePolicy.StopStep.FORCE_STOP !in plan ||
            !cfg.forceStopVpn || expectedPackage.isEmpty()
        ) {
            // 「保留客户端进程」时上面只跑了温和手段；确实关不掉才允许强停一次，
            // 宁可让进程被杀，也不能出现「点了断开、VPN 还连着」
            if (cfg.keepClientAlive && cfg.forceStopVpn && expectedPackage.isNotEmpty()) {
                log(ctx, "verify: 温和手段没关掉，改为强制停止（进程会在关闭后重新预热）")
                finishVerifyOff(
                    ctx, expectedPackage, generation, attempt,
                    listOf(EnginePolicy.StopStep.FORCE_STOP),
                )
                return
            }
            log(ctx, "verify: vpn still up, no further step allowed")
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
                finishVerifyOff(ctx, expectedPackage, generation, attempt + 1, plan)
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
                // 没连上就快步重试：原来的固定 5 秒一拍会让「第一次没成功」再白等 5 秒
                val connected = hasVpnTransport(ctx)
                connectingTicks = if (connected) 0 else connectingTicks + 1
                handler.postDelayed(
                    this,
                    EnginePolicy.reconcileDelay(
                        connected = connected,
                        connectingTicks = connectingTicks,
                        maxFastTicks = RECONCILE_CONNECTING_TICKS,
                        fastMs = RECONCILE_CONNECTING_MS,
                        normalMs = RECONCILE_INTERVAL_MS,
                    ),
                )
            }
        }
    }

    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        // 首拍用连接窗口的短间隔：刚发起连接就复核一次，不用干等 5 秒
        handler.postDelayed(watchdog, RECONCILE_CONNECTING_MS)
    }

    private fun stopWatchdog() {
        watchdogRunning = false
        connectingTicks = 0
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
