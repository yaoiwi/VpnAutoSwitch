package com.vpnautoswitch.module.system

import com.vpnautoswitch.module.Config

/**
 * 配置镜像的判定规则。
 *
 * 模块在 system_server 里取配置的唯一途径是查询模块 App 暴露的 ConfigProvider。
 * 而 App 进程不一定起得来：
 *  - 刚装完还没点开过，Android 认为这个包处于 stopped 状态，provider 查询会被直接拒绝；
 *  - 部分 ROM 的自启动管理会拦掉「被别的进程拉起」的 App 进程。
 *
 * 这两种情况下 query 失败，模块原来会拿到一个默认 Config（vpnPackage 为空），于是
 * 「已经配好的目标应用」被当成「没配置」——不连 VPN，甚至把正在用的 VPN 关掉。
 * 表现出来就是：重启后必须打开一次模块界面才正常。
 *
 * 所以每次成功拿到配置就往 Settings.Secure 写一份镜像，下次读不到时退回镜像。
 * 这里只放「镜像还能不能用」的纯判断，方便单测。
 */
internal object ConfigCachePolicy {

    /** 镜像存放的 Secure 键（system_server 可读写，App 不需要读它） */
    const val MIRROR_KEY = "vpn_autoswitch_config_mirror"

    /** 镜像超过这么久就不再采信：用户可能换了配置甚至换了客户端 */
    const val MAX_AGE_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** 时钟回拨的容差：写入时间比现在还晚这么多，说明不可信 */
    private const val FUTURE_TOLERANCE_MS = 60_000L

    fun isFresh(savedAt: Long, now: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean {
        if (savedAt <= 0L) return false
        if (savedAt > now + FUTURE_TOLERANCE_MS) return false
        return now - savedAt <= maxAgeMs
    }

    /** 镜像内容是否可以拿来当配置用：至少要能看出「用户配过」 */
    fun isUsable(config: Config?): Boolean =
        config != null && (config.vpnPackage.isNotEmpty() || config.targets.isNotEmpty())
}
