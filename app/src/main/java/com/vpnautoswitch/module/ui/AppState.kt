package com.vpnautoswitch.module.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vpnautoswitch.module.BuildConfig
import com.vpnautoswitch.module.Config
import com.vpnautoswitch.module.ModuleContract
import com.vpnautoswitch.module.apps.AppInfo
import com.vpnautoswitch.module.apps.AppsRepository
import com.vpnautoswitch.module.system.Engine
import com.vpnautoswitch.module.system.ModuleLog
import com.vpnautoswitch.module.system.RuntimeDiagnostics
import org.json.JSONObject
import java.util.concurrent.Executors

sealed class Screen {
    data object Main : Screen()
    data object Picker : Screen()
}

/**
 * 系统侧当前状态。全部通过读取 Settings.Secure 获得（读操作不需要任何权限）。
 *
 * - heartbeat：system_server 里的 hook 写入的心跳时间戳，能读到就说明模块真的注入成功了
 * - alwaysOnApp：系统当前的「始终开启的 VPN」应用，等于我们配置的 VPN 就说明模块动作生效了
 * - takenByModule：本模块是否正处于「接管中」状态
 */
data class Diagnostics(
    val heartbeat: Long = 0L,
    val alwaysOnApp: String? = null,
    val takenByModule: Boolean = false,
    val network: String = "未知",
    val log: String = "",
    /** system_server 里实际加载的模块版本号；0 表示没读到（旧代码或未注入） */
    val moduleVersion: Int = 0,
    /** 当前 Wi-Fi 名称（模块写在 Settings 里，App 自己读不到 SSID） */
    val currentSsid: String = "",
    /** 配置的 VPN 客户端 */
    val vpnPackage: String = "",
    /** null 表示未配置或无法查询 */
    val vpnInstalled: Boolean? = null,
    val vpnEnabled: Boolean? = null,
    val vpnHasService: Boolean? = null,
    val vpnConnected: Boolean = false,
    /** 模块上报的当前命中规则与说明 */
    val activeRule: String = "",
    val ruleDetail: String = "",
    val ruleUpdatedAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    val lastFailureStage: String = "",
    val lastFailurePackage: String = "",
    val lastFailureStreak: Int = 0,
    val heartbeatFresh: Boolean = false,
    val moduleState: DiagnosticsPolicy.ModuleState = DiagnosticsPolicy.ModuleState.NEVER,
    /** 快捷磁贴暂停了自动联动 */
    val paused: Boolean = false,
) {
    companion object {
        fun load(context: Context, config: Config): Diagnostics {
            val cr = context.contentResolver
            val now = System.currentTimeMillis()
            val heartbeat = try {
                Settings.Secure.getLong(cr, "vpn_autoswitch_heartbeat", 0L)
            } catch (t: Throwable) {
                0L
            }
            val moduleVersion = try {
                Settings.Secure.getInt(cr, "vpn_autoswitch_module_version", 0)
            } catch (t: Throwable) {
                0
            }
            val runtime = readJson(cr, RuntimeDiagnostics.RUNTIME_KEY)
            val failure = readJson(cr, RuntimeDiagnostics.FAILURE_KEY)
            val inspection = AppsRepository.inspectVpn(context, config.vpnPackage)
            return Diagnostics(
                heartbeat = heartbeat,
                alwaysOnApp = try {
                    Settings.Secure.getString(cr, "always_on_vpn_app")
                } catch (t: Throwable) {
                    null
                },
                takenByModule = try {
                    Settings.Secure.getInt(cr, "vpn_autoswitch_active", 0) == 1
                } catch (t: Throwable) {
                    false
                },
                network = networkLabel(context),
                log = ModuleLog.read(context),
                moduleVersion = moduleVersion,
                currentSsid = try {
                    Settings.Secure.getString(cr, "vpn_autoswitch_ssid").orEmpty()
                } catch (t: Throwable) {
                    ""
                },
                vpnPackage = config.vpnPackage,
                vpnInstalled = inspection?.installed,
                vpnEnabled = inspection?.enabled,
                vpnHasService = inspection?.hasVpnService,
                vpnConnected = hasVpnTransport(context),
                activeRule = runtime?.optString("activeRule").orEmpty(),
                ruleDetail = runtime?.optString("detail").orEmpty(),
                ruleUpdatedAt = runtime?.optLong("updatedAt", 0L) ?: 0L,
                lastFailureAt = failure?.optLong("at", 0L) ?: 0L,
                lastFailureStage = failure?.optString("stage").orEmpty(),
                lastFailurePackage = failure?.optString("vpnPackage").orEmpty(),
                lastFailureStreak = failure?.optInt("failureStreak", 0) ?: 0,
                heartbeatFresh = DiagnosticsPolicy.isHeartbeatFresh(heartbeat, now),
                moduleState = DiagnosticsPolicy.moduleState(
                    heartbeat,
                    moduleVersion,
                    BuildConfig.VERSION_CODE,
                    now,
                ),
                paused = try {
                    Settings.Secure.getInt(cr, ModuleContract.PAUSED_KEY, 0) == 1
                } catch (t: Throwable) {
                    false
                },
            )
        }

        private fun readJson(
            cr: android.content.ContentResolver,
            key: String,
        ): JSONObject? = try {
            Settings.Secure.getString(cr, key)?.let { JSONObject(it) }
        } catch (t: Throwable) {
            null
        }

        private fun hasVpnTransport(context: Context): Boolean = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (t: Throwable) {
            false
        }

        private fun networkLabel(context: Context): String = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "无网络"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> {
                    // VPN 已连上时，看底层网络
                    val underlying = cm.allNetworks
                        .mapNotNull { cm.getNetworkCapabilities(it) }
                        .firstOrNull {
                            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                                (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                    it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                        }
                    transportLabel(underlying)
                }
                else -> transportLabel(caps)
            }
        } catch (t: Throwable) {
            "未知"
        }

        private fun transportLabel(caps: NetworkCapabilities?): String = when {
            caps == null -> "未知"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "其他"
        }
    }
}

class AppState(private val context: Context) {

    var screen by mutableStateOf<Screen>(Screen.Main)
    var config by mutableStateOf(Config.load(context))
        private set

    var apps by mutableStateOf(emptyList<AppInfo>())
    var appsLoading by mutableStateOf(false)
    var pickerMode by mutableIntStateOf(MODE_MULTI)
    var pickerSelection by mutableStateOf(setOf<String>())
    var diagnostics by mutableStateOf(Diagnostics.load(context, config))

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun save(next: Config) {
        config = next
        Config.save(context, next)
    }

    /**
     * 清空全部运行日志。
     *
     * 普通 App 没有 WRITE_SECURE_SETTINGS，直接写 Settings.Secure 会静默失败，
     * 所以请 system_server 里的模块代删，并先把本地界面清空。
     */
    fun clearLog() {
        try {
            context.sendBroadcast(Intent(Engine.ACTION_CLEAR_LOG).setPackage("android"))
        } catch (t: Throwable) {
            // 忽略，界面仍然先清空
        }
        diagnostics = diagnostics.copy(log = "")
    }

    fun refreshDiagnostics() {
        diagnostics = Diagnostics.load(context, config)
    }

    /** 与快捷磁贴相同的暂停开关；App 自己不能写 Settings.Secure，只能请模块代改。 */
    fun togglePause() {
        try {
            context.sendBroadcast(
                Intent(ModuleContract.ACTION_TOGGLE_PAUSE).setPackage("android")
            )
        } catch (t: Throwable) {
            // 忽略，模块没注入时不会有任何效果
        }
        mainHandler.postDelayed({ refreshDiagnostics() }, 500L)
    }

    fun loadApps(force: Boolean = false) {
        if (appsLoading) return
        if (apps.isNotEmpty() && !force) return
        appsLoading = true
        executor.execute {
            val withIcons = AppsRepository.loadIcons(context, AppsRepository.loadLaunchable(context))
            mainHandler.post {
                apps = withIcons
                appsLoading = false
            }
        }
    }

    fun openPicker(mode: Int) {
        pickerMode = mode
        pickerSelection = when (mode) {
            MODE_SINGLE -> listOfNotNull(config.vpnPackage.takeIf { it.isNotEmpty() }).toSet()
            MODE_OFF -> config.offTargets
            else -> config.targets
        }
        screen = Screen.Picker
        loadApps()
    }

    fun toggleSelection(packageName: String) {
        pickerSelection = if (pickerMode == MODE_SINGLE) {
            setOf(packageName)
        } else if (pickerSelection.contains(packageName)) {
            pickerSelection - packageName
        } else {
            pickerSelection + packageName
        }
    }

    fun confirmPicker() {
        save(
            when (pickerMode) {
                MODE_SINGLE -> config.copy(vpnPackage = pickerSelection.firstOrNull().orEmpty())
                MODE_OFF -> config.copy(offTargets = LinkedHashSet(pickerSelection))
                else -> config.copy(targets = LinkedHashSet(pickerSelection))
            }
        )
        screen = Screen.Main
    }

    fun closePicker() {
        screen = Screen.Main
    }

    /** 导出配置到 SAF 选定的文件；返回是否成功 */
    fun exportConfig(uri: Uri): Boolean = try {
        val json = JSONObject(config.toJson()).toString(2)
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.flush()
        }
        true
    } catch (t: Throwable) {
        false
    }

    /** 从 SAF 选定的文件导入配置；返回是否成功 */
    fun importConfig(uri: Uri): Boolean = try {
        val text = context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
        val parsed = Config.parse(text)
        if (parsed == null) {
            false
        } else {
            save(parsed)
            refreshDiagnostics()
            true
        }
    } catch (t: Throwable) {
        false
    }

    fun openLsposed(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage("org.lsposed.manager")
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    fun labelOf(packageName: String): String =
        apps.firstOrNull { it.packageName == packageName }?.label
            ?: AppsRepository.labelOf(context, packageName)

    companion object {
        const val MODE_MULTI = 0
        const val MODE_SINGLE = 1

        /** 选择「打开时关闭 VPN」的应用 */
        const val MODE_OFF = 2
    }
}
