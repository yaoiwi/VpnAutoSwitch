package com.vpnautoswitch.module.system

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.vpnautoswitch.module.Config
import com.vpnautoswitch.module.ConfigProvider
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject

/**
 * system_server 侧的配置缓存。查询 provider 是跨进程调用，不能放在 AMS 的锁里做，
 * 因此只在后台工作线程调用，并缓存 3 秒。
 *
 * 每次成功读到配置都会往 Settings.Secure 写一份镜像：App 进程起不来的时候
 * （刚装完还没点开、被 ROM 的自启动管理拦住），provider 查询会失败，
 * 这时候退回镜像总比把「读不到」当成「没配置」好。
 */
internal object ConfigRepo {

    private const val TAG = "VpnAutoSwitch"
    private const val CACHE_MS = 3_000L

    /** provider 打不开时，隔这么久再试一次，别一直用镜像 */
    private const val MIRROR_RETRY_MS = 2_000L

    @Volatile
    private var cached: Config? = null

    @Volatile
    private var cachedAt = 0L

    /** 上一次写进 Secure 的镜像原文，避免每次心跳都重复写 */
    @Volatile
    private var mirrored: String? = null

    /** 最近一次配置是从哪来的：provider / mirror / none，只用于日志与诊断 */
    @Volatile
    private var source: String = "none"

    fun lastSource(): String = source

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
            source = "provider"
            mirror(ctx, fresh)
            return fresh
        }

        // provider 不可用：先用内存里的，再退到上次写下的镜像
        val fallback = snapshot ?: readMirror(ctx)
        if (fallback != null) {
            cached = fallback
            // 比正常缓存短一截：隔 2 秒再问候一次 provider，一旦 App 起来了就换成真实值
            cachedAt = now - CACHE_MS + MIRROR_RETRY_MS
            if (snapshot == null) source = "mirror"
            return fallback
        }

        source = "none"
        return null
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

    /** 把成功读到的配置写一份到 Secure 设置，供下次 provider 不可用时兜底 */
    private fun mirror(ctx: Context?, config: Config) {
        if (ctx == null) return
        val json = config.toJson()
        if (json == mirrored) return
        try {
            val payload = JSONObject()
                .put("at", System.currentTimeMillis())
                .put("config", JSONObject(json))
            Settings.Secure.putString(
                ctx.contentResolver,
                ConfigCachePolicy.MIRROR_KEY,
                payload.toString(),
            )
            mirrored = json
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] config mirror write failed: $t")
        }
    }

    private fun readMirror(ctx: Context?): Config? {
        if (ctx == null) return null
        return try {
            val raw = Settings.Secure.getString(
                ctx.contentResolver,
                ConfigCachePolicy.MIRROR_KEY,
            )
            if (raw.isNullOrEmpty()) return null
            val payload = JSONObject(raw)
            val savedAt = payload.optLong("at", 0L)
            if (!ConfigCachePolicy.isFresh(savedAt, System.currentTimeMillis())) {
                XposedBridge.log("[$TAG] config mirror expired, ignored")
                return null
            }
            val config = Config.parse(payload.optJSONObject("config")?.toString())
            if (!ConfigCachePolicy.isUsable(config)) return null
            mirrored = config?.toJson()
            config
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] config mirror read failed: $t")
            null
        }
    }

    fun reset() {
        cached = null
        cachedAt = 0L
    }
}
