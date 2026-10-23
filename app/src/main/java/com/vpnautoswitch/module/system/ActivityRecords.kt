package com.vpnautoswitch.module.system

import android.os.IBinder
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 把 system_server 里的 ActivityRecord 反射成包名。
 * Android 10 ~ 14 的字段/方法名保持稳定，找不到时静默降级。
 */
internal object ActivityRecords {

    private const val TAG = "VpnAutoSwitch"

    private var forToken: Method? = null
    private var packageNameField: Field? = null
    private var resolved = false
    private var loader: ClassLoader? = null

    /** system_server 的 ClassLoader，由 ModuleEntry 注入 */
    fun attach(classLoader: ClassLoader) {
        loader = classLoader
    }

    @Synchronized
    private fun resolve() {
        if (resolved) return
        resolved = true
        try {
            val cls = Class.forName("com.android.server.wm.ActivityRecord", false, loader)
            for (name in arrayOf("forTokenLocked", "forToken")) {
                val m = try {
                    cls.getDeclaredMethod(name, IBinder::class.java).apply { isAccessible = true }
                } catch (t: Throwable) {
                    null
                }
                if (m != null) {
                    forToken = m
                    break
                }
            }
            packageNameField = try {
                cls.getDeclaredField("packageName").apply { isAccessible = true }
            } catch (t: Throwable) {
                null
            }
            XposedBridge.log(
                "[$TAG] ActivityRecord resolver: method=$forToken field=$packageNameField"
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] ActivityRecord resolve failed: $t")
        }
    }

    fun packageOfToken(token: IBinder): String? {
        resolve()
        val m = forToken ?: return null
        val record = try {
            m.invoke(null, token)
        } catch (t: Throwable) {
            null
        } ?: return null
        return packageOf(record)
    }

    fun packageOf(record: Any?): String? {
        if (record == null) return null
        resolve()
        return try {
            packageNameField?.get(record) as? String
        } catch (t: Throwable) {
            null
        }
    }
}
