package com.vpnautoswitch.module

import android.Manifest
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.vpnautoswitch.module.ui.App
import com.vpnautoswitch.module.ui.AppState
import com.vpnautoswitch.module.ui.Diagnostics
import com.vpnautoswitch.module.ui.Screen
import com.vpnautoswitch.module.ui.isDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {

    private var appState: AppState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 模块可能在 App 没运行时更新过状态，广播收不到就只剩这一份记录，进门先补一次。
        // 开关已经关掉的情况不能照着旧快照发，那会让「关掉通知」看起来没生效。
        if (Config.load(this).statusNotification) {
            StatusNotifyReceiver.replay(this)
        } else {
            StatusNotifyReceiver.cancel(this)
        }
        setContent {
            val state = remember {
                AppState(this@MainActivity).also { appState = it }
            }

            // 停留本界面时的刷新策略：
            //  - Settings.Secure 里那几个键被写时系统会通知观察者 → 立刻刷新（事件驱动）
            //  - 15 秒的兜底只做两件不写设置的事：让「心跳 N 秒前」这个相对时间继续走，
            //    以及网络类型变化（Wi-Fi ↔ 移动数据）
            // 以前是固定 5 秒整轮读一遍，一天上万次，每次都要解析日志、查 PackageManager。
            DisposableEffect(Unit) {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        state.requestRefresh()
                    }
                }
                val resolver = contentResolver
                Diagnostics.WATCHED_KEYS.forEach { key ->
                    resolver.registerContentObserver(
                        Settings.Secure.getUriFor(key), false, observer,
                    )
                }
                onDispose { resolver.unregisterContentObserver(observer) }
            }

            LaunchedEffect(Unit) {
                // 1.9.3 起通知改由本应用发，Android 13 需要运行时授权。
                // 开关在旧版本就打开过的用户不会触发 onCheckedChange，这里补一次，
                // 否则升级上来会「开关是开的、通知栏什么都没有」。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    state.config.statusNotification &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_NOTIFICATION_PERMISSION,
                    )
                }
            }

            LaunchedEffect(Unit) {
                while (isActive) {
                    delay(15_000)
                    if (state.screen is Screen.Main) {
                        state.refreshDiagnostics()
                    }
                }
            }

            // 应用内切换深浅色时，状态栏 / 导航栏图标也要跟着反色
            val dark = isDarkTheme(state.config.darkMode)
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                )
            }

            App(state)
        }
    }

    override fun onDestroy() {
        appState?.dispose()
        appState = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 刚拿到通知权限：把之前没发出去的那条补上，不用等下一次状态变化
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            StatusNotifyReceiver.replay(this)
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATION_PERMISSION = 0x5652
    }
}
