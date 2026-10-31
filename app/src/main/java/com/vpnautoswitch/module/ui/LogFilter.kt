package com.vpnautoswitch.module.ui

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
        "guard:", "re-off", "skipped", "cancelled", "canceled",
        "dropped", "retry", "not in takeover", "no tunnel", "timeout",
    )

    fun isAbnormal(line: String): Boolean = KEYWORDS.any { line.contains(it, ignoreCase = true) }

    fun select(lines: List<String>, onlyAbnormal: Boolean): List<String> =
        if (onlyAbnormal) lines.filter(::isAbnormal) else lines
}
