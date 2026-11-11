package com.vpnautoswitch.module.system

/**
 * 「规则要求开，但还没连上」时，当前卡在哪一步。
 *
 * 首页状态卡一直只有「命中规则 / 实际状态」两行。规则明明命中、状态却是未连接，
 * 用户看不出区别：是模块正在动手、在等客户端、在冷却，还是根本没动静。
 * 这三种情况要等的时间完全不同，混成一句「未连接」就只能反复刷新碰运气。
 *
 * 只在「本该连上却没连上」的窗口里说话，其余时候返回 null，状态卡保持原样。
 */
internal data class BlockInput(
    /** 当前规则是否要求连上 */
    val expectedOn: Boolean,
    val vpnConnected: Boolean,
    val readiness: ClientReadiness,
    /** 本轮已经发出的启动请求次数 */
    val attempts: Int,
    /** 距离下一次允许动手还有多久（0 表示已就绪） */
    val cooldownMs: Long,
    /** 是否正在等客户端初始化（进程活着、还没到重发时间） */
    val waitingClient: Boolean,
    /** 这个客户端是否成功连上过 */
    val everConnected: Boolean,
)

/**
 * @param text 一句「卡在哪」
 * @param needsUser 这件事是不是必须用户动手才能过（客户端没装、被冻结、从没授权过）；
 *   只有这类才在界面上标黄，冷却和重试是模块自己在跑，标黄会让人以为出错了
 */
internal data class BlockMessage(val text: String, val needsUser: Boolean)

internal object StartBlockPolicy {

    /**
     * @return 一句「卡在哪」，没有值得说的就返回 null。
     *
     * 客户端没就绪排最前：这时下面的冷却、重试都没有意义，说了反而误导。
     */
    fun of(input: BlockInput): BlockMessage? {
        if (!input.expectedOn || input.vpnConnected) return null

        if (ReadinessPolicy.needsUser(input.readiness)) {
            return BlockMessage(ReadinessPolicy.advice(input.readiness), needsUser = true)
        }
        if (input.waitingClient) {
            return BlockMessage(
                "已发出第 ${input.attempts} 次启动请求，正在等客户端初始化，" +
                    "最多再等 ${Engine.EXTERNAL_INIT_GRACE_MS / 1000} 秒就重发",
                needsUser = false,
            )
        }
        if (input.attempts >= Engine.MAX_EXTERNAL_ATTEMPTS) {
            return BlockMessage(
                "这一轮已经试了 ${input.attempts} 次都没连上，下一轮会换一条启动路径",
                needsUser = false,
            )
        }
        if (input.cooldownMs > 0L) {
            // 不写具体秒数：这句话会进运行时快照，带上秒数就等于每拍变一次，
            // 5 秒一次的重写变成常态，而用户看状态卡也只需要知道「在动」
            return BlockMessage("正在冷却，马上会再试一次", needsUser = false)
        }
        if (input.attempts > 0) {
            return BlockMessage(
                "已发出第 ${input.attempts} 次启动请求，等隧道建立",
                needsUser = false,
            )
        }
        // 一次都还没发过，说明是被别的条件挡住了，此时唯一有用的提示是授权
        if (!input.everConnected) {
            return BlockMessage(
                "还没有成功连上过，先在客户端里手动点一次连接完成系统授权",
                needsUser = true,
            )
        }
        return null
    }
}
