package com.vpnautoswitch.module.system

import android.content.Context
import android.os.Handler
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把模块日志写进 Settings.Secure，App 里可以直接查看（system_server 读不到 App 私有目录）。
 *
 * 以前每条日志都是「读全部 → 追加一行 → 全量写回」：一次开关流程要走十几个 log()，
 * 等于十几次 SettingsProvider 全量读写，每次写完还会唤醒所有观察者。
 * 现在先在内存里攒着，[FLUSH_DELAY_MS] 内的日志合并成一次写入。
 * 只保留最近 [MAX_LINES] 行，够放下一整轮开关流程，排障时关键信息不会被挤掉。
 */
internal object ModuleLog {

    const val KEY = "vpn_autoswitch_log"

    private const val MAX_LINES = 300
    private const val FLUSH_DELAY_MS = 300L

    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val buffer = ArrayList<String>(MAX_LINES)
    private var loaded = false
    private var scheduled = false
    private var pending: Context? = null
    private var flushHandler: Handler? = null

    private val flushTask = Runnable { flush() }

    /** 跟着模块自己的工作线程走：不额外起线程，也不占 system_server 主线程 */
    fun attach(handler: Handler) {
        flushHandler = handler
    }

    @Synchronized
    fun append(ctx: Context?, message: String) {
        if (ctx == null) return
        ensureLoaded(ctx)
        buffer.add("${format.format(Date())}  $message")
        while (buffer.size > MAX_LINES) buffer.removeAt(0)
        pending = ctx
        val handler = flushHandler
        if (handler == null) {
            // 还没接上工作线程（极早期日志）：直接写，别丢
            flush()
        } else if (!scheduled) {
            scheduled = true
            handler.postDelayed(flushTask, FLUSH_DELAY_MS)
        }
    }

    /** 覆盖写入（App 里删除单条日志时用） */
    @Synchronized
    fun write(ctx: Context, lines: List<String>) {
        buffer.clear()
        buffer.addAll(lines.filter { it.isNotBlank() }.takeLast(MAX_LINES))
        loaded = true
        pending = ctx
        scheduled = false
        flushHandler?.removeCallbacks(flushTask)
        flush()
    }

    @Synchronized
    fun clear(ctx: Context) {
        buffer.clear()
        loaded = true
        pending = ctx
        scheduled = false
        flushHandler?.removeCallbacks(flushTask)
        flush()
    }

    @Synchronized
    fun read(ctx: Context): String {
        if (loaded) return buffer.joinToString("\n")
        return try {
            Settings.Secure.getString(ctx.contentResolver, KEY).orEmpty()
        } catch (t: Throwable) {
            ""
        }
    }

    /** 第一次写之前把已有内容读进内存，避免把之前的日志冲掉 */
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val old = try {
            Settings.Secure.getString(ctx.contentResolver, KEY).orEmpty()
        } catch (t: Throwable) {
            ""
        }
        old.split('\n').filter { it.isNotBlank() }.forEach { buffer.add(it) }
        while (buffer.size > MAX_LINES) buffer.removeAt(0)
    }

    @Synchronized
    private fun flush() {
        scheduled = false
        val ctx = pending ?: return
        try {
            Settings.Secure.putString(ctx.contentResolver, KEY, buffer.joinToString("\n"))
        } catch (t: Throwable) {
            // 忽略：写日志失败绝不影响主流程
        }
    }
}
