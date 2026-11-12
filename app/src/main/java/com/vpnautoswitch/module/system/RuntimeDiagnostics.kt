package com.vpnautoswitch.module.system

import android.content.Context
import android.provider.Settings
import com.vpnautoswitch.module.BuildConfig
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject

/**
 * 只读诊断快照。system_server 是唯一知道“当前命中了哪条规则”的进程，
 * App 通过 Settings.Secure 读取这些值；这里写入失败绝不能影响 VPN 控制。
 */
internal object RuntimeDiagnostics {

    const val RUNTIME_KEY = "vpn_autoswitch_runtime"
    const val FAILURE_KEY = "vpn_autoswitch_last_failure"
    const val CLIENT_TEST_KEY = "vpn_autoswitch_client_test"

    /** 最近一次开启 / 关闭花了多久，用来回答「为什么这次这么慢」 */
    const val TIMING_KEY = "vpn_autoswitch_timing"

    /** system_server 加载时各类 hook 实际挂上的方法数（只写一次） */
    const val HOOKS_KEY = "vpn_autoswitch_hooks"

    /** 模块当前这份配置从哪来、镜像写了多久 */
    const val CONFIG_SOURCE_KEY = "vpn_autoswitch_config_source"

    private const val SCHEMA = 1
    private const val TAG = "VpnAutoSwitch"

    fun publishRuntime(
        ctx: Context,
        activeRule: String,
        detail: String,
        transport: Int,
        foreground: String? = null,
        /** 规则要求连上却没连上时，当前卡在哪一步；空表示没有可说的 */
        blocked: String = "",
        /** 上面那句话是不是必须用户动手才能过（决定界面标不标黄） */
        blockNeedsUser: Boolean = false,
        /** 客户端就绪状态（READY / STOPPED / DISABLED …），供诊断单独取用 */
        readiness: String = "",
        /** 本轮已经发出的启动请求次数 */
        attempt: Int = 0,
        /** 判定链：命中前已经排除掉的条件，形如「已排除：暂停 · Wi-Fi关」 */
        trace: String = "",
        /** 这个客户端是否成功连上过；用来区分「没授权」和「这次没拉起来」 */
        everConnected: Boolean = false,
    ) {
        try {
            val json = JSONObject()
                .put("schema", SCHEMA)
                .put("updatedAt", System.currentTimeMillis())
                .put("activeRule", activeRule)
                .put("detail", detail)
                .put("transport", transport)
                .put("foreground", foreground.orEmpty())
                .put("blocked", blocked)
                .put("blockNeedsUser", blockNeedsUser)
                .put("readiness", readiness)
                .put("attempt", attempt)
                .put("trace", trace)
                .put("everConnected", everConnected)
            Settings.Secure.putString(ctx.contentResolver, RUNTIME_KEY, json.toString())
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] runtime snapshot failed: $t")
        }
    }

    /**
     * @param cause 归因枚举名（见 [FailureCause]），App 侧要按它决定给什么按钮
     * @param hint 一句人话的归因与下一步；调用方有更具体的说法时会覆盖自动生成的那句
     */
    fun publishFailure(
        ctx: Context,
        stage: String,
        vpnPackage: String,
        failureStreak: Int,
        cause: String = "",
        hint: String = "",
    ) {
        try {
            val json = JSONObject()
                .put("schema", SCHEMA)
                .put("at", System.currentTimeMillis())
                .put("stage", stage)
                .put("vpnPackage", vpnPackage)
                .put("failureStreak", failureStreak)
                .put("cause", cause)
                .put("hint", hint)
            Settings.Secure.putString(ctx.contentResolver, FAILURE_KEY, json.toString())
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] failure snapshot failed: $t")
        }
    }

    /**
     * 记录最近一次「开启」或「关闭」的耗时。
     *
     * [kind] 取 "on" / "off"，读改写同一个 JSON，两个方向互不覆盖——
     * 状态诊断里就能直接看到「上次开启 1.2 秒 / 上次关闭 0.7 秒」，
     * 慢的时候不用再靠猜是客户端建隧道慢还是模块在重试。
     */
    fun publishTiming(ctx: Context, kind: String, costMs: Long) {
        try {
            val prev = try {
                Settings.Secure.getString(ctx.contentResolver, TIMING_KEY)
                    ?.let { JSONObject(it) }
            } catch (t: Throwable) {
                null
            }
            val json = prev ?: JSONObject().put("schema", SCHEMA)
            json.put("${kind}Ms", costMs.coerceAtLeast(0L))
            json.put("${kind}At", System.currentTimeMillis())
            Settings.Secure.putString(ctx.contentResolver, TIMING_KEY, json.toString())
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] timing snapshot failed: $t")
        }
    }

    /**
     * hook 挂载结果只在 system_server 启动时产生一次，之后不会变。
     * 带上版本号：App 侧看到版本对不上就知道这是重启前的旧快照，不能拿来下结论。
     */
    /**
     * 写 hook 快照，返回是否真的写进去了。
     *
     * 要能重试：注入 system_server 时 AMS 可能还没注册，`Settings.Secure` 读写会抛
     * NPE —— 和 EngineBoot 里 onBoot 每 2 秒重试是同一个原因。以前这里把异常吞掉、
     * 只打一行 XposedBridge 日志，快照就永远空着，自检里「Hook 挂载」一直显示未知。
     */
    fun publishHooks(ctx: Context?, counts: HookCounts): Boolean {
        if (ctx == null) return false
        return try {
            val json = JSONObject()
                .put("schema", SCHEMA)
                .put("at", System.currentTimeMillis())
                .put("version", BuildConfig.VERSION_CODE)
            counts.asMap().forEach { (name, count) -> json.put(name, count) }
            Settings.Secure.putString(ctx.contentResolver, HOOKS_KEY, json.toString())
            true
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] hook snapshot failed: $t")
            try {
                ModuleLog.append(ctx, "hook snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
            } catch (_: Throwable) {
                // 同一个通道刚失败过，再写一次多半也失败，别把调用方带崩
            }
            false
        }
    }

    /**
     * 配置来源变化时写一次：[source] 为 provider / mirror / none，
     * [mirrorAt] 是镜像原文里的写入时刻（source 不是 mirror 时为 0）。
     */
    fun publishConfigSource(ctx: Context?, source: String, mirrorAt: Long) {
        if (ctx == null) return
        try {
            val json = JSONObject()
                .put("schema", SCHEMA)
                .put("at", System.currentTimeMillis())
                .put("source", source)
                .put("mirrorAt", mirrorAt)
            Settings.Secure.putString(ctx.contentResolver, CONFIG_SOURCE_KEY, json.toString())
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] config source snapshot failed: $t")
        }
    }

    fun publishClientTest(
        ctx: Context,
        vpnPackage: String,
        mode: String,
        status: String,
        detail: String,
    ) {
        try {
            val json = JSONObject()
                .put("schema", SCHEMA)
                .put("at", System.currentTimeMillis())
                .put("vpnPackage", vpnPackage)
                .put("mode", mode)
                .put("status", status)
                .put("detail", detail)
            Settings.Secure.putString(ctx.contentResolver, CLIENT_TEST_KEY, json.toString())
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] client test snapshot failed: $t")
        }
    }
}