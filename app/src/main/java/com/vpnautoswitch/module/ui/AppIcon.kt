package com.vpnautoswitch.module.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.vpnautoswitch.module.apps.AppsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 行内按需加载应用图标。
 *
 * 先看缓存；没有就返回 null（调用方先画首字母占位），随后在 IO 线程解码并刷新。
 * 这样几百个应用的选择页只会解码真正滚到屏幕上的那几行，首帧不用等全量图标。
 */
@Composable
internal fun rememberAppIcon(packageName: String, activityName: String? = null): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    var icon by remember(packageName) {
        mutableStateOf(AppsRepository.cachedIcon(packageName))
    }
    LaunchedEffect(packageName, activityName) {
        if (icon == null) {
            icon = withContext(Dispatchers.IO) {
                AppsRepository.icon(context, packageName, activityName)
            }
        }
    }
    return icon
}
