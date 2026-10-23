package com.vpnautoswitch.module.system

import android.content.pm.ApplicationInfo
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field

/**
 * 把 system_server 里的 ProcessRecord 反射成包名 / 进程名。
 */
internal object ProcessRecords {

    private const val TAG = "VpnAutoSwitch"

    private var infoField: Field? = null
    private var processNameField: Field? = null
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
            val cls = Class.forName("com.android.server.am.ProcessRecord", false, loader)
            infoField = try {
                cls.getDeclaredField("info").apply { isAccessible = true }
            } catch (t: Throwable) {
                null
            }
            processNameField = try {
                cls.getDeclaredField("processName").apply { isAccessible = true }
            } catch (t: Throwable) {
                null
            }
            XposedBridge.log("[$TAG] ProcessRecord resolver: info=$infoField name=$processNameField")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] ProcessRecord resolve failed: $t")
        }
    }

    fun packageOf(processRecord: Any): String? {
        resolve()
        return try {
            val info = infoField?.get(processRecord) as? ApplicationInfo
            info?.packageName
        } catch (t: Throwable) {
            null
        }
    }

    fun processNameOf(processRecord: Any): String? {
        resolve()
        return try {
            processNameField?.get(processRecord) as? String
        } catch (t: Throwable) {
            null
        }
    }
}
