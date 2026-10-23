package com.vpnautoswitch.module.system

import android.content.Context
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把模块日志写进 Settings.Secure，App 里可以直接查看（system_server 读不到 App 私有目录）。
 * 只保留最近 60 行，写失败一律忽略，绝不影响主流程。
 */
internal object ModuleLog {

    const val KEY = "vpn_autoswitch_log"
    private const val MAX_LINES = 60

    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun append(ctx: Context?, message: String) {
        if (ctx == null) return
        try {
            val cr = ctx.contentResolver
            val old = Settings.Secure.getString(cr, KEY).orEmpty()
            val lines = old.split('\n').filter { it.isNotBlank() }.toMutableList()
            lines.add("${format.format(Date())}  $message")
            while (lines.size > MAX_LINES) lines.removeAt(0)
            Settings.Secure.putString(cr, KEY, lines.joinToString("\n"))
        } catch (t: Throwable) {
            // 忽略
        }
    }

    /** 覆盖写入（App 里删除单条日志时用） */
    fun write(ctx: Context, lines: List<String>) {
        try {
            Settings.Secure.putString(
                ctx.contentResolver,
                KEY,
                lines.filter { it.isNotBlank() }.takeLast(MAX_LINES).joinToString("\n")
            )
        } catch (t: Throwable) {
            // 忽略
        }
    }

    fun clear(ctx: Context) {
        try {
            Settings.Secure.putString(ctx.contentResolver, KEY, "")
        } catch (t: Throwable) {
            // 忽略
        }
    }

    fun read(ctx: Context): String = try {
        Settings.Secure.getString(ctx.contentResolver, KEY).orEmpty()
    } catch (t: Throwable) {
        ""
    }
}
