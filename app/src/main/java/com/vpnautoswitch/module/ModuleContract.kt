package com.vpnautoswitch.module

/** App 进程与 system_server 模块之间共用的常量。 */
object ModuleContract {

    /** 快捷磁贴 / App 请求切换「暂停自动联动」 */
    const val ACTION_TOGGLE_PAUSE = "com.vpnautoswitch.module.action.TOGGLE_PAUSE"

    /** App 请求测试指定 VPN 客户端的启动方式 */
    const val ACTION_TEST_CLIENT = "com.vpnautoswitch.module.action.TEST_CLIENT"

    /** App 请求清除已记住的客户端启动方式 */
    const val ACTION_FORGET_CLIENT = "com.vpnautoswitch.module.action.FORGET_CLIENT"

    const val EXTRA_CLIENT_PACKAGE = "client_package"
    const val EXTRA_CLIENT_MODE = "client_mode"

    /** 暂停标记，由 system_server 写入，App 与磁贴只读 */
    const val PAUSED_KEY = "vpn_autoswitch_paused"
}
