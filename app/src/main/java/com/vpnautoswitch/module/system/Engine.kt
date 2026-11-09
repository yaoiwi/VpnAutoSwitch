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
import android.os.UserManager
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
 *
 * 这里只放常量、状态字段和两个常驻任务（心跳、看门狗）。具体逻辑按职责拆在同包的
 * 扩展文件里：EngineBoot（启动与心跳）、EngineDispatch（事件入口）、EngineNetwork（网络与 Wi-Fi）、
 * EngineScreen（锁屏断开）、EnginePause（暂停）、EngineClientTest（客户端测试）、
 * EngineRule（规则描述与快照）、EngineStart / EngineClose / EngineWatch / EngineEvents（控制链）。
 */
internal object Engine {

    internal const val TAG = "VpnAutoSwitch"
    const val HEARTBEAT_KEY = "vpn_autoswitch_heartbeat"

    /** 系统对 SSID 脱敏时返回的占位值（Android 13+ 无定位权限 / ROM 加固时常见） */

    /** 当前加载进 system_server 的模块版本号，App 用它判断「是不是旧代码」 */
    const val MODULE_VERSION_KEY = "vpn_autoswitch_module_version"

    /** 当前 Wi-Fi 名称，写给 App 显示（App 自己没有定位权限，读不到 SSID） */
    const val SSID_KEY = "vpn_autoswitch_ssid"

    /**
     * SSID 的读取结果：`ok` / `off`（没连 Wi-Fi）/ `masked`（连着但系统不给名字）。
     *
     * 只有 [SSID_KEY] 是空字符串时，分不清「没连 Wi-Fi」和「读取被系统拦了」，
     * 而这两种情况的排查方向完全相反，所以单独记一份。
     */
    const val SSID_STATE_KEY = "vpn_autoswitch_ssid_state"

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
     * 同一个包的「残留死亡」日志，这么久之内只记一条。
     *
     * 一次切应用里 AMS 会连着回调好几次（多进程 + 上一轮 ProcessRecord），09:10:33 那次
     * com.android.vending 一口气打了 10 行、com.github.android 4 行，把紧随其后的
     * `reconcile: target process gone -> off` 全埋了。这些行内容只差 pid，留第一条就够。
     */
    internal const val STALE_DEATH_LOG_MS = 1_000L

    /** 残留死亡日志的节流记录（包名 -> 上次打印时刻）；只在工作线程访问 */
    internal val staleDeathLogAt = HashMap<String, Long>()

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
     * 客户端自己掉线（进程还活着、目标应用还在前台）时的自动恢复次数。
     *
     * 只救两次：真断了两次还没稳住，多半是网络本身有问题，继续拉只会变成
     * 「一直重连、一直掉」的耗电循环，还给用户一种模块在跟他抢控制权的感觉。
     */
    internal const val MAX_CLIENT_DROP_RETRIES = 2

    /**
     * 走界面兜底之前，先给静默启动几次机会。
     *
     * FlClash 这类客户端的 VpnService 一启就能连（03:28:40 → 03:28:44），
     * 界面根本不需要；而只能靠界面的客户端，静默每次都要白等一个初始化宽限。
     * 所以次数由 [ExternalControl.silentFails] 记的历史动态决定，这里是上限。
     */
    internal const val SILENT_START_ATTEMPTS = 2

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

    /**
     * 设备还没解锁时，隔这么久再看一眼。
     *
     * 文件级加密（FBE）下，用户没解锁之前，App 里非 directBootAware 的组件根本起不来，
     * `startService` 会直接返回 null。日志里开机 49 秒那两次「同步服务起不来
     * （service=true, stopped=false, enabled=true）」就是撞在这上面——三种常见原因都排除了，
     * 剩下的只有「还没解锁」。这时每 2 秒重试纯属白撞，也不该在 16 秒后判定失败：
     * 用户往往过一会儿才解锁，解锁广播一到就能立刻接上。
     */
    internal const val USER_LOCKED_RETRY_MS = 30_000L

    /** 是否已经因为「设备未解锁」记过一条日志，避免每 30 秒重复 */
    internal var lockedWaitLogged = false

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

    /** 客户端自己掉线后的自动恢复次数；连上后清零，见 [MAX_CLIENT_DROP_RETRIES] */
    internal var clientDropRetries = 0

    internal var offToken = 0
    internal var startToken = 0

    /** 「任务被移除」判定成残留后排队的那次复查；换一个就作废上一个 */
    internal var taskRecheckToken = 0

    /**
     * 划掉任务被忽略时，每种原因上一次记日志的时刻。
     *
     * 用户反复上滑同一个应用会连着触发回调，不节流的话日志会被同一句话刷满。
     * key 是「包名/原因」，最多几十条，不需要清理。
     */
    internal val taskSkipLoggedAt = HashMap<String, Long>()

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

    /** 上一次写进 Settings 的 SSID 状态（ok/off/masked），用来跳过重复写入 */
    internal var ssidState: String? = null

    /** 当前 Wi-Fi 命中「指定 Wi-Fi 关 VPN」规则：期间不再自动开启 */
    internal var wifiSuppressed = false

    /** 当前默认网络的传输类型：0 未知 / 1 Wi-Fi / 2 移动数据 / 3 其他 */
    internal var lastTransport = 0
    internal var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 上一次主动探测网络的时间，避免切应用时连着查好几次 */
    internal var lastProbeAt = 0L
    internal var rebootReceiverRegistered = false
    internal var booted = false

    /** 上一次记「配置读不到」的时间，用于去重（参见 [noteConfigMissing]） */
    internal var lastConfigMissingAt = 0L

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
