package com.vpnautoswitch.module.ui

import com.vpnautoswitch.module.system.LogPolicy

/**
 * 运行日志的「只看异常」过滤。
 *
 * 模块的日志既记正常流程（连上、断开），也记排障信息。真正要看的往往是失败、
 * 被跳过、被作废的那几类，60 行里挑起来费劲，这里统一列一份关键词。
 */
internal object LogFilter {

    private val KEYWORDS = listOf(
        "失败", "无法", "拒绝", "超时", "异常", "没关掉", "未接管",
        "failed", "error", "force stop", "force stopped",
        // startForegroundService 被系统挡回来时打的就是这行，纯英文，
        // 以前「只看异常」筛不出来
        "rejected",
        "guard:", "re-off", "skipped", "cancelled", "canceled",
        "dropped", "retry", "not in takeover", "no tunnel", "timeout",
    )

    fun isAbnormal(line: String): Boolean = KEYWORDS.any { line.contains(it, ignoreCase = true) }

    fun select(lines: List<String>, onlyAbnormal: Boolean): List<String> {
        if (!onlyAbnormal) return lines
        // 设备指纹不是日志内容，是「这些日志来自哪台机器」：筛异常时也留着，
        // 否则用户点「只看异常」再复制，发出去的又是一段无法归属的片段
        return lines.filter { LogPolicy.isDeviceLine(it) || isAbnormal(it) }
    }
}
