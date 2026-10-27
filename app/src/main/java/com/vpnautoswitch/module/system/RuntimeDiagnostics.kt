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
