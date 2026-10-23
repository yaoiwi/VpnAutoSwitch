package com.vpnautoswitch.module.system

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.vpnautoswitch.module.Config
import com.vpnautoswitch.module.ConfigProvider
import de.robv.android.xposed.XposedBridge

/**
 * system_server 侧的配置缓存。查询 provider 是跨进程调用，不能放在 AMS 的锁里做，
 * 因此只在后台工作线程调用，并缓存 3 秒。
 */
internal object ConfigRepo {

    private const val TAG = "VpnAutoSwitch"
    private const val CACHE_MS = 3_000L

    @Volatile
    private var cached: Config? = null

    @Volatile
    private var cachedAt = 0L

    fun get(ctx: Context?): Config = getOrNull(ctx) ?: Config()

    /**
     * 和 [get] 一样，但「从来没查成功」时返回 null，而不是一个默认 Config()。
     *
     * 这点很关键：开机初期 App 进程还没起来，provider 查不到，
     * 如果拿默认值（restartSeq=0）当基线，等几秒后查到真实值（比如 1），
     * 就会被误判成「用户刚点了重启」→ 无限重启。
     */
    fun getOrNull(ctx: Context?): Config? {
        val now = SystemClock.elapsedRealtime()
        val snapshot = cached
        if (snapshot != null && now - cachedAt < CACHE_MS) return snapshot

        val fresh = query(ctx)
        if (fresh != null) {
            cached = fresh
            cachedAt = now
            return fresh
        }
        return snapshot
    }

    private fun query(ctx: Context?): Config? {
        if (ctx == null) return null
        return try {
            ctx.contentResolver.query(ConfigProvider.CONTENT_URI, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) Config.parse(c.getString(0)) else null
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] config query failed: $t")
            null
        }
    }

    fun reset() {
        cached = null
        cachedAt = 0L
    }
}
