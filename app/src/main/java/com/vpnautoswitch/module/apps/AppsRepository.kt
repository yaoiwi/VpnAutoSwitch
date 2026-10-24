package com.vpnautoswitch.module.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.text.Collator
import java.util.Locale

data class AppInfo(
    val packageName: String,
    val label: String,
    val hasVpnService: Boolean,
    val icon: ImageBitmap? = null
)

object AppsRepository {

    private const val ICON_SIZE = 48

    /** 读取有启动图标的已安装应用，按名称排序 */
    fun loadLaunchable(context: Context): List<AppInfo> {
        val pm = context.packageManager

        val vpnPackages = try {
            pm.queryIntentServices(Intent("android.net.VpnService"), 0)
                .map { it.serviceInfo.packageName }
                .toHashSet()
        } catch (t: Throwable) {
            emptySet<String>()
        }

        val result = LinkedHashMap<String, AppInfo>()
        try {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).forEach { info ->
                val pkg = info.activityInfo.packageName
                if (!result.containsKey(pkg)) {
                    result[pkg] = AppInfo(
                        packageName = pkg,
                        label = info.loadLabel(pm).toString(),
                        hasVpnService = vpnPackages.contains(pkg)
                    )
                }
            }
        } catch (t: Throwable) {
            // 忽略，列表为空即可
        }

        val collator = Collator.getInstance(Locale.CHINA)
        return result.values.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /** 把应用图标转成 Compose 可用的 ImageBitmap（在子线程调用） */
    fun loadIcons(context: Context, apps: List<AppInfo>): List<AppInfo> {
        val pm = context.packageManager
        return apps.map { app ->
            val drawable = try {
                pm.getApplicationIcon(app.packageName)
            } catch (t: Throwable) {
                null
            }
            val bitmap = drawable?.let { toBitmap(it, ICON_SIZE) }
            app.copy(icon = bitmap?.asImageBitmap())
        }
    }

    private fun toBitmap(drawable: Drawable, size: Int = ICON_SIZE): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    fun labelOf(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (t: Throwable) {
        packageName
    }

    /** 直接检查配置的 VPN 客户端，不依赖「有启动图标」的应用列表。 */
    fun inspectVpn(context: Context, packageName: String): VpnClientInspection? {
        if (packageName.isBlank()) return null
        val pm = context.packageManager
        val info = try {
            pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (t: Throwable) {
            null
        } ?: return null
        val hasService = try {
            pm.queryIntentServices(
                Intent("android.net.VpnService").setPackage(packageName),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            ).isNotEmpty()
        } catch (t: Throwable) {
            false
        }
        return VpnClientInspection(
            installed = true,
            enabled = info.enabled,
            hasVpnService = hasService,
        )
    }
}

data class VpnClientInspection(
    val installed: Boolean,
    val enabled: Boolean,
    val hasVpnService: Boolean,
)
