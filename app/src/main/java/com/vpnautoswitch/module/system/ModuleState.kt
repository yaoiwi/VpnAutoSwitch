package com.vpnautoswitch.module.system

import android.content.Context
import android.provider.Settings

/**
 * 模块自己的「跑出来的结论」，和配置镜像一样放在 Settings.Secure 里。
 *
 * 这些东西重启后不该忘——它们描述的是客户端本身的行为，不是一次会话的临时状态：
 *
 *  - **预热不起来的客户端**：`bind` 它只会白费一次，而且很多客户端一被唤醒就自恢复连接，
 *    于是日志里出现「刚关掉，1 秒后进程已就绪，VPN 又回来了」。以前存在内存变量里，
 *    重启手机就丢，第一条日志又是盲 bind 一次。
 *  - **不响应 STOP 广播的客户端**：每次关闭都要白等 700~1200 毫秒才进下一步。记下来，
 *    连续两次没反应就直接跳过，后续还有 stopService / 磁贴 / 强停兜底。
 */
internal object ModuleState {

    private const val PREWARM_KEY = "vpn_autoswitch_prewarm_unsupported"
    private const val BROADCAST_KEY = "vpn_autoswitch_broadcast_ineffective"

    /** 广播结论的有效期：客户端升级后可能就支持了，别记一辈子 */
    const val BROADCAST_MEMO_TTL_MS = 7L * 24 * 60 * 60 * 1000

    // ------------------------------------------------------------ 预热黑名单

    fun prewarmUnsupported(ctx: Context?): String? =
        read(ctx, PREWARM_KEY)?.takeIf { it.isNotBlank() }

    fun setPrewarmUnsupported(ctx: Context?, pkg: String?) {
        if (pkg.isNullOrBlank()) remove(ctx, PREWARM_KEY) else write(ctx, PREWARM_KEY, pkg)
    }

    // ------------------------------------------------------ 广播有效性记忆

    /** 已记录的连续失败次数；换了包或过期都算 0 */
    fun broadcastFailures(ctx: Context?, pkg: String): Int {
        if (pkg.isEmpty()) return 0
        val parts = read(ctx, BROADCAST_KEY)?.split('|') ?: return 0
        if (parts.size != 3 || parts[0] != pkg) return 0
        val at = parts[2].toLongOrNull() ?: return 0
        if (System.currentTimeMillis() - at !in 0..BROADCAST_MEMO_TTL_MS) return 0
        return parts[1].toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    /**
     * 记一次广播的结果。成功就把结论清掉——客户端可能升了级，重新给它机会。
     */
    fun noteBroadcastResult(ctx: Context?, pkg: String, worked: Boolean) {
        if (pkg.isEmpty()) return
        if (worked) {
            remove(ctx, BROADCAST_KEY)
            return
        }
        val failures = broadcastFailures(ctx, pkg) + 1
        write(ctx, BROADCAST_KEY, "$pkg|$failures|${System.currentTimeMillis()}")
    }

    // ---------------------------------------------------------------- 存储

    private fun read(ctx: Context?, key: String): String? {
        if (ctx == null) return null
        return try {
            Settings.Secure.getString(ctx.contentResolver, key)
        } catch (t: Throwable) {
            null
        }
    }

    private fun write(ctx: Context?, key: String, value: String) {
        if (ctx == null) return
        try {
            Settings.Secure.putString(ctx.contentResolver, key, value)
        } catch (t: Throwable) {
            // 写不进去只影响「下次还得再试一遍」，不影响主流程
        }
    }

    private fun remove(ctx: Context?, key: String) {
        if (ctx == null) return
        try {
            Settings.Secure.putString(ctx.contentResolver, key, null)
        } catch (t: Throwable) {
            // 同上
        }
    }
}
