package com.vpnautoswitch.module.system

/**
 * 日志滚动与行识别的纯逻辑。
 *
 * 单独抽出来是因为 [ModuleLog] 本身要碰 Settings.Secure 和 Handler，没法在
 * 单测里跑；而「哪些行会被滚掉」恰恰是最容易出错的地方——正常滚动按先进先出
 * 删第 0 行，但第 0 行是设备指纹，删了就等于白加（开机几小时后日志写满，
 * 用户贴出来的日志又变回「不知道什么机器」）。
 */
internal object LogPolicy {

    /** 设备指纹行的正文前缀，写在时间戳之后 */
    const val DEVICE_PREFIX = "device: "

    /**
     * 把列表裁剪到 [maxLines] 以内，保留前 [keepHead] 行。
     *
     * 保留的是「最早」的几行——放进日志语境里就是设备指纹这类一次性上下文。
     * 超出部分从 `keepHead` 位置开始丢，保证后写入的日志总能留下来。
     */
    fun trim(lines: MutableList<String>, maxLines: Int, keepHead: Int = 1) {
        if (maxLines <= 0) {
            lines.clear()
            return
        }
        val head = keepHead.coerceIn(0, maxLines)
        while (lines.size > maxLines) {
            // 头部全都要保留时，只能从头部之后的第一行开始丢
            val index = if (head < lines.size) head else 0
            lines.removeAt(index)
        }
    }

    /** 判断这一行是不是设备指纹（`MM-dd HH:mm:ss  device: ...` 或裸的 `device: ...`） */
    fun isDeviceLine(line: String): Boolean =
        line.substringAfter("  ", line).startsWith(DEVICE_PREFIX)

    /**
     * 取最近 [limit] 行，但设备指纹始终留在开头。
     *
     * 界面只显示最近 60 条、复制报告只带 20 条，直接 `takeLast` 会把第 0 行
     * 截掉——用户看到的、贴出去的又变成一段没法归属的日志。
     */
    fun tailWithHeader(lines: List<String>, limit: Int): List<String> {
        if (lines.isEmpty()) return emptyList()
        val tail = lines.takeLast(limit.coerceAtLeast(0))
        val header = lines.firstOrNull(::isDeviceLine) ?: return tail
        return if (tail.any(::isDeviceLine)) tail else listOf(header) + tail
    }
}
