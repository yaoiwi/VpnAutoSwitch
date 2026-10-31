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

    /**
     * 守卫首拍的等待。
     *
     * 以前是 2.6 秒硬编码「猜」主链跑完，结果常常撞在主链点完磁贴、还在等生效的那一段，
     * 于是判定「又回来了」并开第二条链。现在守卫由主链结束时接力启动，首拍只需留一点余量。
     */
    private const val GUARD_FIRST_MS = 500L

    /** 关闭链的兜底时长：超过这个时间还没释放 busy，就认为那条链已经哑了，守卫接管 */
    private const val CLOSE_CHAIN_MAX_MS = 15_000L

    /** 锁屏计时的最大分钟数，防止配置被改坏后挂一个几小时的定时器 */
    private const val SCREEN_OFF_MAX_MINUTES = 120

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

    /**
     * 关闭链里两次点磁贴的最小间隔。
     *
     * 磁贴是切换：主关闭链点完还没生效，关后守卫又点一次，就会把刚断开的 VPN 重新打开。
     * 8 秒足够让第一次点击彻底生效，也够 2 秒的 tunnel 复查跑完。
     */
    private const val TILE_TOGGLE_GUARD_MS = 8_000L

    /** 强停之后这段时间内不预热：客户端刚被杀掉，绑起来等于把「关掉」作废 */
    private const val PREWARM_AFTER_FORCE_STOP_MS = 10_000L

    /** 关闭后至少等这么久再预热：给客户端留出拆隧道的时间 */
    private const val PREWARM_DELAY_MS = 1_500L

    /**
     * 预热后复查间隔。
     *
     * 有的客户端在进程被唤醒时（onCreate / onBind）就把上次的连接恢复了，bind 一下等于替它
     * 又开了 VPN。这里要晚于 bind 的上限（[ExternalControl] 里 6 秒），
     * 否则隧道还没建起来就下结论。
     */
    private const val PREWARM_CHECK_MS = 7_000L

    /** 客户端不响应 always-on 时，改用它的外部控制入口（START_CLASH / STOP_CLASH） */
    private const val EXTERNAL_COOLDOWN_MS = 20_000L
    private const val MAX_EXTERNAL_ATTEMPTS = 3

    /**
     * 已经确认要用外部控制的客户端，两次动手之间的最短间隔。
     *
     * 以前是 6 秒，本意是「别把慢启动的客户端打断」——但这件事现在由
     * [EXTERNAL_INIT_GRACE_MS] 管：进程活着就一直等，进程没了才重发。
     * 冷却再压 6 秒就是重复保护，白白把重发往后推（日志里 15:14:41 冷却到点
     * 却因为宽限还在，又干等 4 秒）。3 秒足够防止抖动，也不和宽限打架。
     */
    private const val EXTERNAL_ONLY_GUARD_MS = 3_000L

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
    private const val EXTERNAL_INIT_GRACE_MS = 4_000L

    /**
     * 首次启动后多久还没看到隧道就补发一次启动。
     *
     * 6 秒的外部控制冷却是为了「别把慢启动的客户端打断」，但第一次明明没成也要干等满 6 秒，
     * 用户感知就是「点开应用要等七八秒才有网」。1.8 秒补发一次（只补一次，走不 stop 的
     * startVpnService，onStartCommand 幂等），冷却仍然留给真正没反应的情况。
     */
    private const val FAST_RETRY_MS = 1_800L

    /** 同一个应用的 foreground 日志在这个窗口内最多记一条：应用内部换页会刷出一堆没信息量的行 */
    private const val FOREGROUND_LOG_MS = 30_000L

    /**
     * 强停客户端之后，多久之内不许它被 bind / startService 拉起来。
     *
     * 覆盖两种情况：system_server 里的 Vpn 还挂着 ServiceConnection，以及客户端自己的
     * 前台服务被 START_STICKY 重启。窗口要比守卫的一次复查长（守卫首拍 0.5s、之后 2.4s），
     * 但明显短于用户「退出后又想用」的手速，中间被打断也算不清。
     */
    private const val CLIENT_START_GUARD_MS = 6_000L

    /**
     * 强停前先调 `stopService` 让客户端自己断，等这么久看它断没断。
     *
     * 这个等待只是「给它一次体面退出的机会」，不能拖住关闭：被系统 bind 着的 VpnService
     * 光靠 stopService 是停不掉的（bind 连接还在，服务不会走 onDestroy），
     * 所以等满 900ms 也照样进强停。500ms 足够让响应快的客户端自己收工。
     */
    private const val STOP_SERVICE_WAIT_MS = 500L

    /** 隧道状态的记忆时长：一拍看门狗里会连问好几次，超过这个时间才重新查 */
    private const val TRANSPORT_MEMO_MS = 400L

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

    /**
     * 强制停止后隧道可能还要一会儿才消失：分批复查，仍连着就再停一次。
     *
     * 以前是 2s、4s、6s（乘 attempt），一次没关干净就要 10 秒以上，用户体感就是
     * 「退出应用后 VPN 还开着，下拉状态栏才看到它变灰」。现在强停后会拦住客户端自启
     * （见 [ClientStartGuard]），没有「刚杀完又被拉起来」的来回，复查就可以快：
     * 800 / 1200 / 1600ms 三次足够覆盖系统拆 transport 的时间。
     */
    private const val FORCE_STOP_RECHECK_MS = 800L
    private const val MAX_FORCE_STOP_ATTEMPTS = 3
    private const val FAILURE_HINT_AT = 5

    /** 初始化重试期间最多缓存的事件数，防止异常 ROM 下无限增长 */
    private const val MAX_PENDING_EVENTS = 64

    /**
     * 开机后等配置的耐心。
     *
     * 模块拿配置要查询 App 的 provider，而 App 进程可能起不来（刚装完没点开过、
     * 被 ROM 自启动管理拦住）。8 次 × 2 秒足够覆盖开机初期那段拥堵，
     * 拿到镜像也算成功，所以真正走完这 8 次的场景很少。
     */
    private const val CONFIG_WARMUP_ATTEMPTS = 8
    private const val CONFIG_WARMUP_INTERVAL_MS = 2_000L

    /** 配置读不到时，这条提醒最多每 30 秒记一次（否则每次切换应用都刷一行） */
    private const val CONFIG_MISSING_LOG_MS = 30_000L

    private val thread = HandlerThread("VpnAutoSwitch").apply {
        priority = Thread.NORM_PRIORITY
        start()
    }
    private val handler = Handler(thread.looper)
    private var heartbeatStarted = false

    init {
        // 日志合并写入跟着工作线程走，不额外起线程
        ModuleLog.attach(handler)
    }

    /** 这一轮连接是否已经补发过一次启动 */
    private var fastRetryDone = false

    /** 「客户端还在初始化，先不重复启动」这一轮是否已经记过日志（看门狗 900ms 一拍，别刷屏） */
    private var initWaitLogged = false

    /**
     * 最近一次真正发出启动请求的时刻。
     *
     * 宽限期要按「这次启动」算，不能按整轮算：用户划掉再打开时会先失败一次，
     * 整轮早就超过宽限，用整轮时刻算会让宽限对之后的每一次重发都失效，
     * 结果变成每 900ms 重发一次、把客户端反复推倒。
     */
    private var lastStartAt = 0L

    /**
     * 本轮关闭是否已经试过「让客户端自己停 VpnService」。
     *
     * 强停是最后手段，代价不只是进程没了：VPN 会话还登记在 system_server 时，
     * 进程一被杀就会被重新 bind 起来（日志里「强停后 app alive=true、再停一次才断」）。
     * 先调 stopService 让客户端走自己的 onDestroy，既温和又能真正拆掉会话。
     * 只试一次，没效果就照常强停。
     */
    private var stopServiceTried = false

    /** 关后守卫的结束时刻：窗口内不预热，否则 bind 正好把守卫要抑制的客户端拉回来 */
    private var guardUntil = 0L

    /**
     * 主关闭链是否还在跑，以及跑的是哪一代。
     *
     * 守卫只能等这条链彻底收尾后再出手：两条链会互相把对方排队的复查作废
     * （`offGeneration` 一变，另一边的 `closeChainValid` 就全部静默返回）。
     */
    private var closeChainRunning = false
    private var closeChainGen = 0
    private var closeChainStartedAt = 0L

    /** 主链结束后要接力的守卫请求；主链没跑完之前先存着，别提前开窗 */
    private var pendingGuard: GuardRequest? = null

    private data class GuardRequest(
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
    private var pendingPrewarm: PrewarmRequest? = null

    private data class PrewarmRequest(
        val vpnPackage: String,
        /** 最早动手的时刻：给客户端一点时间把隧道拆干净 */
        val notBefore: Long,
    )

    /**
     * 一 bind 就自己连上的客户端：预热对它等于「又开一次 VPN」，
     * 记下来以后不再预热，免得每次关闭都多一轮开→关。
     */
    private var prewarmUnsupportedPkg: String? = null

    /** 锁屏已到点、正在保持断开：期间不自动重连，直到用户解锁 */
    private var screenOffActive = false
    private var screenOffToken = 0
    private var screenOffAt = 0L

    /** VPN 隧道检测的记忆值：一拍里多处问到同一个状态时不再重复遍历网络 */
    private var transportAt = 0L
    private var transportValue = false

    /** foreground 日志去重：同一个应用 30 秒内只记一条 */
    private var lastForegroundLogAt = 0L
    private var lastLoggedForegroundPkg = ""

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

    /** 关闭链里最后一次点快捷磁贴的时刻：磁贴是切换语义，短时间点两次会把 VPN 又打开 */
    private var lastTileToggleAt = 0L

    /** 本轮关闭链是否允许强停。用户手动关过的那条隧道置 false，避免越权杀掉他连的客户端 */
    private var chainForceStopAllowed = true

    /** 最后一次强制停止客户端的时刻：刚停完不能立刻预热，否则等于把它拉回来 */
    private var lastForceStopAt = 0L
    private var reapplyCount = 0
    private var externalAttempts = 0
    private var clientTestToken = 0
    private var lastExternalAt = 0L
    /** 本轮接管是否已经提示过「VPN 已开启」，避免设置写完但隧道没起来就误报 */
    private var connectedNotified = false
    private var wasConnected = false

    /**
     * 这一轮关闭还欠用户一条「VPN 已关闭」提示。
     *
     * 提示不能在下发关闭时就发：请求发出后目标应用可能马上又回到前台（划掉 A 切到 B），
     * 关闭链整轮作废、VPN 其实一直连着，先说「已关闭」就成了假消息。
     * 改到 [noteVpnDown] 里，等隧道真的没了再说。
     */
    private var offToastPending = false

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

    /** 上一次记「配置读不到」的时间，用于去重（参见 [noteConfigMissing]） */
    private var lastConfigMissingAt = 0L

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

    private fun prewarm(pkg: String) {
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
    private fun warmupConfig(ctx: Context, attempt: Int) {
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
     * 配置彻底读不到（provider 失败且没有镜像）时记一句。
     *
     * 以前这种情况是静默 return 的：用户看到的就是「打开应用什么都没发生」，
     * 日志里一个字都没有，只能靠猜。做去重是因为每次切应用都会走到这里。
     */
    private fun noteConfigMissing(ctx: Context, pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastConfigMissingAt < CONFIG_MISSING_LOG_MS) return
        lastConfigMissingAt = now
        log(ctx, "config unavailable, $pkg ignored (start the module app once)")
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
        if (prewarmUnsupportedPkg == pkg) prewarmUnsupportedPkg = null
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

    private fun isNetworkAllowed(cfg: Config): Boolean =
        EnginePolicy.isNetworkAllowed(lastTransport, cfg.allowWifi, cfg.allowMobile)

    // ------------------------------------------------------------ 锁屏断开

    /**
     * 锁屏后延时断开。
     *
     * 手机放兜里通宵挂着 VPN，流量和电量都在漏。到点先确认屏幕真的还关着，
     * 再用和「划掉任务」同一套关闭链断掉，并置 [screenOffActive] 挡住自动重连。
     */
    private fun onScreenOff(ctx: Context) {
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
    private fun cancelScreenOffTimer(ctx: Context) {
        if (screenOffToken == 0) return
        screenOffToken++
        screenOffAt = 0L
        if (!screenOffActive) log(ctx, "screen on -> cancel the pending disconnect")
    }

    /** 解锁：解除锁屏断开状态，目标应用还活着就按原来的规则重新连上 */
    private fun onScreenUnlocked(ctx: Context) {
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

    private fun isScreenOn(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isInteractive
    } catch (t: Throwable) {
        // 读不到就当作亮着：宁可少断一次，也不要在用户正用手机时断开
        true
    }

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

    // ---------------------------------------------------------------- 事件

    private fun handleForeground(pkg: String) {
        val ctx = context() ?: return
        beat(ctx)
        val cfg = ConfigRepo.getOrNull(ctx)
        if (cfg == null) {
            // 「读不到配置」和「用户没配置」是两件事。这里绝不能往下走：
            // 下面的 `vpnPackage.isEmpty()` 分支会把正在用的 VPN 当成「模块已停用」直接关掉。
            noteConfigMissing(ctx, pkg)
            return
        }
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
            // 新一轮「用户主动打开」，抖动重试次数重新开始算
            flapRetries = 0
            log(ctx, "$pkg reopened -> auto enable resumed")
        }
        // 是「重新打开」就当作一次全新的连接请求，别被上一轮的掉线状态挡住
        if (freshOpen) wasConnected = false

        logForeground(ctx, pkg, freshOpen)
        if (isNetworkAllowed(cfg)) {
            ensureVpn(ctx, cfg)
        } else {
            log(ctx, "network type disabled, skip enabling")
        }
        startWatchdog()
    }

    /**
     * 「xxx -> foreground」只在有信息量时记一条。
     *
     * 应用内部换页、弹窗、从通知栏返回都会各来一次 activityResumed，
     * 每来一次记一条，60 行的缓冲区很快就被刷满，真正有用的失败信息反而被挤掉。
     */
    private fun logForeground(ctx: Context, pkg: String, freshOpen: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val repeated = pkg == lastLoggedForegroundPkg && now - lastForegroundLogAt < FOREGROUND_LOG_MS
        // 不是「重新打开」、VPN 也还连着：这一条没有信息量，跳过
        if (repeated || (!freshOpen && hasVpnTransport(ctx))) return
        lastLoggedForegroundPkg = pkg
        lastForegroundLogAt = now
        log(ctx, "$pkg -> foreground")
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
        // 只有本轮第一次启动才先停掉残留实例：这时客户端可能还挂着上一次的会话，
        // 停一下再起才能干净地建新隧道。重试时绝不能再停——第二次 stopService 会把
        // 第一次刚刚开始建立的隧道推倒，日志 17:52:53→17:53:10 的 17 秒就是这么来的
        // （三次「restart VPN service」，每次都先 stop 再 start）。
        if (externalAttempts <= 1) {
            ExternalControl.stopVpnService(ctx, scheduledPackage)
        }
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

    /**
     * 启动后 [FAST_RETRY_MS] 还没有隧道，就再发一次启动请求。
     *
     * 日志里三次都是「第一次白启动、第二次才成」，而两次之间被 6 秒冷却隔开。
     * 这里绕过冷却补发一次：走不 stop 的 startVpnService，onStartCommand 幂等，
     * 不会打断已经在建的那条隧道；只补一次，避免和客户端互相拉扯。
     */
    private fun scheduleFastRetry(ctx: Context, cfg: Config) {
        if (fastRetryDone || cfg.vpnPackage.isEmpty()) return
        fastRetryDone = true
        val pkg = cfg.vpnPackage
        handler.postDelayed({
            val current = ConfigRepo.get(ctx)
            // 期间状态变了就别补：用户关了模块、换了客户端、切到了「打开即关闭」的应用、
            // 或者自己手动关了 VPN，都不该再把它拉起来
            if (!current.enabled || current.vpnPackage != pkg) return@postDelayed
            if (automationPaused || userOff || wifiSuppressed || offAppForeground != null) {
                return@postDelayed
            }
            if (aliveTargets.isEmpty()) return@postDelayed
            if (hasVpnTransport(ctx)) return@postDelayed
            // 进程已经起来了就说明启动请求生效了，只是客户端还在建隧道（v2rayNG 要十秒左右）。
            // 这时候再 startService 一次，客户端会当成第二次「连接」请求，隧道建起来又断。
            if (isProcessAlive(ctx, pkg)) {
                log(ctx, "fast retry skipped: client process is alive, still initializing")
                return@postDelayed
            }
            ExternalControl.startVpnService(ctx, pkg)
            lastApplyAt = SystemClock.elapsedRealtime()
            log(ctx, "fast retry: no tunnel after ${FAST_RETRY_MS}ms, start service again")
        }, FAST_RETRY_MS)
    }

    // ---------------------------------------------------------------- VPN 控制

    private fun ensureVpn(ctx: Context, cfg: Config) {
        // 有开启意图就说明现在要用了：先撤掉强停后那段「不许客户端自启」的窗口，
        // 否则下面重新启动 VpnService 会被自己的闸门挡掉
        ClientStartGuard.release()
        if (automationPaused) return
        // 锁屏断开期间不自动重连，等用户解锁后由 onScreenUnlocked 恢复
        if (screenOffActive) return
        // 任何新的授权开启意图都让此前排队的关闭验证链失效
        offGeneration++
        // 关闭链还在跑的时候来了开启意图：多半是「划掉目标应用 A，切到目标应用 B」。
        // 这一轮整体作废——排队的复查、守卫、预热，还有「已关闭」的提示都要撤掉：
        // 隧道可能从头到尾没断过，先说「已关闭」再什么都不说，比不提示更让人糊涂。
        val cancelledOff = closeChainRunning
        if (cancelledOff) {
            offToastPending = false
            pendingPrewarm = null
            log(
                ctx,
                "off cancelled: ${lastForegroundTarget ?: lastForeground ?: "目标应用"} 回到前台，" +
                    "本轮不做关闭",
            )
        }
        // 关闭意图已经被开启取代：主链排队的复查都失效了，busy 标志和守卫请求也要作废，
        // 否则守卫会在兜底时长后跳出来，把用户刚连上的 VPN 又关掉一次
        closeChainRunning = false
        closeChainStartedAt = 0L
        pendingGuard = null
        offGuardToken++
        guardUntil = 0L
        // 新的开启意图说明这条隧道归模块管了，之后的关闭可以按正常力度来
        chainForceStopAllowed = true
        val settingPointsToUs = VpnSwitcher.currentAlwaysOn(ctx) == cfg.vpnPackage
        val active = VpnSwitcher.isActive(ctx.contentResolver)
        val connected = hasVpnTransport(ctx)

        if (connected) {
            // 已经连上了，说明用户「保持关闭」的意图已经结束（例如他自己又手动连上了）
            userOff = false
            val nowUp = SystemClock.elapsedRealtime()
            if (connectedAt == 0L) connectedAt = nowUp
            // 连稳了才清掉抖动重试的计数：否则「连上—掉—连上—掉」会一直重连下去。
            // 「稳」的门槛要比抖动窗口长，否则窗口内的掉落永远算不完次数。
            if (nowUp - connectedAt >= EnginePolicy.STABLE_CONNECTION_MS) flapRetries = 0
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
                when {
                    // 这一轮关闭刚被「目标应用回到前台」取消，VPN 从头到尾没断过：
                    // 状态没变，不提示「已开启」也不算漏话
                    cancelledOff ->
                        log(ctx, "off cancelled but vpn stayed up -> skip the 'enabled' toast")
                    // 刚断开的一两秒内又连上：多半是客户端自动重连，或系统按常驻设置把它拉了回来。
                    // 用户刚点的是「关闭」，这时提示「已开启」与他的操作正好相反，宁可不说。
                    !EnginePolicy.isFreshOff(SystemClock.elapsedRealtime() - lastOffAt) ->
                        toast(ctx, "VPN 已开启", cfg.toastEnabled)
                    else ->
                        log(ctx, "vpn came back right after off -> skip the 'enabled' toast")
                }
            }
            failureStreak = 0
            failureLogged = false
            tileAttempts = 0
            reapplyCount = 0
            externalAttempts = 0
            fastRetryDone = false
            initWaitLogged = false
            lastStartAt = 0L
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
            // 抖动重试靠看门狗下一拍发起。但看门狗在「连不上」时会累计 connectingTicks，
            // 满 8 拍就把节奏从 900ms 退到 5 秒——掉线时这个计数还留着，
            // 于是补偿重发被拖到 5 秒后（日志 15:14:47 掉、15:14:52 才重发）。
            // 掉线就等于重新开始一轮，计数清零，重发能在一拍内发出。
            connectingTicks = 0

            // 刚连上就掉：先怀疑「这次没连成」，不要当成用户手动断开。
            // 否则 userOff 一挂上，用户得退出应用再打开才会重连（日志里干等几十秒就是这个）。
            // 目标应用还停在前台时窗口放到 6 秒：用户明明开着应用，掉落几乎不会是手动断开，
            // 而 2 秒窗口常被采样误差顶到边界（15:02:57 连上、15:02:59 掉就属于这种）。
            if (autoSession && EnginePolicy.isStartupFlap(
                    connectedForMs = connectedFor,
                    retries = flapRetries,
                    maxRetries = MAX_FLAP_RETRIES,
                    windowMs = if (aliveTargets.isNotEmpty()) {
                        EnginePolicy.TARGET_FLAP_MS
                    } else {
                        EnginePolicy.STARTUP_FLAP_MS
                    },
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
            // 把「活了多久」写出来：下次再判成手动断开时，能直接看出是 2.1 秒还是 30 分钟，
            // 不用再靠时间戳反推
            val lifeText =
                if (connectedFor == Long.MAX_VALUE) "" else " after ${connectedFor}ms"
            log(ctx, "vpn dropped$lifeText -> stay off until a target app is reopened")
            return
        }

        // 已经被用户手动关掉，等下次打开目标应用
        if (userOff) return

        // 从这里往下模块真的要动手了，记下这轮是模块拉起的
        autoSession = true

        val now = SystemClock.elapsedRealtime()
        // 计时的起点放在「模块决定动手」这一刻：前面被冷却挡住的那些调用不算，
        // 否则「打开应用多久有网」会把这半秒的等待也算进去
        if (connectStartedAt == 0L) {
            connectStartedAt = now
            // 新的一轮连接，补发资格重置
            fastRetryDone = false
            initWaitLogged = false
            lastStartAt = 0L
        }

        val externalMode = ExternalControl.modeOf(ctx, cfg.vpnPackage)

        // 已经确认这个客户端不响应 always-on：不再写常驻 VPN 设置（避免 unbind 干扰）
        if (externalMode != null) {
            if (cfg.forceStopVpn) Packages.unstop(cfg.vpnPackage)

            if (!EnginePolicy.cooldownReady(now, lastApplyAt, EXTERNAL_ONLY_GUARD_MS)) return
            if (externalAttempts >= MAX_EXTERNAL_ATTEMPTS &&
                !EnginePolicy.cooldownReady(now, lastApplyAt, REAPPLY_SLOW_MS)
            ) return

            // 进程还活着，说明上一次启动请求已经被系统接受，隧道正在建。
            // 这时候再发一次启动，客户端会当成新连接把正在建的推倒重来——
            // 日志里「启动两次 → 7~11 秒才通、通了又掉」就是这么来的。
            if (EnginePolicy.waitForClientInit(
                    alreadyStarted = externalAttempts > 0,
                    sinceStartMs = now - lastStartAt,
                    processAlive = isProcessAlive(ctx, cfg.vpnPackage),
                    graceMs = EXTERNAL_INIT_GRACE_MS,
                )
            ) {
                if (!initWaitLogged) {
                    initWaitLogged = true
                    log(
                        ctx,
                        "external-only: 客户端还在初始化，最多再等 " +
                            "${EXTERNAL_INIT_GRACE_MS / 1000} 秒再重发启动",
                    )
                }
                return
            }

            lastApplyAt = now
            lastStartAt = now
            externalAttempts++

            if (externalMode == "activity" && cfg.allowVpnUi &&
                ExternalControl.start(ctx, cfg.vpnPackage)
            ) {
                pendingExternalMode = "activity"
                log(ctx, "external-only: start via external control (remembered)")
            } else if (externalMode == "service" || externalAttempts <= 2) {
                log(ctx, "external-only: restart VPN service without UI (attempt $externalAttempts)")
                scheduleDelayedServiceStart(ctx, cfg)
                scheduleFastRetry(ctx, cfg)
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
        // 用户刚手动关过 VPN：现在这条隧道是他自己又连回来的，模块不该去强停它
        val wasUserOff = userOff
        chainForceStopAllowed = !wasUserOff
        // 取消尚未执行的关闭任务，避免同一次关闭派生多条验证链
        offToken++
        // 手动连上的 VPN 没有接管标记，也要提示「已关闭」。
        // 这里必须绕开 400ms 缓存：turnOff 常常紧跟在一次「隧道还没建好」的采样之后，
        // 读到旧值就会把 wasActive 判成 false，后面的「VPN 已关闭」直接不发。
        val wasActive = VpnSwitcher.isActive(ctx.contentResolver) || hasVpnTransport(ctx, fresh = true)
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
        offToastPending = false
        connectedAt = 0L
        connectStartedAt = 0L
        flapRetries = 0
        fastRetryDone = false
        initWaitLogged = false
        lastStartAt = 0L
        stopServiceTried = false
        guardUntil = 0L
        // 取消上一轮的守卫窗口：这次是新的一次关闭，旧窗口盯着的是另一条链
        offGuardToken++
        pendingGuard = null
        pendingPrewarm = null
        // 主链从现在开始跑。守卫要等它彻底收尾后再接力——两条链同时跑会互相作废对方的复查。
        closeChainRunning = true
        closeChainGen = generation
        closeChainStartedAt = SystemClock.elapsedRealtime()
        val willGuard = EnginePolicy.shouldHandOffToGuard(
            guardEnabled = cfg.offGuard,
            tookOwnership = tookOwnership,
            wasActive = wasActive,
            wasUserOff = wasUserOff,
        )
        // 守卫窗口长度由 startOffGuard 按「真正开始观察」的时刻算，这里不再预估：
        // 以前按「主链 15s + 窗口 8s」压住预热，结果正常关闭也要等 23 秒客户端才变热。
        offStartedAt = SystemClock.elapsedRealtime()
        lastOffAt = offStartedAt
        stopWatchdog()
        startToken++
        pendingExternalMode = null
        autoSession = false
        userOff = false
        // 顺带把当前的 always-on 打出来：「强停后 VPN 又回来」只有在 always_on 指向客户端时
        // 才可能是系统重连，指向空/别的应用就只能是客户端自己或 ROM 拉起来的。
        // 以前这行信息缺失，只能靠「有没有 came back」去猜，白绕好几轮。
        val alwaysOnNow = VpnSwitcher.currentAlwaysOn(ctx).orEmpty()
        log(
            ctx,
            (if (tookOwnership) "vpn OFF ($reason)" else "off requested ($reason, 未接管常驻设置)") +
                ", always_on='${alwaysOnNow.ifEmpty { "-" }}'",
        )
        // 「VPN 已关闭」不在这里发：这条链随时可能因为「目标应用又回到前台」整轮作废，
        // 那时隧道一直连着，先说关闭就是假消息。改由 noteVpnDown 在确认断开时补上。
        offToastPending = wasActive

        // 清掉 always-on 未必能断开「用户手动连上的」VPN，稍等一下确认。
        // 外部控制模式没有设置项可清，直接把校验提前，尽快进入停止链路。
        // 清掉常驻设置后系统很快会 unbind，1.2 秒是白等；外部控制模式本来就没有设置项，直接查
        val verifyDelay = if (tookOwnership) 600L else 300L
        handler.postDelayed({
            verifyOff(
                ctx, cfg.vpnPackage, generation,
                // 开了「保留客户端进程」就先只走温和手段，实在关不掉再强停。
                // 用户手动关过的那条隧道例外：强停他手动连的客户端属于越权。
                forceStopAllowed = chainForceStopAllowed &&
                    EnginePolicy.allowForceStopOnClose(
                        forceStopEnabled = cfg.forceStopVpn,
                        keepClientAlive = cfg.keepClientAlive,
                        directForceStop = cfg.forceStopImmediately,
                    ),
            )
        }, verifyDelay)
        // 外部控制模式下客户端可能自己把 VPN 拉回来，关掉后盯一小会儿。
        // 用户刚手动关过的除外：那条隧道是他自己又连回来的，盯着他再关一次是越权。
        // 真正的窗口要等主链收尾才开（见 endCloseChain），这里只是把请求记下来。
        if (willGuard) {
            pendingGuard = GuardRequest(
                vpnPackage = cfg.vpnPackage,
                generation = generation,
                tookOwnership = tookOwnership,
                wasActive = wasActive,
                wasUserOff = wasUserOff,
            )
        }
        // 保留客户端进程：等主链和守卫都收尾后再 bind 一次 VpnService，把进程拉热。
        // 进程还在就什么都不做；这样下次点开目标应用不用等冷启动。
        // 「直接强制停止」本身就意味着不要留进程，跟这里正好相反，所以不预热。
        if (cfg.keepClientAlive && !cfg.forceStopImmediately &&
            cfg.vpnPackage.isNotEmpty() && cfg.vpnPackage != prewarmUnsupportedPkg
        ) {
            pendingPrewarm = PrewarmRequest(
                vpnPackage = cfg.vpnPackage,
                notBefore = SystemClock.elapsedRealtime() + PREWARM_DELAY_MS,
            )
            // 主链很快就结束、又没开守卫时由这一拍负责；否则它会把自己推迟到收尾之后再跑
            handler.postDelayed({ maybePrewarm(ctx) }, PREWARM_DELAY_MS)
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
        // 提示改成「真的断了才说」：请求发出后可能被目标应用打断，那时隧道一直没断
        if (offToastPending) {
            offToastPending = false
            toast(ctx, "VPN 已关闭", ConfigRepo.get(ctx).toastEnabled)
        }
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
        if (!hasVpnTransport(ctx, fresh = true)) {
            noteVpnDown(ctx)
            log(ctx, "verify: vpn is down")
            endCloseChain(ctx, generation)
            return
        }

        val cfg = ConfigRepo.get(ctx)
        val isServiceMode = expectedPackage.isNotEmpty() &&
            ExternalControl.modeOf(ctx, expectedPackage) == "service"
        val hasTile = expectedPackage.isNotEmpty() && QuickTiles.find(ctx, expectedPackage) != null
        val plan = EnginePolicy.stopOrder(
            hasPackage = expectedPackage.isNotEmpty(),
            allowActivityUi = cfg.allowVpnUi,
            // service 模式是模块用 startService 把客户端拉起来的，磁贴上的状态跟实际并不同步，
            // 点它等于盲按一次 toggle。日志里 15:50:32 点完，15:50:38 VPN 反而「came back」，
            // 之后又要强停一遍——这条路径本来就不该点磁贴。
            hasTile = hasTile && !isServiceMode,
            forceStopEnabled = cfg.forceStopVpn && forceStopAllowed,
            // 「直接强制停止」跳掉广播 / activity / 磁贴，一步到位
            directForceStop = cfg.forceStopImmediately,
        )
        // 把这轮打算用哪些手段记一行：以后看到「温和手段没关掉」时，
        // 能立刻分清是磁贴没找到、还是点了没反应，不必靠时间戳反推
        log(ctx, "verify: steps=" + plan.joinToString(">") { it.name.lowercase() })

        // 1) 无 UI：给客户端发它自己的停止广播（Clash 系支持）。
        //    service 模式也试一次——某些客户端（mihomo / Clash.Meta）虽然只能靠
        //    VpnService 启动，但仍然实现了 STOP 广播，发一下比直接强杀更温和。
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
                    endCloseChain(ctx, generation)
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
     * 与主关闭链**串行**：由 [endCloseChain] 在主链收尾后接力启动，窗口内每次动手前
     * 还会再看一眼 [closeChainRunning]。两条链同时跑会让 `offGeneration` 互相作废，
     * 结果是磁贴被点两次、强停次数从 1 重数，日志里出现同一秒的两条 verify。
     *
     * 省电设计：只在没有接管常驻设置时启动；间隔递增（首拍 0.5s，最慢 2.4s），
     * 整个窗口只查 5 次左右；窗口结束不留下任何定时器。
     */
    private fun startOffGuard(ctx: Context, req: GuardRequest) {
        val cfg = ConfigRepo.get(ctx)
        if (!cfg.offGuard || req.vpnPackage.isEmpty()) return
        val startedAt = SystemClock.elapsedRealtime()
        // 窗口内不预热：bind 会把守卫正想抑制的客户端叫醒
        guardUntil = maxOf(guardUntil, startedAt + GUARD_WINDOW_MS)
        val token = ++offGuardToken
        var attempts = 0
        var interval = GUARD_FIRST_MS
        // 守卫真正开始观察的时刻：主链还没收尾时只是在等，不该消耗观察窗口
        var windowStart = 0L
        // 每次重新关闭都推进一遍代次：让上一条链里排队的复查、以及 turnOff 排的预热全部作废
        var chainGen = req.generation

        val tick = object : Runnable {
            override fun run() {
                if (token != offGuardToken) return
                val now = SystemClock.elapsedRealtime()

                // 主链还在跑（或兜底时长内仍认为自己没跑完）：守卫生来就是给它收尾的，不插队
                if (!EnginePolicy.guardMayAct(
                        busy = closeChainRunning,
                        busyForMs = now - closeChainStartedAt,
                        maxBusyMs = CLOSE_CHAIN_MAX_MS,
                    )
                ) {
                    handler.postDelayed(this, 500L)
                    return
                }

                if (windowStart == 0L) windowStart = now
                if (now - windowStart >= GUARD_WINDOW_MS) {
                    guardUntil = 0L
                    // 抑制结束，客户端可以变热了（等这一拍已经推后的预热会自己到点，这里只是提前）
                    maybePrewarm(ctx)
                    return
                }
                if (!closeChainValid(ctx, req.vpnPackage, chainGen)) return

                if (!hasVpnTransport(ctx, fresh = true)) {
                    interval = EnginePolicy.nextGuardInterval(interval)
                    handler.postDelayed(this, interval)
                    return
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
                    guardUntil = 0L
                    // 客户端会自己把 VPN 拉回来，这时候再 bind 一次只会让它更快连上
                    if (pendingPrewarm != null) {
                        pendingPrewarm = null
                        log(ctx, "prewarm cancelled: 客户端会自己重连，本轮不预热")
                    }
                    return
                }
                log(ctx, "guard: vpn came back -> re-off ($attempts/$MAX_GUARD_ATTEMPTS)")
                // 走完整关闭链（停止广播 → 磁贴 → 强停），而不是只点磁贴：
                // 磁贴是切换语义，连续点两次会把 VPN 又打开
                chainGen = ++offGeneration
                closeChainRunning = true
                closeChainGen = chainGen
                closeChainStartedAt = now
                // 不再接力下一个守卫：窗口还没走完，下一拍继续由当前这一条负责
                pendingGuard = null
                verifyOff(
                    ctx, req.vpnPackage, chainGen,
                    forceStopAllowed = chainForceStopAllowed &&
                        EnginePolicy.allowForceStopOnClose(
                            forceStopEnabled = cfg.forceStopVpn,
                            keepClientAlive = cfg.keepClientAlive,
                            directForceStop = cfg.forceStopImmediately,
                        ),
                )
                // 等关闭链跑完再查（广播 2s + 磁贴 2s），避免在 transport 还没刷新时重复动手
                interval = 3_500L
                handler.postDelayed(this, interval)
            }
        }
        handler.postDelayed(tick, interval)
    }

    /**
     * 主关闭链收尾：释放 busy 标志，需要的话把守卫接力起来。
     *
     * 只认当前代次：旧链被新关闭作废后仍然可能跑到这里，它不能把新链的 busy 标志清掉。
     */
    private fun endCloseChain(ctx: Context?, generation: Int) {
        if (generation != closeChainGen) return
        closeChainRunning = false
        closeChainStartedAt = 0L
        val request = pendingGuard
        if (request != null) pendingGuard = null
        if (ctx == null) return
        if (request != null && request.generation == generation) startOffGuard(ctx, request)
        // 守卫没开或不适用时，主链一结束就可以预热；开了守卫则要等窗口结束（见 maybePrewarm）
        maybePrewarm(ctx)
    }

    /**
     * 预热客户端进程，把「下次打开目标应用」的冷启动省掉。
     *
     * 由三处唤起：[turnOff] 排的第一拍、[endCloseChain] 主链收尾、守卫窗口结束/放弃。
     * 谁先满足条件谁执行，执行时清空 [pendingPrewarm]，其余的调用自然返回。
     *
     * 前置条件缺一不可：主链已收尾、守卫窗口已结束、隧道确实断开、进程确实不在了。
     * 预热会 bind 客户端，正好把它从后台叫醒——这对「保留进程」是目的，
     * 对「关闭后继续抑制」却是干扰，所以必须等抑制结束。
     */
    private fun maybePrewarm(ctx: Context) {
        val req = pendingPrewarm ?: return
        // 预热的有效性**不**跟「哪一代关闭链」绑。守卫每次重新关闭都会推进 offGeneration，
        // 按代次判定就一定会被它顶掉——日志 15:13:33 那条
        // 「prewarm skipped: 已重新连接或目标应用回到前台」其实正是被作废，
        // 结果是开了「保留客户端进程」也从来不预热。
        // 真正该判的是「现在还是不是那个该保持关闭的状态」。
        val cfgNow = ConfigRepo.get(ctx)
        if (!cfgNow.enabled || cfgNow.vpnPackage != req.vpnPackage) {
            pendingPrewarm = null
            log(ctx, "prewarm cancelled: 客户端或开关已变")
            return
        }
        if (userOff || automationPaused) {
            pendingPrewarm = null
            log(ctx, "prewarm skipped: 已停止自动控制")
            return
        }
        if (aliveTargets.isNotEmpty() || offAppForeground != null) {
            pendingPrewarm = null
            log(ctx, "prewarm skipped: 目标应用已回到前台，不用预热")
            return
        }
        // 主链还在跑：它收尾时会再叫一次，这里不插队
        if (closeChainRunning) return
        val now = SystemClock.elapsedRealtime()
        when (EnginePolicy.prewarmGate(
            chainRunning = false,
            now = now,
            guardUntil = guardUntil,
            notBefore = req.notBefore,
            hasTransport = hasVpnTransport(ctx),
            processAlive = isProcessAlive(ctx, req.vpnPackage),
            sinceForceStopMs = now - lastForceStopAt,
            forceStopGuardMs = PREWARM_AFTER_FORCE_STOP_MS,
        )) {
            // 主链还在跑（兜底分支）：它收尾时会再叫一次，这里不插队
            EnginePolicy.PrewarmGate.WAIT_CHAIN -> return
            // 守卫还在盯「别让它回来」：这时候 bind 等于把要抑制的进程叫醒
            EnginePolicy.PrewarmGate.WAIT_GUARD -> {
                handler.postDelayed({ maybePrewarm(ctx) }, guardUntil - now + 300L)
                return
            }
            EnginePolicy.PrewarmGate.WAIT_TIME -> {
                handler.postDelayed({ maybePrewarm(ctx) }, req.notBefore - now)
                return
            }
            // 隧道还在：这一轮不是「关掉了」的状态，预热会让它更难断
            EnginePolicy.PrewarmGate.SKIP_TRANSPORT -> {
                pendingPrewarm = null
                log(ctx, "prewarm skipped: 隧道还连着，这一轮不用预热")
                return
            }
            // 进程还活着就已经是热的，什么都不用做
            EnginePolicy.PrewarmGate.SKIP_ALIVE -> {
                pendingPrewarm = null
                log(ctx, "prewarm skipped: 客户端进程还活着，不用预热")
                return
            }
            // 刚强停过：bind 会把刚进入 stopped 状态的客户端拉回来，稍等一下再试，
            // 不能就此放弃——否则「强停过」的这一轮永远不会预热（关掉守卫时尤其明显）。
            EnginePolicy.PrewarmGate.SKIP_FORCE_STOP -> {
                val wait = (lastForceStopAt + PREWARM_AFTER_FORCE_STOP_MS - now + 300L)
                    .coerceAtLeast(300L)
                log(ctx, "prewarm: 刚强停过，${wait}ms 后再预热")
                handler.postDelayed({ maybePrewarm(ctx) }, wait)
                return
            }
            EnginePolicy.PrewarmGate.GO -> Unit
        }
        pendingPrewarm = null
        // 上一轮强停可能留下了 stopped 标记，先清掉再 bind，否则拉不起来
        val unstopped = Packages.unstop(req.vpnPackage)
        log(ctx, "prewarm: 开始预热 ${req.vpnPackage}（清除 stopped=$unstopped）")
        if (!ExternalControl.prewarm(ctx, req.vpnPackage, handler)) return
        handler.postDelayed({ verifyPrewarm(ctx, req) }, PREWARM_CHECK_MS)
    }

    /**
     * 预热复查。
     *
     * 有的客户端在进程被唤醒时就把上次的连接恢复了（onCreate / onBind 里直接 reconnect），
     * bind 一下等于替它把 VPN 又打开了。它做不到「进程热着、隧道断开」这个状态，
     * 那就再关一次，并记住以后不给它预热，免得每次关闭都多一轮开→关。
     */
    private fun verifyPrewarm(ctx: Context, req: PrewarmRequest) {
        val cfg = ConfigRepo.get(ctx)
        if (!cfg.enabled || cfg.vpnPackage != req.vpnPackage) return
        // 用户已经重新打开目标应用：这时隧道起来是正常的连接请求，不是「预热把它弄醒了」
        if (aliveTargets.isNotEmpty() || offAppForeground != null) return
        if (!hasVpnTransport(ctx, fresh = true)) return
        prewarmUnsupportedPkg = req.vpnPackage
        log(ctx, "prewarm: 客户端一被唤醒就自己连上了，以后不再预热，并再关一次")
        val generation = ++offGeneration
        closeChainRunning = true
        closeChainGen = generation
        closeChainStartedAt = SystemClock.elapsedRealtime()
        verifyOff(
            ctx, req.vpnPackage, generation,
            forceStopAllowed = chainForceStopAllowed &&
                EnginePolicy.allowForceStopOnClose(
                    forceStopEnabled = cfg.forceStopVpn,
                    keepClientAlive = cfg.keepClientAlive,
                    directForceStop = cfg.forceStopImmediately,
                ),
        )
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
                if (!hasVpnTransport(ctx, fresh = true)) {
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
        if (!hasVpnTransport(ctx, fresh = true)) {
            noteVpnDown(ctx)
            log(ctx, "verify: vpn is down")
            endCloseChain(ctx, generation)
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
                    endCloseChain(ctx, generation)
                } else {
                    verifyOffStep(ctx, expectedPackage, generation, false, plan)
                }
            }
            return
        }

        // 3) 快捷磁贴。磁贴是切换语义：主链点完还没生效，关后守卫再点一次就把它又打开了，
        //    所以这里的第二次点击必须挡掉，宁可直接进强停。
        val now = SystemClock.elapsedRealtime()
        val tileAllowed = EnginePolicy.tileToggleAllowed(
            now = now,
            lastToggleAt = lastTileToggleAt,
            guardMs = TILE_TOGGLE_GUARD_MS,
        )
        if (EnginePolicy.StopStep.TILE in plan && !tileAllowed) {
            log(
                ctx,
                "verify: tile toggled ${now - lastTileToggleAt}ms ago, " +
                    "skip the second toggle (would turn the vpn back on)",
            )
        }
        val tile = if (EnginePolicy.StopStep.TILE in plan && tileAllowed) {
            QuickTiles.find(ctx, expectedPackage)
        } else {
            null
        }
        if (tile != null) {
            if (QuickTiles.click(ctx, tile)) {
                lastTileToggleAt = now
                // 磁贴生效通常是即时的，1.2 秒还没断就说明这次点击没被采纳，
                // 再等下去只是白拖关闭时间（原来 2 秒，加上前面的广播就是 3 秒）
                awaitDown(ctx, expectedPackage, generation, 1_200L) { down ->
                    if (down) {
                        noteVpnDown(ctx)
                        log(ctx, "verify: vpn is down (qs tile)")
                        endCloseChain(ctx, generation)
                    } else {
                        finishVerifyOff(ctx, expectedPackage, generation, 0, plan)
                    }
                }
                return
            }
            // 磁贴存在但点不动（ROM 限制 / 磁贴未启用）：继续走后面的手段，但留个记录，
            // 否则日志里只会看到「温和手段没关掉」，查不出是哪一步失效
            log(ctx, "verify: 快捷磁贴点击未生效，继续下一种手段")
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
        if (!hasVpnTransport(ctx, fresh = true)) {
            noteVpnDown(ctx)
            log(
                ctx,
                if (attempt == 0) "verify: vpn is down" else "verify: disconnected after force stop"
            )
            endCloseChain(ctx, generation)
            return
        }

        // 强停之前，先让客户端自己把 VpnService 停掉。onDestroy 里它会走自己的
        // 断流与清理，比杀进程温和；更要紧的是——进程被杀而 VPN 会话还登记在
        // system_server 时，会话会立刻被重新 bind，进程原地复活
        // （日志 15:34:05「still up after force stop, app alive=true」就是这个来回）。
        if (attempt == 0 && !stopServiceTried && expectedPackage.isNotEmpty()) {
            stopServiceTried = true
            if (ExternalControl.stopVpnService(ctx, expectedPackage)) {
                log(ctx, "verify: 先停客户端 VpnService，等它自己断开")
                handler.postDelayed({
                    if (!closeChainValid(ctx, expectedPackage, generation)) return@postDelayed
                    if (!hasVpnTransport(ctx, fresh = true)) {
                        noteVpnDown(ctx)
                        log(ctx, "verify: vpn is down (VpnService stopped)")
                        endCloseChain(ctx, generation)
                    } else {
                        finishVerifyOff(ctx, expectedPackage, generation, attempt, plan)
                    }
                }, STOP_SERVICE_WAIT_MS)
                return
            }
        }

        if (EnginePolicy.StopStep.FORCE_STOP !in plan ||
            !cfg.forceStopVpn || expectedPackage.isEmpty()
        ) {
            // 「保留客户端进程」时上面只跑了温和手段；确实关不掉才允许强停一次，
            // 宁可让进程被杀，也不能出现「点了断开、VPN 还连着」。
            // 用户手动连的那条例外：那是他自己的连接，杀掉客户端属于越权。
            if (cfg.keepClientAlive && cfg.forceStopVpn && expectedPackage.isNotEmpty() &&
                chainForceStopAllowed
            ) {
                log(ctx, "verify: 温和手段没关掉，改为强制停止（稍后会重新预热进程）")
                finishVerifyOff(
                    ctx, expectedPackage, generation, attempt,
                    listOf(EnginePolicy.StopStep.FORCE_STOP),
                )
                return
            }
            log(ctx, "verify: vpn still up, no further step allowed")
            endCloseChain(ctx, generation)
            return
        }
        if (attempt >= MAX_FORCE_STOP_ATTEMPTS) {
            log(ctx, "verify: still up after $attempt force stops, giving up")
            RuntimeDiagnostics.publishFailure(ctx, "stop", expectedPackage, attempt)
            endCloseChain(ctx, generation)
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
        if (!ok) {
            endCloseChain(ctx, generation)
            return
        }
        lastForceStopAt = SystemClock.elapsedRealtime()
        // 这里**不要**立刻 unstop：stopped 标记会阻止系统再次 bind 它的 VpnService，
        // 正是我们想要的「关掉就真的关掉」。下次需要启动时 ensureVpn 会再清除。
        Packages.forget(expectedPackage)
        // 下面这 6 秒是关键：force-stop 不拦 bind，Vpn 的 ServiceConnection 或客户端自己的
        // 前台服务会立刻把它拉回来（日志 15:50:35 → 15:50:38 的 came back，以及那个
        // 「正在启动服务」提示都出在这里）。窗口内让 ActiveServices 的入口直接拒绝启动。
        ClientStartGuard.suppress(
            expectedPackage,
            SystemClock.elapsedRealtime(),
            CLIENT_START_GUARD_MS,
        )
        log(
            ctx,
            "force stopped, stopped-state kept (will clear before next start), " +
                "client starts blocked for ${CLIENT_START_GUARD_MS}ms",
        )

        handler.postDelayed({
            if (!closeChainValid(ctx, expectedPackage, generation)) return@postDelayed
            if (!hasVpnTransport(ctx, fresh = true)) {
                // 这里以前只打了日志、没走 noteVpnDown，「VPN 已关闭」就永远弹不出来：
                // 强停这条路是绝大多数关闭的终点（17:49:58、17:53:37 都是），
                // 结果只有走磁贴 / 广播成功的少数情况才有提示。
                noteVpnDown(ctx)
                log(ctx, "verify: disconnected after force stop")
                endCloseChain(ctx, generation)
            } else {
                // 强停后隧道还在：多半是 VPN 会话没拆完，system_server 又把客户端的
                // VpnService bind 了起来（进程原地复活）。不是「没杀掉」，只能再停一次。
                val alive = isProcessAlive(ctx, expectedPackage)
                log(
                    ctx,
                    "verify: 强停后隧道还在，" +
                        if (alive) "客户端已被系统重新拉起 -> 再停一次"
                        else "进程已结束 -> 再停一次",
                )
                finishVerifyOff(ctx, expectedPackage, generation, attempt + 1, plan)
            }
        }, FORCE_STOP_RECHECK_MS + attempt * 400L)
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

    /**
     * 当前有没有 VPN 隧道。
     *
     * 默认带 400ms 记忆：一拍看门狗里 `logForeground` / `ensureVpn` / 结尾复查会各问一次，
     * 每次都遍历 allNetworks 再逐个跨进程取 capabilities，纯属重复劳动。
     * 关闭链里判断「到底断开没有」的地方传 `fresh = true`，宁可多查一次也不能看旧值。
     */
    private fun hasVpnTransport(ctx: Context, fresh: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!fresh && now - transportAt in 0..TRANSPORT_MEMO_MS) return transportValue
        val value = try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (t: Throwable) {
            true
        }
        transportAt = now
        transportValue = value
        return value
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
            val cfg = ConfigRepo.getOrNull(ctx)
            if (cfg == null) {
                // 配置读不到就先不动：下面第一段判断会把空配置当成「模块已停用」，
                // 直接把用户正在用的 VPN 关掉，而且连一句日志都不留
                noteConfigMissing(ctx, "watchdog")
                handler.postDelayed(this, RECONCILE_INTERVAL_MS)
                return
            }

            // 这一拍只读一次常驻状态：原来 1~3 个分支各读一遍 Settings.Secure
            val active = VpnSwitcher.isActive(ctx.contentResolver)
            // 进程列表同理：一次 tick 里最多用到两处，原来各构造一遍
            var procs: List<ActivityManager.RunningAppProcessInfo>? = null
            var procsLoaded = false
            fun processes(): List<ActivityManager.RunningAppProcessInfo>? {
                if (!procsLoaded) {
                    procs = runningProcesses(ctx)
                    procsLoaded = true
                }
                return procs
            }

            if (!cfg.enabled || cfg.vpnPackage.isEmpty()) {
                if (active) turnOff("module disabled")
                aliveTargets.clear()
                watchdogRunning = false
                return
            }

            if (automationPaused) {
                watchdogRunning = false
                return
            }

            // 锁屏断开后不再对账：目标进程还在，但用户就是希望它保持断开
            if (screenOffActive) {
                watchdogRunning = false
                return
            }

            if (wifiSuppressed) {
                if (active) turnOff("wifi off")
                watchdogRunning = false
                return
            }

            if (aliveTargets.isEmpty()) {
                // 兜底：目标进程都没了，VPN 却还开着（例如进程死亡事件被系统吞了），
                // 只要这轮是模块拉起的、且没有「打开即关闭」的应用在压制，就直接关掉。
                if (autoSession && offAppForeground == null && (active || hasVpnTransport(ctx))) {
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
                if (isProcessAlive(ctx, suppressor, processes())) {
                    watchdogRunning = false
                    return
                }
                offAppForeground = null
                log(ctx, "$suppressor is gone, auto vpn available again")
            }

            if (!isNetworkAllowed(cfg)) {
                if (active) {
                    log(ctx, "reconcile: network type disabled -> off")
                    turnOff("network type disabled")
                    // turnOff 已经停掉并移除了本看门狗，不能再把自己排回去
                    return
                }
                handler.postDelayed(this, RECONCILE_INTERVAL_MS)
                return
            }

            if (!EnginePolicy.shouldEnsureForProcessState(anyTargetAlive(ctx, processes()))) {
                log(ctx, "reconcile: target process gone -> off")
                aliveTargets.clear()
                scheduleOff(ctx, "watchdog")
                watchdogRunning = false
            } else {
                if (!active && !ExternalControl.isKnown(ctx, cfg.vpnPackage)) {
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
        // 新一轮连接从快节奏开始：上一轮可能停在 5 秒的慢拍上（计数没清）
        connectingTicks = 0
        // 首拍用连接窗口的短间隔：刚发起连接就复核一次，不用干等 5 秒
        handler.postDelayed(watchdog, RECONCILE_CONNECTING_MS)
    }

    private fun stopWatchdog() {
        watchdogRunning = false
        connectingTicks = 0
        handler.removeCallbacks(watchdog)
    }

    /** 读一次进程列表。看门狗一拍里会用到两处，由调用方复用同一份快照 */
    private fun runningProcesses(ctx: Context): List<ActivityManager.RunningAppProcessInfo>? {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        return try {
            am.runningAppProcesses
        } catch (t: Throwable) {
            null
        }
    }

    private fun isProcessAlive(
        ctx: Context,
        pkg: String,
        snapshot: List<ActivityManager.RunningAppProcessInfo>? = null,
    ): Boolean {
        val processes = snapshot ?: runningProcesses(ctx) ?: return false
        return processes.any { it.processName == pkg || it.processName.startsWith("$pkg:") }
    }

    /**
     * @return true 还在运行；false 已经结束；null 无法判断（列表被系统裁剪时）
     */
    private fun anyTargetAlive(
        ctx: Context,
        snapshot: List<ActivityManager.RunningAppProcessInfo>? = null,
    ): Boolean? {
        val processes = snapshot ?: runningProcesses(ctx) ?: return null

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
