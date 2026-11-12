package com.vpnautoswitch.module.ui

import com.vpnautoswitch.module.system.LogPolicy

/**
 * 运行日志的筛选：按类别，以及「只看异常」。
 *
 * 一次开关失败常常伴随十几条正常日志，60 行里挑起来费劲。两个维度各管一件事：
 *
 * - 类别是模块写日志时就打好的方括号（`[启动]`），回答「这段在讲什么」；
 * - 异常是这里按关键词判的，回答「这段里哪几行是坏消息」。
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

    /** 日志区顶部的类别标签；[tag] 空表示不过滤 */
    enum class Tab(val label: String, val tag: String) {
        ALL("全部", ""),
        START("启动", "启动"),
        STOP("关闭", "关闭"),
        NET("网络", "网络"),
        DIAG("诊断", "诊断"),
    }

    fun isAbnormal(line: String): Boolean = KEYWORDS.any { line.contains(it, ignoreCase = true) }

    fun inTab(line: String, tab: Tab): Boolean =
        tab.tag.isEmpty() || line.contains("[${tab.tag}]")

    fun select(
        lines: List<String>,
        onlyAbnormal: Boolean,
        tab: Tab = Tab.ALL,
    ): List<String> {
        var out = lines
        if (tab.tag.isNotEmpty()) {
            out = out.filter { inTab(it, tab) }
        }
        if (onlyAbnormal) {
            // 设备指纹不是日志内容，是「这些日志来自哪台机器」：筛异常时也留着，
            // 否则用户点「只看异常」再复制，发出去的又是一段无法归属的片段
            out = out.filter { LogPolicy.isDeviceLine(it) || isAbnormal(it) }
        }
        return out
    }
}
