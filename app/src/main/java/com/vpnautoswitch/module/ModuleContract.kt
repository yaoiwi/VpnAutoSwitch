package com.vpnautoswitch.module

/** App 进程与 system_server 模块之间共用的常量。 */
object ModuleContract {

    /** 快捷磁贴 / App 请求切换「暂停自动联动」 */
    const val ACTION_TOGGLE_PAUSE = "com.vpnautoswitch.module.action.TOGGLE_PAUSE"

    /** 暂停标记，由 system_server 写入，App 与磁贴只读 */
    const val PAUSED_KEY = "vpn_autoswitch_paused"
}
