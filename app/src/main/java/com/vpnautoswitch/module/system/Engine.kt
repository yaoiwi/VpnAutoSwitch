package com.vpnautoswitch.module.system

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
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
import android.os.PowerManager
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

    internal const val TAG = "VpnAutoSwitch"
    const val HEARTBEAT_KEY = "vpn_autoswitch_heartbeat"

    /** 当前加载进 system_server 的模块版本号，App 用它判断「是不是旧代码」 */
    const val MODULE_VERSION_KEY = "vpn_autoswitch_module_version"

    /** 当前 Wi-Fi 名称，写给 App 显示（App 自己没有定位权限，读不到 SSID） */
    const val SSID_KEY = "vpn_autoswitch_ssid"

    /** App 通过这个 action 请求重启（受 signature 权限保护） */
    /** App 请求清空运行日志（App 没有 WRITE_SECURE_SETTINGS，只能让 system_server 代删） */
    const val ACTION_CLEAR_LOG = "com.vpnautoswitch.module.action.CLEAR_LOG"
    /** 跟随 applicationId，和清单里的 signature 权限保持一致 */
    internal val REBOOT_PERMISSION = "${BuildConfig.APPLICATION_ID}.permission.REBOOT"

    internal const val RECONCILE_INTERVAL_MS = 5_000L

    /**
     * 已经发起连接、隧道还没起来时的对账间隔。
     *
     * 原来固定 5 秒一拍：第一次尝试没连上，用户就要多等 5 秒才轮到第二次。
     * 连接窗口里改成 0.9 秒一拍，连上或拍数用尽后自动退回 [RECONCILE_INTERVAL_MS]。
     */
    internal const val RECONCILE_CONNECTING_MS = 900L
    internal const val RECONCILE_CONNECTING_TICKS = 8

    /** 同一次启动会连着走几次 startActivity，同一个包在这个窗口内只提前探一次 */
    internal const val PREWARM_DEDUPE_MS = 1_200L

    /** 首次重写常驻设置前的等待：给客户端一个建隧道的时间，又不至于等满 12 秒 */
    internal const val REAPPLY_FIRST_MS = 4_000L

    /** 关后守卫：窗口总长与最多重关次数 */
    internal const val GUARD_WINDOW_MS = 8_000L
    internal const val MAX_GUARD_ATTEMPTS = 2

    /**
     * 守卫首拍的等待。
     *
     * 以前是 2.6 秒硬编码「猜」主链跑完，结果常常撞在主链点完磁贴、还在等生效的那一段，
     * 于是判定「又回来了」并开第二条链。现在守卫由主链结束时接力启动，首拍只需留一点余量。
     */
    internal const val GUARD_FIRST_MS = 500L

    /** 关闭链的兜底时长：超过这个时间还没释放 busy，就认为那条链已经哑了，守卫接管 */
    internal const val CLOSE_CHAIN_MAX_MS = 15_000L

    /** 锁屏计时的最大分钟数，防止配置被改坏后挂一个几小时的定时器 */
    internal const val SCREEN_OFF_MAX_MINUTES = 120

    internal var offGuardToken = 0

    /** 独立心跳：不依赖目标应用或看门狗，App 用它判断模块是否仍在运行 */
    internal const val HEARTBEAT_ACTIVE_MS = 30_000L

    /** 没有目标应用、也没有模块自己的会话时降频：少写一次就少唤醒一次设置观察者 */
    internal const val HEARTBEAT_IDLE_MS = 60_000L

    /** 版本号兜底重写的间隔（按心跳次数算）：设置被外部清掉时还能自己补回来 */
    internal const val VERSION_REWRITE_TICKS = 20

    /**
     * 心跳的最短写入间隔。
     *
     * 应用内换页、弹窗、从系统面板返回都会各来一次 activityResumed，
     * 每次写一遍心跳纯属浪费：SettingsProvider 每写一次就唤醒所有观察者。
     * App 的判活阈值是 90 秒，3 秒节流完全不影响判断。
     */
    internal const val BEAT_MIN_INTERVAL_MS = 3_000L

    /** 运行时快照内容没变时，最多每 5 秒重写一次 */
    internal const val RUNTIME_REWRITE_MS = 5_000L

    /**
     * 重写 always-on 设置会先清空再写回，等于让系统 unbind + rebind 一次 VpnService。
     * 客户端（尤其 Clash 系）建立隧道要好几秒，写得太频繁会一直打断它，
     * 表现就是「connected 永远 false，偶尔才成功」——所以这里给足冷却时间。
     *
     * 12 秒是按「写完只能干等」定的；现在每次启动之后都有 [EXTERNAL_INIT_GRACE_MS]
     * 的进程宽限挡着，重复打断已经由宽限负责，冷却再压 12 秒就只是白等。
     * 日志 03:11:40 → 03:11:53 那 13 秒空窗就是它。
     */
    internal const val REAPPLY_GUARD_MS = 6_000L
    internal const val REAPPLY_SLOW_MS = 60_000L
    internal const val MAX_FAST_REAPPLY = 4
    internal const val TILE_AFTER_REAPPLY = 1
    internal const val TILE_COOLDOWN_MS = 12_000L
    internal const val MAX_TILE_ATTEMPTS = 3

    /**
     * 关闭链里两次点磁贴的最小间隔。
     *
     * 磁贴是切换：主关闭链点完还没生效，关后守卫又点一次，就会把刚断开的 VPN 重新打开。
     * 8 秒足够让第一次点击彻底生效，也够 2 秒的 tunnel 复查跑完。
     */
    internal const val TILE_TOGGLE_GUARD_MS = 8_000L

    /** 强停之后这段时间内不预热：客户端刚被杀掉，绑起来等于把「关掉」作废 */
    internal const val PREWARM_AFTER_FORCE_STOP_MS = 10_000L

    /** 关闭后至少等这么久再预热：给客户端留出拆隧道的时间 */
    internal const val PREWARM_DELAY_MS = 1_500L

    /**
     * 预热后复查间隔。
     *
     * 有的客户端在进程被唤醒时（onCreate / onBind）就把上次的连接恢复了，bind 一下等于替它
     * 又开了 VPN。这里要晚于 bind 的上限（[ExternalControl] 里 6 秒），
     * 否则隧道还没建起来就下结论。
     */
    internal const val PREWARM_CHECK_MS = 7_000L

    /**
     * 客户端不响应 always-on 时，改用它的外部控制入口（START_CLASH / STOP_CLASH）。
     *
     * 20 秒的来历是「给客户端留足建隧道的时间」，但这段时间现在由进程宽限
     * （[EXTERNAL_INIT_GRACE_MS]：进程活着就一直等、进程没了才重发）精确控制，
     * 冷却只需要防止无意义的重复请求。日志 03:11:53 → 03:12:08 → 03:12:23
     * 两次 15 秒的空等就是这么来的。
     */
    internal const val EXTERNAL_COOLDOWN_MS = 8_000L
    internal const val MAX_EXTERNAL_ATTEMPTS = 3

    /**
     * 已经确认要用外部控制的客户端，两次动手之间的最短间隔。
     *
     * 以前是 6 秒，本意是「别把慢启动的客户端打断」——但这件事现在由
     * [EXTERNAL_INIT_GRACE_MS] 管：进程活着就一直等，进程没了才重发。
     * 冷却再压 6 秒就是重复保护，白白把重发往后推（日志里 15:14:41 冷却到点
     * 却因为宽限还在，又干等 4 秒）。3 秒足够防止抖动，也不和宽限打架。
     *
     * 这 3 秒同时是**唯一**的重发节奏。以前另有一个 1.8 秒的独立补发定时器，它比
     * 客户端进程本身的启动还早：`isProcessAlive` 那时必然读到「还没起来」，于是每次
     * 都判定「启动没生效」再补一刀。日志 02:00:37 → 02:00:39 → 02:00:43 六秒里发了
     * 三次启动，而 v2rayNG 每次 onStartCommand 都会把正在建的隧道推倒重建，
     * 表现就是「连上五秒又掉」。那条补发链已经删掉，重发只走这里。
     */
    internal const val EXTERNAL_ONLY_GUARD_MS = 3_000L

    /**
     * 外部控制模式下「别去打断正在建隧道」的宽限期。
     *
     * 看门狗在等隧道时每 900ms 一拍，冷却一到就会再发一次 startService；而客户端进程还活着
     * 说明上一次启动已被接受、隧道正在建，这一下再发启动，有的客户端会当成一次新连接、
     * 把正在建的那条推倒重来。所以要等。
     *
     * 但等多久不能凭感觉。日志里 v2rayNG 的所有成功案例都在 2 秒内建好
     * （15:12:58→15:13:00、15:14:52→15:14:54），而每次超过 10 秒没隧道的那一轮，
     * 等到 10 秒重发后反而 1~5 秒就通了——说明那一次启动根本没被客户端采纳。
     * 原来是 10 秒，等于每次失败都白等 8 秒，用户感知就是「划掉再打开要十几秒」。
     * 4 秒：给正常慢启动留 2 秒余量，又不让白等拖住重发。
     */
    internal const val EXTERNAL_INIT_GRACE_MS = 4_000L

    /** 同一个应用的 foreground 日志在这个窗口内最多记一条：应用内部换页会刷出一堆没信息量的行 */
    internal const val FOREGROUND_LOG_MS = 30_000L

    /**
     * 强停客户端之后，多久之内不许它被 bind / startService 拉起来。
     *
     * 覆盖两种情况：system_server 里的 Vpn 还挂着 ServiceConnection，以及客户端自己的
     * 前台服务被 START_STICKY 重启。窗口要比守卫的一次复查长（守卫首拍 0.5s、之后 2.4s），
     * 但明显短于用户「退出后又想用」的手速，中间被打断也算不清。
     */
    internal const val CLIENT_START_GUARD_MS = 6_000L

    /**
     * 强停前先调 `stopService` 让客户端自己断，等这么久看它断没断。
     *
     * 这个等待只是「给它一次体面退出的机会」，不能拖住关闭：被系统 bind 着的 VpnService
     * 光靠 stopService 是停不掉的（bind 连接还在，服务不会走 onDestroy），
     * 所以等满 900ms 也照样进强停。500ms 足够让响应快的客户端自己收工。
     */
    internal const val STOP_SERVICE_WAIT_MS = 500L

    /** 隧道状态的记忆时长：一拍看门狗里会连问好几次，超过这个时间才重新查 */
    internal const val TRANSPORT_MEMO_MS = 400L

    /**
     * 关闭前的去抖。
     *
     * 划掉任务、看门狗确认没有目标存活时，用户意图已经很明确，0.5 秒就够；
     * 进程死亡有可能是系统回收后立刻重启，多等一会儿免得来回抖。
     */
    internal const val OFF_FAST_MS = 500L
    internal const val OFF_SLOW_MS = 1_500L

    /**
     * 「刚回到前台」的保护窗口：这段时间内收到该包的进程死亡，一律当成上一轮进程的
     * 残留回调，不关 VPN。
     *
     * 3 秒是按实测定的——真正的退出（划掉 / 按返回后进程被回收）不会在回到前台 3 秒内
     * 发生；而 AMS 清理残留记录恰好紧跟在 startProcessLocked 之后，也就是 launch 那一刻。
     */
    internal const val DEATH_STALE_WINDOW_MS = 3_000L

    /**
     * 「任务被移除」被判定成残留之后，隔多久复查一次。
     *
     * 用户真的划掉应用时，桌面的 resume 事件一般紧跟着 removeTask 回调，
     * 这两秒足够它翻面；复查时它已经不在前台，就按正常退出处理。
     */
    internal const val TASK_RECHECK_MS = 2_000L

    /**
     * 「刚才那个 task removed 是我们自己造成的」的保护窗口。
     *
     * 没有外部控制入口的客户端（FlClash 改版）只能靠把界面拉到前台来触发它连接。
     * 界面一起来，正在用的目标应用就被挤到后台，系统紧接着回调 removeTask ——
     * 包名完全正确、也确实离开了前台，但用户根本没走。照单全收的结果就是
     * 「VPN 连上 1 秒就被自己关掉」（日志 02:50:48 启动 → 02:50:49 task removed → 02:50:50 vpn OFF）。
     *
     * 6 秒覆盖「界面冷启动 + 用户看清楚再切回去」，而真人在这段时间里划掉目标应用的概率可以忽略；
     * 真被误判也只是这一次不关，下次回到前台会重新拉起。
     */
    internal const val UI_TASK_REMOVAL_GUARD_MS = 6_000L

    /**
     * 刚连上就掉的补偿重试。
     *
     * 有的客户端先 establish 再因为「这次没连成」断开，隧道只活一秒左右。
     * 以前这被当成用户手动关闭，直接挂上 userOff，得退出应用再打开才会重连。
     * 现在自动补一次，最多 [MAX_FLAP_RETRIES] 次，且只等 [FLAP_RETRY_MS]。
     */
    internal const val FLAP_RETRY_MS = 250L
    internal const val MAX_FLAP_RETRIES = 2

    /**
     * 强制停止后隧道可能还要一会儿才消失：分批复查，仍连着就再停一次。
     *
     * 以前是 2s、4s、6s（乘 attempt），一次没关干净就要 10 秒以上，用户体感就是
     * 「退出应用后 VPN 还开着，下拉状态栏才看到它变灰」。现在强停后会拦住客户端自启
     * （见 [ClientStartGuard]），没有「刚杀完又被拉起来」的来回，复查就可以快：
     * 800 / 1200 / 1600ms 三次足够覆盖系统拆 transport 的时间。
     */
    internal const val FORCE_STOP_RECHECK_MS = 800L
    internal const val MAX_FORCE_STOP_ATTEMPTS = 3
    internal const val FAILURE_HINT_AT = 5

    /** 初始化重试期间最多缓存的事件数，防止异常 ROM 下无限增长 */
    internal const val MAX_PENDING_EVENTS = 64

    /**
     * 开机后等配置的耐心。
     *
     * 模块拿配置要查询 App 的 provider，而 App 进程可能起不来（刚装完没点开过、
     * 被 ROM 自启动管理拦住）。8 次 × 2 秒足够覆盖开机初期那段拥堵，
     * 拿到镜像也算成功，所以真正走完这 8 次的场景很少。
     */
    internal const val CONFIG_WARMUP_ATTEMPTS = 8
    internal const val CONFIG_WARMUP_INTERVAL_MS = 2_000L

    /** 配置读不到时，这条提醒最多每 30 秒记一次（否则每次切换应用都刷一行） */
    internal const val CONFIG_MISSING_LOG_MS = 30_000L

    internal val thread = HandlerThread("VpnAutoSwitch").apply {
        priority = Thread.NORM_PRIORITY
        start()
    }
    internal val handler = Handler(thread.looper)
    internal var heartbeatStarted = false

    init {
        // 日志合并写入跟着工作线程走，不额外起线程
        ModuleLog.attach(handler)
    }

    /** 「客户端还在初始化，先不重复启动」这一轮是否已经记过日志（看门狗 900ms 一拍，别刷屏） */
    internal var initWaitLogged = false

    /**
     * 最近一次真正发出启动请求的时刻。
     *
     * 宽限期要按「这次启动」算，不能按整轮算：用户划掉再打开时会先失败一次，
     * 整轮早就超过宽限，用整轮时刻算会让宽限对之后的每一次重发都失效，
     * 结果变成每 900ms 重发一次、把客户端反复推倒。
     */
    internal var lastStartAt = 0L

    /**
     * 最近一次「我们自己把客户端界面拉到前台」的时刻（0 表示没做过）。
     *
     * 只给 [EnginePolicy.isUiInducedTaskRemoval] 用：界面顶掉目标应用之后那条
     * removeTask 回调不能当成用户离开，否则拉起客户端的动作本身就会关掉 VPN。
     */
    internal var uiShownAt = 0L

    /**
     * 本轮关闭是否已经试过「让客户端自己停 VpnService」。
     *
     * 强停是最后手段，代价不只是进程没了：VPN 会话还登记在 system_server 时，
     * 进程一被杀就会被重新 bind 起来（日志里「强停后 app alive=true、再停一次才断」）。
     * 先调 stopService 让客户端走自己的 onDestroy，既温和又能真正拆掉会话。
     * 只试一次，没效果就照常强停。
     */
    internal var stopServiceTried = false

    /** 关后守卫的结束时刻：窗口内不预热，否则 bind 正好把守卫要抑制的客户端拉回来 */
    internal var guardUntil = 0L

    /**
     * 主关闭链是否还在跑，以及跑的是哪一代。
     *
     * 守卫只能等这条链彻底收尾后再出手：两条链会互相把对方排队的复查作废
     * （`offGeneration` 一变，另一边的 `closeChainValid` 就全部静默返回）。
     */
    internal var closeChainRunning = false
    internal var closeChainGen = 0
    internal var closeChainStartedAt = 0L

    /** 主链结束后要接力的守卫请求；主链没跑完之前先存着，别提前开窗 */
    internal var pendingGuard: GuardRequest? = null

    internal data class GuardRequest(
        val vpnPackage: String,
        val generation: Int,
        val tookOwnership: Boolean,
        val wasActive: Boolean,
        val wasUserOff: Boolean,
    )

    /**
     * 主链和守卫都收尾后再预热客户端进程；在那之前只记着这个请求。
     *
     * 预热（bind VpnService）会把客户端进程叫醒，正好撞在守卫「别让它回来」的枪口上，
     * 所以两条链没跑完之前不能动手——以前是靠「主链 15s + 守卫 8s」估一个 23 秒，
     * 结果正常关闭也要等半分钟才变热，等于白开这个开关。
     */
    internal var pendingPrewarm: PrewarmRequest? = null

    internal data class PrewarmRequest(
        val vpnPackage: String,
        /** 最早动手的时刻：给客户端一点时间把隧道拆干净 */
        val notBefore: Long,
    )

    /** 锁屏已到点、正在保持断开：期间不自动重连，直到用户解锁 */
    internal var screenOffActive = false
    internal var screenOffToken = 0
    internal var screenOffAt = 0L

    /** VPN 隧道检测的记忆值：一拍里多处问到同一个状态时不再重复遍历网络 */
    internal var transportAt = 0L
    internal var transportValue = false

    /** foreground 日志去重：同一个应用 30 秒内只记一条 */
    internal var lastForegroundLogAt = 0L
    internal var lastLoggedForegroundPkg = ""

    /** 心跳计数，用来决定哪一次心跳顺手重写一次版本号 */
    internal var versionTicks = 0

    internal val heartbeat = object : Runnable {
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
    internal fun heartbeatDelay(): Long {
        val idle = aliveTargets.isEmpty() && offAppForeground == null && !autoSession
        return if (idle) HEARTBEAT_IDLE_MS else HEARTBEAT_ACTIVE_MS
    }

    @Volatile
    internal var appContext: Context? = null

    internal val aliveTargets = HashSet<String>()

    /**
     * 最近一个真正回到前台的目标应用。
     *
     * [lastForeground] 会被任何应用（含桌面）覆盖，划掉任务的回调又可能晚于桌面 resume，
     * 用它判断「用户刚收起的是不是正在用的那个」会看走眼，所以单独记一份。
     */
    internal var lastForegroundTarget: String? = null

    /**
     * 当前正在前台的「打开即关闭」应用。
     * 非空期间不再自动开启 VPN，直到它自己退出进程、或用户回到目标应用。
     */
    internal var offAppForeground: String? = null

    /**
     * 这一轮 VPN 是模块自己拉起来的（而不是用户手动连的）。
     * 关掉后清零；看门狗只在它为 true 时才敢主动断开，避免误杀用户手动连的 VPN。
     */
    internal var autoSession = false

    /**
     * 用户手动关掉了 VPN（下拉磁贴 / 在 VPN 客户端里点断开）。
     * 置 true 后不再自动重连，直到他重新打开目标应用（不是应用内部的页面切换）。
     */
    internal var userOff = false

    /** 最近出现在前台的包：用来区分「重新打开应用」和「应用内部 resume」 */
    internal var lastForeground: String? = null

    /**
     * 最近一次看到目标应用回到前台的时刻（elapsedRealtime）。
     *
     * 专门用来识别「滞后到达的进程死亡」：AMS 清理上一轮残留 ProcessRecord 时也会回调，
     * 而这个回调带着正确的包名，很容易被当成「用户刚把应用退了」。Chrome 这种多进程应用
     * 每次从被杀状态拉起都会走一遍，日志里表现为 launching 与 process died 同一秒。
     */
    internal var lastForegroundSeenAt = 0L
    internal var watchdogRunning = false

    /** 连接窗口里已经快轮询了几拍，连上就清零 */
    internal var connectingTicks = 0

    /** 上一次提前连接的包与时间，用来给同一次启动去重 */
    internal var lastPrewarmPkg: String? = null
    internal var lastPrewarmAt = 0L
    internal var lastApplyAt = 0L
    internal var lastTileClickAt = 0L
    internal var tileAttempts = 0

    /** 关闭链里最后一次点快捷磁贴的时刻：磁贴是切换语义，短时间点两次会把 VPN 又打开 */
    internal var lastTileToggleAt = 0L

    /** 本轮关闭链是否允许强停。用户手动关过的那条隧道置 false，避免越权杀掉他连的客户端 */
    internal var chainForceStopAllowed = true

    /** 最后一次强制停止客户端的时刻：刚停完不能立刻预热，否则等于把它拉回来 */
    internal var lastForceStopAt = 0L

    /** 刚被强停、还没在下一次连接时清掉 stopped 标记的包（只用于那条日志） */
    internal var lastForceStopPkg: String? = null
    internal var reapplyCount = 0
    internal var externalAttempts = 0
    internal var clientTestToken = 0
    internal var lastExternalAt = 0L
    /** 本轮接管是否已经提示过「VPN 已开启」，避免设置写完但隧道没起来就误报 */
    internal var connectedNotified = false
    internal var wasConnected = false

    /**
     * 这一轮关闭还欠用户一条「VPN 已关闭」提示。
     *
     * 提示不能在下发关闭时就发：请求发出后目标应用可能马上又回到前台（划掉 A 切到 B），
     * 关闭链整轮作废、VPN 其实一直连着，先说「已关闭」就成了假消息。
     * 改到 [noteVpnDown] 里，等隧道真的没了再说。
     */
    internal var offToastPending = false

    /** 本轮第一次看到「已连接」的时刻，用来分辨「刚连上就掉」和「用了一会儿才掉」 */
    internal var connectedAt = 0L

    /** 本轮开始动手连接的时刻；连上后算出「打开应用多久有网」写进诊断 */
    internal var connectStartedAt = 0L

    /** 本轮开始关闭的时刻；确认隧道真的没了就算出「关闭花了多久」 */
    internal var offStartedAt = 0L

    /** 刚连上就掉的补偿重试次数；稳定连上后清零 */
    internal var flapRetries = 0

    internal var offToken = 0
    internal var startToken = 0

    /** 「任务被移除」判定成残留后排队的那次复查；换一个就作废上一个 */
    internal var taskRecheckToken = 0

    /**
     * 上一次「断开」发生的时刻（模块主动关，或检测到用户手动断开）。
     *
     * 断开后一两秒里 VPN 常常又被客户端自己重连、或被「始终开启的 VPN」拉回来；
     * 那一下不是用户打开的，不能再提示「VPN 已开启」，否则他刚点了关闭却看到已开启。
     */
    internal var lastOffAt = 0L

    /** 心跳与运行时快照的写入节流，见 [BEAT_MIN_INTERVAL_MS] / [RUNTIME_REWRITE_MS] */
    internal var lastBeatAt = 0L
    internal var lastRuntimeAt = 0L
    internal var lastRuntimeKey: String? = null

    /** 每次关闭验证链的代次；新的开启意图或新的关闭链都会推进它 */
    internal var offGeneration = 0

    /** 快捷磁贴暂停自动联动；只影响自动化，不改用户配置 */
    internal var automationPaused = false

    /** 初始化还没完成时按顺序缓存事件，避免先操作 VPN、随后又被 boot 清理掉 */
    internal sealed interface PendingEvent {
        data class Foreground(val packageName: String) : PendingEvent
        data class Died(val packageName: String, val detail: String? = null) : PendingEvent
        data class TaskRemoved(val packageName: String) : PendingEvent
    }

    internal val pendingEvents = ArrayDeque<PendingEvent>()

    /** 本轮是通过哪种外部方式发起启动的，连上后据此记住该客户端 */
    internal var pendingExternalMode: String? = null

    internal var failureStreak = 0
    internal var failureLogged = false

    /** 当前连着的 Wi-Fi 名称（不带引号）；非 Wi-Fi 时为 null */
    internal var currentSsid: String? = null

    /** 当前 Wi-Fi 命中「指定 Wi-Fi 关 VPN」规则：期间不再自动开启 */
    internal var wifiSuppressed = false

    /** 当前默认网络的传输类型：0 未知 / 1 Wi-Fi / 2 移动数据 / 3 其他 */
    internal var lastTransport = 0
    internal var networkCallback: ConnectivityManager.NetworkCallback? = null
    internal var rebootReceiverRegistered = false
    internal var booted = false

    /** 上一次记「配置读不到」的时间，用于去重（参见 [noteConfigMissing]） */
    internal var lastConfigMissingAt = 0L

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
        handler.post {
            // 用户自己点开了 VPN 客户端：他要用它，立刻撤掉强停后的自启抑制，
            // 否则他点「连接」会起不来，看起来像模块把客户端弄坏了
            if (packageName == ConfigRepo.get(context()).vpnPackage) ClientStartGuard.release()
            prewarm(packageName)
        }
    }

    internal fun prewarm(pkg: String) {
        val ctx = context() ?: return
        val cfg = ConfigRepo.getOrNull(ctx)
        if (cfg == null) {
            noteConfigMissing(ctx, pkg)
            return
        }
        if (!cfg.enabled || cfg.vpnPackage.isEmpty()) return
        if (pkg == cfg.vpnPackage || pkg == BuildConfig.APPLICATION_ID) return
        if (!cfg.targets.contains(pkg)) return
        if (automationPaused || userOff || wifiSuppressed || offAppForeground != null) return
        if (screenOffActive) return
        if (!isNetworkAllowed(cfg)) return
        // 已经连着就不用插手，resumed 之后走正常流程
        if (hasVpnTransport(ctx)) return

        // 同一次启动会连着走好几次 startActivity，同一个包只提前探一次
        val now = SystemClock.elapsedRealtime()
        if (pkg == lastPrewarmPkg && now - lastPrewarmAt < PREWARM_DEDUPE_MS) return
        lastPrewarmPkg = pkg
        lastPrewarmAt = now

        log(ctx, "$pkg launching -> prewarm")
        ensureVpn(ctx, cfg, pkg)
        // 点了又立刻退出时没人收尾，交给看门狗发现「没有目标存活」后再关
        startWatchdog()
    }

    /**
     * @param detail 触发这次回调的 AMS 方法与进程 pid，只写进日志，用来区分
     *   「真的退出了」和「AMS 清理残留记录」。null 表示调用方没提供。
     */
    fun onProcessDied(packageName: String, detail: String? = null) {
        handler.post { dispatchProcessDied(packageName, detail) }
    }

    fun onTaskRemoved(packageName: String) {
        handler.post { dispatchTaskRemoved(packageName) }
    }

    internal fun dispatchForeground(packageName: String) {
        if (!booted) {
            enqueue(PendingEvent.Foreground(packageName))
            return
        }
        handleForeground(packageName)
        publishRuntime(context())
    }

    internal fun dispatchProcessDied(packageName: String, detail: String? = null) {
        if (!booted) {
            enqueue(PendingEvent.Died(packageName, detail))
            return
        }
        handleProcessDied(packageName, detail)
        publishRuntime(context())
    }

    internal fun dispatchTaskRemoved(packageName: String) {
        if (!booted) {
            enqueue(PendingEvent.TaskRemoved(packageName))
            return
        }
        handleTaskRemoved(packageName)
        publishRuntime(context())
    }

    internal fun enqueue(event: PendingEvent) {
        if (pendingEvents.size >= MAX_PENDING_EVENTS) pendingEvents.removeFirst()
        pendingEvents.addLast(event)
    }

    internal fun replayPendingEvents() {
        while (pendingEvents.isNotEmpty()) {
            when (val event = pendingEvents.removeFirst()) {
                is PendingEvent.Foreground -> handleForeground(event.packageName)
                is PendingEvent.Died -> handleProcessDied(event.packageName, event.detail)
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
    internal fun onBoot(attempt: Int) {
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
    internal fun warmupConfig(ctx: Context, attempt: Int) {
        // 配置只能从 App 暴露的 provider 读，而 provider 通不通取决于 App 进程在不在。
        // 开机那一刻它一定没起，系统会不会替我们去起它，各家 ROM 规矩不一样（自启动管理、
        // 后台限制），最终表现就是「重启后必须先打开一次模块界面」。
        // 与其干等，不如自己动手：调用方是 system_server，既不受后台启动限制，
        // 也能启动非 exported 组件。
        if (attempt == 0 || attempt == 3) kickAppProcess(ctx)

        val cfg = ConfigRepo.getOrNull(ctx)
        if (cfg != null) {
            when {
                attempt > 0 ->
                    log(ctx, "config ready after ${attempt * 2}s (source=${ConfigRepo.lastSource()})")
                ConfigRepo.lastSource() != "provider" ->
                    log(ctx, "config from ${ConfigRepo.lastSource()} cache, app process not up yet")
            }
            return
        }
        if (attempt == 0) log(ctx, "config not ready yet, waiting for the app process")
        if (attempt >= CONFIG_WARMUP_ATTEMPTS) {
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
    internal fun kickAppProcess(ctx: Context) {
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
            log(
                ctx,
                if (started != null) "config: module app process requested"
                else "config: 模块 App 的同步服务没找到，继续等 provider",
            )
        } catch (t: Throwable) {
            log(ctx, "config: 拉不起模块 App 进程（$t），继续等 provider")
        }
    }

    /**
     * 配置彻底读不到（provider 失败且没有镜像）时记一句。
     *
     * 以前这种情况是静默 return 的：用户看到的就是「打开应用什么都没发生」，
     * 日志里一个字都没有，只能靠猜。做去重是因为每次切应用都会走到这里。
     */
    internal fun noteConfigMissing(ctx: Context, pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastConfigMissingAt < CONFIG_MISSING_LOG_MS) return
        lastConfigMissingAt = now
        log(ctx, "config unavailable, $pkg ignored (start the module app once)")
    }

    internal fun readPaused(ctx: Context): Boolean = try {
        Settings.Secure.getInt(ctx.contentResolver, ModuleContract.PAUSED_KEY, 0) == 1
    } catch (t: Throwable) {
        false
    }

    /**
     * 快捷磁贴 / App 触发的暂停开关。
     *
     * 暂停只停止自动化，不主动断开当前 VPN；恢复时按当前前台与网络规则重新对账。
     */
    internal fun togglePause(ctx: Context) {
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
    internal fun testClient(ctx: Context, pkg: String, mode: String) {
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

    internal fun pollClientTest(
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

    internal fun forgetClientMode(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) return
        ExternalControl.forget(ctx, pkg)
        if (ModuleState.prewarmUnsupported(ctx) == pkg) ModuleState.setPrewarmUnsupported(ctx, null)
        RuntimeDiagnostics.publishClientTest(ctx, pkg, "", "idle", "已清除记住的启动方式")
        log(ctx, "client mode forgotten: $pkg")
    }

    internal fun startHeartbeat() {
        if (heartbeatStarted) return
        heartbeatStarted = true
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
    }

    internal fun retryBoot(attempt: Int) {
        if (attempt < 90) {
            handler.postDelayed({ onBoot(attempt + 1) }, 2_000L)
        } else {
            XposedBridge.log("[$TAG] boot init gave up after $attempt attempts")
        }
    }

    /** @return true 表示 Settings 写入成功，说明 AMS / ContentProvider 已经就绪 */
    internal fun beat(ctx: Context): Boolean {
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

    internal fun log(ctx: Context?, message: String) {
        XposedBridge.log("[$TAG] $message")
        ModuleLog.append(ctx, message)
    }

    internal fun cleanupStaleState(ctx: Context) {
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

    internal fun registerNetworkCallback(ctx: Context) {
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
    internal fun registerRebootReceiver(ctx: Context): Boolean {
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

    internal fun isNetworkAllowed(cfg: Config): Boolean =
        EnginePolicy.isNetworkAllowed(lastTransport, cfg.allowWifi, cfg.allowMobile)

    // ------------------------------------------------------------ 锁屏断开

    /**
     * 锁屏后延时断开。
     *
     * 手机放兜里通宵挂着 VPN，流量和电量都在漏。到点先确认屏幕真的还关着，
     * 再用和「划掉任务」同一套关闭链断掉，并置 [screenOffActive] 挡住自动重连。
     */
    internal fun onScreenOff(ctx: Context) {
        val cfg = ConfigRepo.get(ctx)
        if (!cfg.enabled || !cfg.disconnectOnScreenOff || cfg.vpnPackage.isEmpty()) return
        if (automationPaused) return
        if (!hasVpnTransport(ctx)) return

        val minutes = cfg.screenOffMinutes.coerceIn(1, SCREEN_OFF_MAX_MINUTES)
        screenOffAt = SystemClock.elapsedRealtime()
        val token = ++screenOffToken
        log(ctx, "screen off -> disconnect in ${minutes}min")
        handler.postDelayed({
            if (token != screenOffToken) return@postDelayed
            val current = ConfigRepo.get(ctx)
            val elapsed = SystemClock.elapsedRealtime() - screenOffAt
            if (!EnginePolicy.shouldDisconnectOnScreenOff(
                    enabled = current.disconnectOnScreenOff && current.enabled,
                    screenOn = isScreenOn(ctx),
                    hasTunnel = hasVpnTransport(ctx, fresh = true),
                    elapsedMs = elapsed,
                    configuredMinutes = current.screenOffMinutes,
                )
            ) {
                return@postDelayed
            }
            log(ctx, "screen off ${current.screenOffMinutes}min -> off")
            screenOffActive = true
            turnOff("screen off")
        }, minutes * 60_000L)
    }

    /**
     * 亮屏：作废还没到点的计时。
     *
     * 已经因为锁屏断开了就保持断开——亮屏多半只是看一眼时间，等解锁（[onScreenUnlocked]）再恢复，
     * 否则每次抬手看表都要重连一次 VPN。
     */
    internal fun cancelScreenOffTimer(ctx: Context) {
        if (screenOffToken == 0) return
        screenOffToken++
        screenOffAt = 0L
        if (!screenOffActive) log(ctx, "screen on -> cancel the pending disconnect")
    }

    /** 解锁：解除锁屏断开状态，目标应用还活着就按原来的规则重新连上 */
    internal fun onScreenUnlocked(ctx: Context) {
        if (!screenOffActive) return
        screenOffActive = false
        val cfg = ConfigRepo.get(ctx)
        if (!cfg.enabled || cfg.vpnPackage.isEmpty()) return
        log(ctx, "unlocked -> auto connect available again")
        if (aliveTargets.isNotEmpty() || isWifiOnActive(cfg)) {
            ensureVpn(ctx, cfg)
            startWatchdog()
        }
        publishRuntime(ctx)
    }

    internal fun isScreenOn(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isInteractive
    } catch (t: Throwable) {
        // 读不到就当作亮着：宁可少断一次，也不要在用户正用手机时断开
        true
    }

    internal fun isWifiOnActive(cfg: Config): Boolean {
        val ssid = currentSsid ?: return false
        return lastTransport == 1 && cfg.wifiOn.contains(ssid)
    }

    /** 只描述当前命中的规则，不参与任何控制决策。 */
    internal fun currentRule(cfg: Config): Pair<String, String> {
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

    internal fun publishRuntime(ctx: Context?) {
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
    internal fun refreshSsid(ctx: Context, isWifi: Boolean): String? {
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

    internal fun handleNetworkChanged() {
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

    // ---------------------------------------------------------------- 对账（看门狗）

    /** 每 5 秒对账一次；具体逻辑在 EngineWatch.kt 的 [watchdogTick] 里 */
    internal val watchdog = Runnable { watchdogTick() }

    // ---------------------------------------------------------------- 工具

    internal fun context(): Context? {
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

