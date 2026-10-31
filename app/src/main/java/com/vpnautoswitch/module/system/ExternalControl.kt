package com.vpnautoswitch.module.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    private val unsupportedStart = HashSet<String>()
    private val unsupportedStop = HashSet<String>()
    private val noVpnService = HashSet<String>()
    private var cachedKnown: MutableSet<String>? = null

    /**
     * 已确认的启动方式："activity"（客户端导出的外部控制界面）或 "service"（直接启 VpnService）。
     * 只有**确实靠外部手段连上**过才会记录，避免把 always-on 能连上的客户端误判成外部控制。
     */
    fun modeOf(ctx: Context, pkg: String): String? {
        if (pkg.isEmpty()) return null
        return known(ctx).firstOrNull { it.startsWith("$pkg|") }?.substringAfter('|')
    }

    fun isKnown(ctx: Context, pkg: String): Boolean = modeOf(ctx, pkg) != null

    /** App 进程里可能缓存了旧值；界面刷新时强制重新读 Settings。 */
    fun modeOfFresh(ctx: Context, pkg: String): String? {
        cachedKnown = null
        return modeOf(ctx, pkg)
    }

    fun remember(ctx: Context, pkg: String, mode: String) {
        if (pkg.isEmpty()) return
        val set = known(ctx)
        set.removeAll { it.startsWith("$pkg|") }
        set.add("$pkg|$mode")
        saveKnown(ctx, set)
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
        if (pkg.isEmpty() || pkg in noVpnService) return false
        val service = findVpnService(ctx, pkg) ?: run {
            noVpnService.add(pkg)
            return false
        }
        return try {
            ctx.startForegroundService(Intent().setComponent(ComponentName(pkg, service)))
            ModuleLog.append(ctx, "started VPN service $service")
            true
        } catch (t: Throwable) {
            noVpnService.add(pkg)
            ModuleLog.append(ctx, "start VPN service failed: $t")
            false
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
    fun prewarm(ctx: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val service = findVpnService(ctx, pkg)
        if (service == null) {
            ModuleLog.append(ctx, "prewarm: 没找到 $pkg 的 VpnService，跳过")
            return false
        }

        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val connected = java.util.concurrent.atomic.AtomicBoolean(false)
        var conn: ServiceConnection? = null
        val main = Handler(Looper.getMainLooper())
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
                // 万一 onServiceConnected 迟迟不来（服务卡住），3 秒后自己解绑
                main.postDelayed({
                    if (!connected.get()) {
                        // 进程被强停过又刚清标记时常见：绑定请求发出去了，客户端没起来
                        ModuleLog.append(ctx, "prewarm: 3 秒内没连上，放弃本次预热")
                    }
                    release()
                }, 3_000L)
                true
            }
        } catch (t: Throwable) {
            released.set(true)
            ModuleLog.append(ctx, "prewarm failed: $t")
            false
        }
    }

    /** 会短暂显示客户端界面：仅在用户明确允许时调用 */
    fun start(ctx: Context, pkg: String): Boolean {
        // 实测直接 startForegroundService 启动 TunService 时，服务起来了但隧道不建立
        // （拿不到应用自己的初始化上下文），所以优先走官方导出的外部控制入口。
        if (launch(ctx, pkg, "action.START_CLASH", unsupportedStart)) return true
        return startVpnService(ctx, pkg)
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
                ModuleLog.append(ctx, "$pkg 没有 $suffix 入口，继续使用 always-on")
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
