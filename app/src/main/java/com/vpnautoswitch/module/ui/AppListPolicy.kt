package com.vpnautoswitch.module.ui

import java.text.Collator
import java.util.Locale

/**
 * 已选应用列表的展示顺序与筛选。
 *
 * 目标应用一多（十几二十个），列表就变成一条要来回滚的长卷，
 * 所以这里把「按什么顺序排、显示哪些」抽成纯函数，行为可以固定下来测。
 */
object AppListPolicy {

    /** 少于这个数量就不显示搜索框，免得空占一行 */
    const val SEARCH_THRESHOLD = 5

    fun shouldShowSearch(count: Int): Boolean = count >= SEARCH_THRESHOLD

    /**
     * @param packages 用户已选的包名，保持添加顺序
     * @param labelOf  包名 → 显示名
     * @param query    过滤词，匹配显示名或包名（忽略大小写）
     * @param sortByName true 按显示名（中文用拼音序），false 保持添加顺序
     */
    fun display(
        packages: Collection<String>,
        labelOf: (String) -> String,
        query: String = "",
        sortByName: Boolean = false,
    ): List<String> {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            packages.toList()
        } else {
            packages.filter {
                labelOf(it).lowercase().contains(q) || it.lowercase().contains(q)
            }
        }
        if (!sortByName) return filtered
        val collator = Collator.getInstance(Locale.CHINA)
        return filtered.sortedWith { a, b ->
            val byLabel = collator.compare(labelOf(a), labelOf(b))
            // 同名应用（比如同一个 App 的多开）再用包名兜底，保证顺序稳定
            if (byLabel != 0) byLabel else a.compareTo(b)
        }
    }
}
