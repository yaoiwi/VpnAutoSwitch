package com.vpnautoswitch.module.system

import android.os.Build

/**
 * 设备指纹：型号、Android 版本、ROM 构建号、厂商系统版本。
 *
 * 为什么值得单独写进日志：状态报告顶部本来就有这一行，但报告要用户主动打开
 * 应用才会生成；而 issue 里贴出来的往往只有一段运行日志，看不出是什么机器。
 * 同一个故障在不同 ROM 上的成因完全不同（#15 是荣耀的后台限制、#16 是
 * ColorOS 的任务清理），没有这行就只能靠反复追问。
 *
 * 厂商版本走反射读 `android.os.SystemProperties`：模块跑在 system_server 里，
 * 读得到；App 进程可能被 hidden API 拦下，那就返回 null，报告里少一段而已，
 * 不影响其他内容。
 */
internal object DeviceInfo {

    /** 厂商系统版本字段。第一个能读到的就用，值和 [Build.DISPLAY] 重复时跳过 */
    private val ROM_PROPS = listOf(
        "ro.miui.ui.version.name" to "MIUI",
        "ro.build.version.oplusrom" to "ColorOS",
        "ro.build.version.opporom" to "ColorOS",
        "ro.build.version.emui" to "EMUI/HarmonyOS",
        "ro.vivo.os.version" to "OriginOS",
        "ro.build.version.realmeui" to "realme UI",
        "ro.build.version.oneui" to "One UI",
    )

    /** 日志第 0 行用的完整指纹，形如 `device: OnePlus PKR110 / Android 16 (API 36) / ...` */
    fun fingerprintLine(): String = LogPolicy.DEVICE_PREFIX + fingerprint()

    /** 报告和日志共用的主体，不含前缀 */
    fun fingerprint(): String = buildString {
        append(Build.MANUFACTURER)
        append(' ')
        append(Build.MODEL)
        append(" / Android ")
        append(Build.VERSION.RELEASE)
        append(" (API ")
        append(Build.VERSION.SDK_INT)
        append(')')
        val display = Build.DISPLAY
        if (!display.isNullOrBlank()) {
            append(" / ")
            append(display)
        }
        romVendor()?.let {
            append(" / ")
            append(it)
        }
    }

    /**
     * 厂商系统版本，形如 `ColorOS 16.0.1` / `MIUI V816`。
     *
     * 和 `Build.DISPLAY` 相同的值直接丢掉——OnePlus 这类机型两个字段本来就是
     * 同一个串，重复一遍只是让行变长。
     */
    fun romVendor(): String? {
        for ((prop, label) in ROM_PROPS) {
            val value = systemProperty(prop) ?: continue
            if (value == Build.DISPLAY) continue
            return "$label $value"
        }
        return null
    }

    private fun systemProperty(key: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }
}
