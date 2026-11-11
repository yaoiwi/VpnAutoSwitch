package com.vpnautoswitch.module.system

/**
 * 「固定启动方式」的存储格式与过期判定。
 *
 * 老格式是 `包名|方式`，没有客户端版本也没有时间。客户端升级后启动方式可能完全变了，
 * 但固定值还是旧的那一条，模块会一直按它重试——issue #18 里换客户端之后用户看不到
 * 任何「配置已过期」的迹象，只能自己猜。带版本之后，升级本身就成了重新探测的理由。
 *
 * 解析写成纯函数放在这里，是因为单测里没有 `Settings.Secure`，也没法构造 `Context`。
 */
internal object PinCodec {

    /** 一条固定记录。version 为 0 表示老格式，无法判断是否过期。 */
    data class Entry(
        val pkg: String,
        val mode: String,
        val version: Int,
        val at: Long,
    )

    private const val MODE_ACTIVITY = "activity"
    private const val MODE_SERVICE = "service"

    /**
     * 解析整串存储值。
     *
     * 两种格式都认：`包名|方式` 和 `包名|方式|客户端版本|固定时间`。
     * 方式只接受 activity / service——旧版本可能写进别的值，留着会让引擎走错分支。
     */
    fun parse(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',')
            .map { it.trim() }
            .mapNotNull { parseOne(it) }
    }

    private fun parseOne(item: String): Entry? {
        val parts = item.split('|')
        if (parts.size < 2) return null
        val pkg = parts[0].trim()
        val mode = parts[1].trim()
        if (pkg.isEmpty()) return null
        if (mode != MODE_ACTIVITY && mode != MODE_SERVICE) return null
        val version = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
        val at = parts.getOrNull(3)?.trim()?.toLongOrNull() ?: 0L
        return Entry(pkg, mode, version, at)
    }

    /** 写回存储时的整串：每条都带版本和时间，老记录在下次 [PinCodec.format] 时自然升级格式。 */
    fun format(entries: List<Entry>): String = entries.joinToString(",") {
        "${it.pkg}|${it.mode}|${it.version}|${it.at}"
    }

    fun entryFor(raw: String?, pkg: String): Entry? =
        parse(raw).firstOrNull { it.pkg == pkg }

    /**
     * 客户端版本变了就算过期。
     *
     * 两种情况不判过期：老格式没有版本（0），或者查不到客户端版本。宁可继续用旧记录，
     * 也不要把一批本来能用的配置打回「从头学」——那会让用户多等几轮，而且新学到的
     * 方式未必比固定的好。
     */
    fun isStale(entry: Entry, clientVersion: Int): Boolean =
        entry.version > 0 && clientVersion > 0 && entry.version != clientVersion

    /** 同包只留一条，按包名替换。 */
    fun upsert(entries: List<Entry>, entry: Entry): List<Entry> =
        entries.filterNot { it.pkg == entry.pkg } + entry

    fun remove(entries: List<Entry>, pkg: String): List<Entry> = entries.filterNot { it.pkg == pkg }
}
