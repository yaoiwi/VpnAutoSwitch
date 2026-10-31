package com.vpnautoswitch.module.system

import android.content.Context
import android.provider.Settings
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

    private const val SCHEMA = 1
    private const val TAG = "VpnAutoSwitch"

    fun publishRuntime(
        ctx: Context,
        activeRule: String,
        detail: String,
        transport: Int,
        foreground: String? = null,
    ) {
        try {
            val json = JSONObject()
                .put("schema", SCHEMA)
                .put("updatedAt", System.currentTimeMillis())
                .put("activeRule", activeRule)
                .put("detail", detail)
                .put("transport", transport)
                .put("foreground", foreground.orEmpty())
            Settings.Secure.putString(ctx.contentResolver, RUNTIME_KEY, json.toString())
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] runtime snapshot failed: $t")
        }
    }

    fun publishFailure(ctx: Context, stage: String, vpnPackage: String, failureStreak: Int) {
        try {
            val json = JSONObject()
                .put("schema", SCHEMA)
                .put("at", System.currentTimeMillis())
                .put("stage", stage)
                .put("vpnPackage", vpnPackage)
                .put("failureStreak", failureStreak)
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