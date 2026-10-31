package com.vpnautoswitch.module.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.quicksettings.TileService
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.text.Collator
import java.util.Locale

data class AppInfo(
    val packageName: String,
    val label: String,
    val hasVpnService: Boolean,
    val activityName: String? = null,
    /** 预装应用。选择页里单独分一组，免得在几百个系统应用里翻自己的 App */
    val isSystem: Boolean = false
)

object AppsRepository {

    /** 按 40dp @3x 渲染，避免 48px 图标在列表里发虚 */
    private const val ICON_SIZE = 128

    private val fallbackColors = intArrayOf(
        0xFF2F6BFF.toInt(),
        0xFF7C5CFF.toInt(),
        0xFF00A88F.toInt(),
        0xFFE06C00.toInt(),
        0xFFD9366E.toInt(),
        0xFF0089C7.toInt(),
    )

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
                    val systemFlags = try {
                        pm.getApplicationInfo(pkg, 0).flags
                    } catch (t: Throwable) {
                        0
                    }
                    result[pkg] = AppInfo(
                        packageName = pkg,
                        label = info.loadLabel(pm).toString(),
                        hasVpnService = vpnPackages.contains(pkg),
                        activityName = info.activityInfo.name,
                        isSystem = (systemFlags and (
                            ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                            )) != 0,
                    )
                }
            }
        } catch (t: Throwable) {
            // 忽略，列表为空即可
        }

        val collator = Collator.getInstance(Locale.CHINA)
        return result.values.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /**
     * 图标缓存。
     *
     * 以前进选择页时一次性把两百多个应用的图标全部解码，首帧要等一两秒；
     * 现在列表只装名称，行滚到眼前才取图标。160 个够覆盖来回滚动的那几屏，
     * 超出按最久未用淘汰（128×128 一张约 64KB，上限约 10MB）。
     */
    private const val ICON_CACHE_SIZE = 160
    private val iconCache =
        object : LinkedHashMap<String, ImageBitmap>(ICON_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ImageBitmap>?,
            ): Boolean = size > ICON_CACHE_SIZE
        }

    /** 已经在内存里的图标；没有就返回 null，调用方先用占位块顶上 */
    fun cachedIcon(packageName: String): ImageBitmap? =
        synchronized(iconCache) { iconCache[packageName] }

    /**
     * 按需取一个应用的图标（在子线程调用）。
     *
     * 依次尝试：启动 Activity 的图标 → 应用图标 → 文字占位图。
     * 部分 ROM / 图标包会让前两种抛异常，此时仍然给出一个可辨识的方块，列表不会出现空洞。
     */
    fun icon(context: Context, packageName: String, activityName: String? = null): ImageBitmap {
        cachedIcon(packageName)?.let { return it }

        val pm = context.packageManager
        val drawable = try {
            activityName?.let { pm.getActivityIcon(ComponentName(packageName, it)) }
        } catch (t: Throwable) {
            null
        } ?: try {
            pm.getApplicationIcon(packageName)
        } catch (t: Throwable) {
            null
        }

        val bitmap = drawable?.let {
            try {
                toBitmap(it, ICON_SIZE)
            } catch (t: Throwable) {
                null
            }
        } ?: fallbackIcon(labelOf(context, packageName))

        val result = bitmap.asImageBitmap()
        synchronized(iconCache) { iconCache[packageName] = result }
        return result
    }

    private fun toBitmap(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable) {
            val source = drawable.bitmap
            if (source != null && !source.isRecycled) {
                if (source.width == size && source.height == size) return source
                return Bitmap.createScaledBitmap(source, size, size, true)
            }
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    /** 图标资源损坏或缺失时，用首字符生成一个稳定的占位图。 */
    private fun fallbackIcon(label: String): Bitmap {
        val size = ICON_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fallbackColors[(label.hashCode() and 0x7FFFFFFF) % fallbackColors.size]
        }
        val radius = size * 0.24f
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, background)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.46f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val trimmed = label.trim()
        val letter = if (trimmed.isEmpty()) {
            "?"
        } else {
            trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).uppercase(Locale.getDefault())
        }
        val metrics = text.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(letter, size / 2f, baseline, text)
        return bitmap
    }

    /**
     * 包是否仍然安装。
     *
     * 带 MATCH_DISABLED_COMPONENTS：用户只是在系统设置里「停用」应用时不算卸载，
     * 否则下次启用会被当成新装，开关被静默关掉。
     */
    fun isInstalled(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            true
        } catch (t: Throwable) {
            false
        }
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
        val services = try {
            pm.queryIntentServices(
                Intent("android.net.VpnService").setPackage(packageName),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
        } catch (t: Throwable) {
            emptyList()
        }
        val serviceName = (services.firstOrNull {
            it.serviceInfo.permission == "android.permission.BIND_VPN_SERVICE"
        } ?: services.firstOrNull {
            it.serviceInfo.name.contains("Tun", ignoreCase = true)
        })?.serviceInfo?.name
        val externalStart = hasActivity(pm, packageName, "$packageName.action.START_CLASH")
        val externalStop = hasActivity(pm, packageName, "$packageName.action.STOP_CLASH")
        val quickTile = try {
            pm.queryIntentServices(
                Intent(TileService.ACTION_QS_TILE).setPackage(packageName),
                0,
            ).isNotEmpty()
        } catch (t: Throwable) {
            false
        }
        return VpnClientInspection(
            installed = true,
            enabled = info.enabled,
            hasVpnService = hasService,
            vpnServiceName = serviceName,
            hasExternalStart = externalStart,
            hasExternalStop = externalStop,
            hasQuickTile = quickTile,
        )
    }

    private fun hasActivity(pm: PackageManager, pkg: String, action: String): Boolean = try {
        pm.resolveActivity(
            Intent(action).setPackage(pkg).addCategory(Intent.CATEGORY_DEFAULT),
            PackageManager.MATCH_DEFAULT_ONLY,
        ) != null
    } catch (t: Throwable) {
        false
    }
}

data class VpnClientInspection(
    val installed: Boolean,
    val enabled: Boolean,
    val hasVpnService: Boolean,
    val vpnServiceName: String? = null,
    val hasExternalStart: Boolean = false,
    val hasExternalStop: Boolean = false,
    val hasQuickTile: Boolean = false,
)
