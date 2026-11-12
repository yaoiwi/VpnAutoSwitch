package com.vpnautoswitch.module.system

import android.content.Context
import android.os.Handler
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志类别，写进每行开头的方括号里。
 *
 * 一轮开关流程要走十几条日志，按时间顺序排下来「谁在等谁」很难读。分类之后
 * App 里可以只看启动或只看关闭，用户贴日志时也可以只贴一段相关的。
 */
internal enum class LogCat(val tag: String) {
    /** 拉起客户端、探测启动方式、重试 */
    START("启动"),

    /** 断开、强停、验证是否真的关掉 */
    STOP("关闭"),

    /** 网络切换、SSID 变化、Wi-Fi 规则 */
    NET("网络"),

    /** 规则判定、客户端就绪状态、诊断快照 */
    DIAG("诊断"),

    /** 模块加载、hook 注入、配置读取 */
    BOOT("加载"),
}

/**
 * 把模块日志写进 Settings.Secure，App 里可以直接查看（system_server 读不到 App 私有目录）。
 *
 * 以前每条日志都是「读全部 → 追加一行 → 全量写回」：一次开关流程要走十几个 log()，
 * 等于十几次 SettingsProvider 全量读写，每次写完还会唤醒所有观察者。
 * 现在先在内存里攒着，[FLUSH_DELAY_MS] 内的日志合并成一次写入。
 *
 * 容量分两层：
 *   - 内存留 [MAX_MEMORY_LINES] 行，够覆盖好几轮开关流程，按需导出时用这一份；
 *   - Settings 里只留最近 [MAX_SETTINGS_LINES] 行，界面日志区读的就是它。
 *
 * 分层的理由：Settings 每次写入都是全量替换 + 唤醒观察者，两千行（约 150KB）
 * 每 300 毫秒写一遍会明显拖慢 system_server；而 500 行又常常在用户报问题之前
 * 就被后续心跳挤掉——issue #15 里那份只有 3 行的日志就是这么来的。
 *
 * 第 0 行是设备指纹，滚动时受保护：日志写满后先被挤掉的本来是最早的行，而那
 * 一行恰恰是「这台机器是什么型号、什么 ROM」，丢了之后用户贴出来的日志又变成
 * 无法归属的片段（issue 里多个人混着贴时尤其明显）。
 */
internal object ModuleLog {

    const val KEY = "vpn_autoswitch_log"

    /** 按需导出的完整日志；App 读走之后会请模块清空，不常驻 */
    const val FULL_KEY = "vpn_autoswitch_log_full"

    /** 内存里保留的行数：约等于普通用户两三轮开关流程，够定位一次失败 */
    private const val MAX_MEMORY_LINES = 2000

    /** 写进 Settings 的行数：界面日志区只显示这么多，读得快 */
    private const val MAX_SETTINGS_LINES = 500

    /** 单次导出上限：再多就超过 Settings 单值写入的合理体积了 */
    private const val MAX_DUMP_LINES = 1500

    private const val FLUSH_DELAY_MS = 300L

    // 带日期：跨天排障时只有 HH:mm:ss 无法对齐（用户贴日志常常跨了零点）
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val buffer = ArrayList<String>(MAX_MEMORY_LINES)
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
        LogPolicy.trim(buffer, MAX_MEMORY_LINES)
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
        LogPolicy.trim(buffer, MAX_MEMORY_LINES)
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
        LogPolicy.trim(buffer, MAX_MEMORY_LINES)
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
        LogPolicy.trim(buffer, MAX_MEMORY_LINES)
    }

    /**
     * 按需导出完整日志。
     *
     * App 侧点「导出完整日志」时由模块代写：普通应用没有 WRITE_SECURE_SETTINGS，
     * 自己写不进来。写的是内存里那一份（最多 [MAX_MEMORY_LINES] 行），
     * 比界面能看到的 500 行多出三倍，足够覆盖「打开应用 → 连不上 → 又试了几轮」的全过程。
     *
     * @return 写入的行数；-1 表示写入失败
     */
    @Synchronized
    fun dump(ctx: Context): Int {
        ensureLoaded(ctx)
        val lines = LogPolicy.tailWithHeader(buffer, MAX_DUMP_LINES)
        return try {
            Settings.Secure.putString(ctx.contentResolver, FULL_KEY, lines.joinToString("\n"))
            lines.size
        } catch (t: Throwable) {
            -1
        }
    }

    /** App 把完整日志读走之后请模块清掉，别让一百多 KB 常驻在设置里 */
    @Synchronized
    fun clearFull(ctx: Context) {
        try {
            Settings.Secure.putString(ctx.contentResolver, FULL_KEY, "")
        } catch (t: Throwable) {
            // 清不掉只影响设置体积，不影响功能
        }
    }

    @Synchronized
    private fun flush() {
        scheduled = false
        val ctx = pending ?: return
        try {
            // 只落最近 500 行：两千行（约 150KB）每 300 毫秒全量写一次会把 SettingsProvider 拖住。
            // 设备指纹由 tailWithHeader 单独保留在第一行
            val lines = LogPolicy.tailWithHeader(buffer, MAX_SETTINGS_LINES)
            Settings.Secure.putString(ctx.contentResolver, KEY, lines.joinToString("\n"))
        } catch (t: Throwable) {
            // 忽略：写日志失败绝不影响主流程
        }
    }
}
