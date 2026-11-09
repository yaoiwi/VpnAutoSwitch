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
 *
 * 第 0 行是设备指纹，滚动时受保护：日志写满后先被挤掉的本来是最早的行，而那
 * 一行恰恰是「这台机器是什么型号、什么 ROM」，丢了之后用户贴出来的日志又变成
 * 无法归属的片段（issue 里多个人混着贴时尤其明显）。
 */
internal object ModuleLog {

    const val KEY = "vpn_autoswitch_log"

    private const val MAX_LINES = 500
    private const val FLUSH_DELAY_MS = 300L

    // 带日期：跨天排障时只有 HH:mm:ss 无法对齐（用户贴日志常常跨了零点）
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
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
        LogPolicy.trim(buffer, MAX_LINES)
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

    /**
     * 写入设备指纹，并保证它永远待在日志最前面。
     *
     * 每次模块加载都会调（开机、作用域重载、升级后重启），内容一样就直接返回，
     * 不重复写 Settings.Secure——那里每次写入都会唤醒所有观察者。
     */
    @Synchronized
    fun ensureHeader(ctx: Context?, line: String) {
        if (ctx == null || line.isBlank()) return
        ensureLoaded(ctx)
        if (buffer.firstOrNull() == line) return
        // 升级后设备行会变（版本号进了 ROM 串的情况很少，但模块版本会变），
        // 先把旧的剔掉再加，避免头部长出两行设备指纹
        buffer.removeAll { LogPolicy.isDeviceLine(it) }
        buffer.add(0, line)
        LogPolicy.trim(buffer, MAX_LINES)
        pending = ctx
        // 头部只在启动时写一次，直接落盘，不必等 300ms 合批
        scheduled = false
        flushHandler?.removeCallbacks(flushTask)
        flush()
    }

    /** 覆盖写入（App 里删除单条日志时用） */
    @Synchronized
    fun write(ctx: Context, lines: List<String>) {
        buffer.clear()
        buffer.addAll(lines.filter { it.isNotBlank() })
        LogPolicy.trim(buffer, MAX_LINES)
        loaded = true
        pending = ctx
        scheduled = false
        flushHandler?.removeCallbacks(flushTask)
        flush()
    }

    /** 清空日志，但留着设备指纹——它说明的是「这些日志来自哪台机器」，不是日志本身 */
    @Synchronized
    fun clear(ctx: Context) {
        val header = buffer.firstOrNull()?.takeIf { LogPolicy.isDeviceLine(it) }
        buffer.clear()
        header?.let { buffer.add(it) }
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
        LogPolicy.trim(buffer, MAX_LINES)
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
