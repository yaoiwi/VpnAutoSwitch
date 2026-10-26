package com.vpnautoswitch.module.system

import android.content.Intent
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 把 system_server 里的 Task / TaskRecord 反射成包名。
 *
 * 任务移除回调不一定直接给出包名：
 *  - ActivityTaskSupervisor.removeTask(Task, ...) 的参数里有 Task 对象
 *  - ActivityTaskManagerService.removeTask(int) 只有 taskId
 *
 * 两条路都做一次解析；失败时静默降级，不能影响系统移除任务。
 */
internal object TaskRecords {

    private const val TAG = "VpnAutoSwitch"
    private const val MATCH_ATTACHED_OR_RECENT = 1

    private var rootContainerField: Field? = null
    private var anyTaskWithMode: Method? = null
    private var anyTaskWithoutMode: Method? = null
    private var serviceClass: Class<*>? = null

    @Synchronized
    private fun resolveService(service: Any) {
        if (serviceClass == service.javaClass) return
        serviceClass = service.javaClass
        rootContainerField = findField(service.javaClass, "mRootWindowContainer")
        val containerClass = rootContainerField?.type
        anyTaskWithMode = findMethod(
            containerClass,
            "anyTaskForId",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )
        anyTaskWithoutMode = findMethod(
            containerClass,
            "anyTaskForId",
            Int::class.javaPrimitiveType!!,
        )
        XposedBridge.log(
            "[$TAG] Task resolver: field=$rootContainerField " +
                "withMode=$anyTaskWithMode withoutMode=$anyTaskWithoutMode"
        )
    }

    /** 用 ATMS.removeTask(int) 的 taskId 查当前任务。 */
    fun packageOfTaskId(service: Any, taskId: Int): String? {
        resolveService(service)
        val container = try {
            rootContainerField?.get(service)
        } catch (t: Throwable) {
            null
        } ?: return null
        val task = try {
            anyTaskWithMode?.invoke(container, taskId, MATCH_ATTACHED_OR_RECENT)
                ?: anyTaskWithoutMode?.invoke(container, taskId)
        } catch (t: Throwable) {
            null
        }
        return packageOf(task)
    }

    /** 从 Task / TaskRecord 取启动组件或根 Activity 的包名。 */
    fun packageOf(task: Any?): String? {
        if (task == null) return null

        val intent = invokeNoArg(task, "getBaseIntent") as? Intent
        intent?.component?.packageName?.let { return it }

        val rootActivity = invokeNoArg(task, "getRootActivity")
            ?: fieldOf(task, "rootActivity", "mRootActivity")
        return ActivityRecords.packageOf(rootActivity)
    }

    private fun invokeNoArg(target: Any, vararg names: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            for (name in names) {
                try {
                    val method = cls.getDeclaredMethod(name).apply { isAccessible = true }
                    return method.invoke(target)
                } catch (t: Throwable) {
                    // 继续找下一个
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun fieldOf(target: Any, vararg names: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            for (name in names) {
                try {
                    val field = cls.getDeclaredField(name).apply { isAccessible = true }
                    return field.get(target)
                } catch (t: Throwable) {
                    // 继续找下一个
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun findField(cls: Class<*>, name: String): Field? {
        var current: Class<*>? = cls
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (t: Throwable) {
                current = current.superclass
            }
        }
        return null
    }

    private fun findMethod(cls: Class<*>?, name: String, vararg params: Class<*>): Method? {
        if (cls == null) return null
        return try {
            cls.getDeclaredMethod(name, *params).apply { isAccessible = true }
        } catch (t: Throwable) {
            null
        }
    }
}
