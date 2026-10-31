package com.vpnautoswitch.module.system

/**
 * 关闭 VPN 客户端之后的一小段「不许它自己再起来」的窗口。
 *
 * 为什么需要：`forceStopPackage` 只禁止隐式广播和 Activity 的自启动，**不禁止别人 bind 它的服务**。
 * VPN 客户端断开隧道的那一瞬间，system_server 里的 Vpn 还持有它的 ServiceConnection，
 * 或者它自己的前台服务被 START_STICKY 重新拉起来——进程被杀后一秒内原地复活，
 * 隧道跟着重建。表现就是：
 *  - 日志 `force stopped` 之后紧接 `guard: vpn came back -> re-off`，同一次退出要强停两遍；
 *  - MIUI 在 bind 这个刚被强停的包时弹「正在启动服务」。
 *
 * 这个窗口不是靠改设置（清 always-on）解决的，而是直接拦住那次 bind / startService：
 * [com.vpnautoswitch.module.ModuleEntry] 在 `ActiveServices` 的入口检查本类，
 * 窗口内对目标包直接返回失败。窗口只有几秒，用户主动打开客户端会立刻解除（见
 * [Engine.onAppLaunching] / [Engine.ensureVpn]），不影响手动使用。
 *
 * 状态放这里而不是 Engine：hook 在 system_server 的 Binder 线程上跑，
 * Engine 在自有工作线程上写，中间隔着一层，用一个只含两个字段的对象最省事。
 */
internal object ClientStartGuard {

    @Volatile
    private var target: String? = null

    /** 用 elapsedRealtime 计，避免改系统时间把窗口拉长或抹掉 */
    private var expiresAt = 0L

    /** 开始抑制 [pkg] 的自启动，[durationMs] 后自动失效 */
    fun suppress(pkg: String, now: Long, durationMs: Long) {
        if (pkg.isEmpty() || durationMs <= 0L) return
        synchronized(this) {
            target = pkg
            expiresAt = now + durationMs
        }
    }

    /** 解除抑制。用户主动打开客户端、或模块自己要启动它时必须调用 */
    fun release() {
        synchronized(this) {
            target = null
            expiresAt = 0L
        }
    }

    /**
     * 这次 bind / startService 该不该被拦。
     *
     * 绝大多数调用会在第一行就返回 false（`target` 为空），所以这里不做额外分配。
     */
    fun blocked(pkg: String?, now: Long): Boolean {
        val expected = target ?: return false
        if (pkg == null || pkg != expected) return false
        synchronized(this) {
            // 双重检查：可能刚被 release 或换成了别的包
            if (target != expected) return false
            if (now < expiresAt) return true
            target = null
            expiresAt = 0L
            return false
        }
    }

    /** 当前是否处在抑制窗口内（只用于日志与诊断） */
    fun active(now: Long): Boolean {
        val expected = target ?: return false
        return expected.isNotEmpty() && now < expiresAt
    }
}
