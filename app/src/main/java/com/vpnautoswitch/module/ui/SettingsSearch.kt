package com.vpnautoswitch.module.ui

/**
 * 设置页搜索。
 *
 * 只做「分组级」过滤：命中任意关键词就保留整组，避免为 37 个条目逐个建索引，
 * 也不会因为 UI 增删条目而和搜索表脱节。
 */
internal object SettingsSearch {

    data class Section(val title: String, val keywords: List<String>)

    val sections: List<Section> = listOf(
        Section(
            "网络条件",
            listOf("wifi", "wi-fi", "无线", "网络", "数据", "流量", "移动", "ssid", "热点"),
        ),
        Section(
            "外观",
            listOf("深色", "浅色", "主题", "颜色", "跟随", "系统", "dark", "light", "夜间"),
        ),
        Section(
            "界面与动画",
            listOf("悬浮", "导航", "底部", "动画", "切页", "滑动", "手势", "震动", "触感", "haptic"),
        ),
        Section(
            "高级",
            listOf(
                "锁定", "lockdown", "划掉", "任务", "进程", "断开", "toast", "提示",
                "通知", "界面", "强制", "停止", "常驻", "恢复", "always", "任意", "后台",
            ),
        ),
        Section("备份与恢复", listOf("导出", "导入", "备份", "恢复", "配置", "json")),
        Section("状态诊断", listOf("诊断", "自检", "心跳", "状态", "检查", "transport")),
        Section("运行日志", listOf("日志", "log", "刷新", "清空", "记录")),
    )

    /** 返回命中的分组标题；查询为空时返回 null 表示不过滤 */
    fun matchedTitles(query: String): Set<String>? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        return sections.filter { matches(it, q) }.map { it.title }.toSet()
    }

    fun matches(section: Section, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return section.title.lowercase().contains(q) ||
            section.keywords.any { it.lowercase().contains(q) }
    }
}
