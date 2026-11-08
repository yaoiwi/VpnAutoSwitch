package com.vpnautoswitch.module.system

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.vpnautoswitch.module.BuildConfig
import com.vpnautoswitch.module.ModuleContract
import de.robv.android.xposed.XposedBridge

// 启动与心跳：注入入口、开机引导、配置预热、心跳、广播接收器

internal fun Engine.attach(ctx: Context?) {
    if (ctx != null) appContext = ctx
    handler.post { onBoot(0) }
}

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
internal fun Engine.onBoot(attempt: Int) {
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
    // 带上版本号和开机时长：升级后忘了重启作用域、或者跑的还是旧包时，
    // 一眼就能看出来；复现「重启后不生效」时也能看出模块是开机多久才就绪的
    log(
        ctx,
        "module ready v${com.vpnautoswitch.module.BuildConfig.VERSION_NAME}" +
            " (${com.vpnautoswitch.module.BuildConfig.VERSION_CODE})" +
            " at uptime ${SystemClock.elapsedRealtime() / 1000}s, death hooks + watchdog",
    )
    startHeartbeat()
    // 先确认配置能拿到，再重放开机期间排队的事件：
    // 拿不到配置就去处理这些事件，会把「已配置」误判成「没配置」
    warmupConfig(ctx, 0)
    replayPendingEvents()
    publishRuntime(ctx)
}

/**
 * 开机后主动确认一次配置。
 *
 * provider 查不通时（App 处于 stopped、被 ROM 拦掉自启动），模块会退回上一次
 * 写下的镜像；两者都没有才继续重试。日志里能看出这一轮是 provider、镜像，还是
 * 彻底拿不到——这决定了「打开目标应用不动」到底是配置问题还是别的。
 */
internal fun Engine.warmupConfig(ctx: Context, attempt: Int) {
    // 配置只能从 App 暴露的 provider 读，而 provider 通不通取决于 App 进程在不在。
    // 开机那一刻它一定没起，系统会不会替我们去起它，各家 ROM 规矩不一样（自启动管理、
    // 后台限制），最终表现就是「重启后必须先打开一次模块界面」。
    // 与其干等，不如自己动手：调用方是 system_server，既不受后台启动限制，
    // 也能启动非 exported 组件。
    //
    // 只让第一次出声：第二次（attempt 3）是同一件事的重复尝试，成功后会有
    // 「module app process requested」，失败也没必要把同一段现场再打一遍。
    if (attempt == 0 || attempt == 3) kickAppProcess(ctx, verbose = attempt == 0)

    val cfg = ConfigRepo.getOrNull(ctx)
    if (cfg != null) {
        when {
            attempt > 0 ->
                log(ctx, "config ready after ${attempt * 2}s (source=${ConfigRepo.lastSource()})")
            ConfigRepo.lastSource() != "provider" ->
                log(ctx, "config from ${ConfigRepo.lastSource()} cache, app process not up yet")
        }
        // 配置是异步到的：它到达之前发生的网络事件会被丢掉（那时 ConfigRepo 还是空的），
        // 而「开机时 Wi-Fi 已经连着、用户直接开目标应用」正是最常见的情形。
        // 不在这里补一次评估，就得等下一次网络切换规则才会生效。
        handler.post {
            handleNetworkChanged()
            publishRuntime(ctx)
        }
        return
    }
    if (attempt == 0) log(ctx, "config not ready yet, waiting for the app process")
    if (attempt >= CONFIG_WARMUP_ATTEMPTS) {
        // 设备还没解锁：App 的非 directBootAware 组件一律起不来，怎么重试都白搭。
        // 不在这里判死刑，降到 30 秒一次等解锁；解锁广播会立刻再叫一次（见 onUserUnlocked）。
        if (isUserUnlocked(ctx) == false) {
            if (!lockedWaitLogged) {
                lockedWaitLogged = true
                log(ctx, "config: 设备还没解锁，模块 App 起不来，等解锁后自动重试")
            }
            // 解锁那一刻 App 才允许启动，所以每轮都再敲一次门：成功的那次会打
            // 「module app process requested」，从日志能直接看出解锁后是否接上了
            kickAppProcess(ctx)
            handler.postDelayed({ warmupConfig(ctx, attempt) }, USER_LOCKED_RETRY_MS)
            return
        }
        log(
            ctx,
            "config unavailable after ${attempt * 2}s: " +
                "the module app was never started, automation stays idle",
        )
        return
    }
    handler.postDelayed({ warmupConfig(ctx, attempt + 1) }, CONFIG_WARMUP_INTERVAL_MS)
}

/**
 * 主动把模块 App 的进程拉起来（只为一个空服务，不显示任何界面）。
 *
 * 拉起来之后 provider 就能查了，配置随即可用；同时 [ConfigRepo] 会把配置镜像写进
 * Secure 设置，下次开机就算系统还不让起进程，也能直接用镜像。
 */
/**
 * @param verbose 拉不起来时要不要把现场写进模块日志。重试轮次传 false，只留 Xposed 日志，
 *   免得同一段诊断在日志里出现两遍，看着像「一直在失败」。
 */
internal fun Engine.kickAppProcess(ctx: Context, verbose: Boolean = true) {
    val pkg = BuildConfig.APPLICATION_ID
    try {
        // 被强停过的包（stopped）连 startService 都会被拒，先清标记
        Packages.unstop(pkg)
        // 组件名要用类的全名（com.vpnautoswitch.module.*），不是 applicationId
        val started = ctx.startService(
            Intent().setComponent(
                ComponentName(pkg, com.vpnautoswitch.module.ConfigSyncService::class.java.name),
            ),
        )
        if (started != null) {
            log(ctx, "config: module app process requested")
        } else {
            // 返回 null 表示系统没接这次启动，原因可能是包还在 stopped、用户还没解锁、
            // 或者 ROM 拦了后台启动。这三者处理方式完全不同，所以把现场一起打出来。
            val serviceFound = try {
                ctx.packageManager.getServiceInfo(
                    ComponentName(pkg, com.vpnautoswitch.module.ConfigSyncService::class.java.name),
                    0,
                )
                true
            } catch (t: Throwable) {
                // 光知道「没找到」不够：开机初期 PackageManager 自己也可能抛 NPE，
                // 那种情况组件其实是好的，下一次就成功。把异常类型带上才分得清。
                if (verbose) log(ctx, "config: 查同步服务失败（${t.javaClass.simpleName}: ${t.message}）")
                false
            }
            val stopped = try {
                (ctx.packageManager.getApplicationInfo(pkg, 0).flags and
                    android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
            } catch (t: Throwable) {
                null
            }
            val enabled = try {
                ctx.packageManager.getApplicationInfo(pkg, 0).enabled
            } catch (t: Throwable) {
                null
            }
            // 开机那几十秒里，前三项往往全是正常的：service 在、没被强停、组件也没禁用，
            // 就是起不来。剩下唯一说得通的原因就是用户还没解锁（FBE），所以把它一起打出来。
            val unlocked = isUserUnlocked(ctx)
            val line = "config: 模块 App 的同步服务起不来" +
                "（service=$serviceFound, stopped=$stopped, enabled=$enabled, " +
                "unlocked=$unlocked），继续等 provider"
            if (verbose) log(ctx, line) else XposedBridge.log("[$TAG] $line")
        }
    } catch (t: Throwable) {
        val line = "config: 拉不起模块 App 进程（$t），继续等 provider"
        if (verbose) log(ctx, line) else XposedBridge.log("[$TAG] $line")
    }
}

/**
 * 配置彻底读不到（provider 失败且没有镜像）时记一句。
 *
 * 以前这种情况是静默 return 的：用户看到的就是「打开应用什么都没发生」，
 * 日志里一个字都没有，只能靠猜。做去重是因为每次切应用都会走到这里。
 */
internal fun Engine.noteConfigMissing(ctx: Context, pkg: String) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastConfigMissingAt < CONFIG_MISSING_LOG_MS) return
    lastConfigMissingAt = now
    log(ctx, "config unavailable, $pkg ignored (start the module app once)")
}

internal fun Engine.heartbeatDelay(): Long {
    val idle = aliveTargets.isEmpty() && offAppForeground == null && !autoSession
    return if (idle) HEARTBEAT_IDLE_MS else HEARTBEAT_ACTIVE_MS
}

internal fun Engine.startHeartbeat() {
    if (heartbeatStarted) return
    heartbeatStarted = true
    handler.removeCallbacks(heartbeat)
    handler.post(heartbeat)
}

internal fun Engine.retryBoot(attempt: Int) {
    if (attempt < 90) {
        handler.postDelayed({ onBoot(attempt + 1) }, 2_000L)
    } else {
        XposedBridge.log("[$TAG] boot init gave up after $attempt attempts")
    }
}

/** @return true 表示 Settings 写入成功，说明 AMS / ContentProvider 已经就绪 */
internal fun Engine.beat(ctx: Context): Boolean {
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

internal fun Engine.cleanupStaleState(ctx: Context) {
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

/**
 * 安装/更新模块后 system_server 里还是旧代码，必须重启才能生效。
 * App 侧点按钮时如果模块已经加载，就由这里直接重启，省掉用户手动操作。
 */
internal fun Engine.registerRebootReceiver(ctx: Context): Boolean {
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
                Intent.ACTION_SCREEN_OFF -> onScreenOff(ctx)
                Intent.ACTION_SCREEN_ON -> cancelScreenOffTimer(ctx)
                Intent.ACTION_USER_PRESENT -> onScreenUnlocked(ctx)
                // 解锁完成：开机时拉不起 App 进程的，在这里补一次
                Intent.ACTION_USER_UNLOCKED -> onUserUnlocked(ctx)
            }
        }
    }
    try {
        val filter = IntentFilter().apply {
            addAction(ACTION_CLEAR_LOG)
            addAction(ModuleContract.ACTION_TOGGLE_PAUSE)
            addAction(ModuleContract.ACTION_TEST_CLIENT)
            addAction(ModuleContract.ACTION_FORGET_CLIENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
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
        // LSPosed 往往比 AMS 上线早，这时 registerReceiver 会 NPE。这属于预期内的
        // 「还没就绪」，retryBoot 两秒后会再来，所以别写成 failed——那看起来像坏了。
        log(ctx, "reboot receiver not ready yet (${t.javaClass.simpleName}), retry in 2s")
        return false
    }
}
