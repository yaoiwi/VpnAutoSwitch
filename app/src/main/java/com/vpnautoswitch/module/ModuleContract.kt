package com.vpnautoswitch.module

/** App 进程与 system_server 模块之间共用的常量。 */
object ModuleContract {

    /** 快捷磁贴 / App 请求切换「暂停自动联动」 */
    const val ACTION_TOGGLE_PAUSE = "com.vpnautoswitch.module.action.TOGGLE_PAUSE"

    /** App 请求测试指定 VPN 客户端的启动方式 */
    const val ACTION_TEST_CLIENT = "com.vpnautoswitch.module.action.TEST_CLIENT"

    /** App 请求清除已记住的客户端启动方式 */
    const val ACTION_FORGET_CLIENT = "com.vpnautoswitch.module.action.FORGET_CLIENT"

    /**
     * system_server 通知 App 刷新常驻状态通知（由 App 自己发，归属和图标才是本应用）。
     *
     * 只由系统进程发出：接收方声明了 `android.permission.STATUS_BAR_SERVICE`，
     * 普通应用拿不到这个权限，发不过来。
     */
    const val ACTION_STATUS_NOTIFY = "com.vpnautoswitch.module.action.STATUS_NOTIFY"

    const val EXTRA_CLIENT_PACKAGE = "client_package"
    const val EXTRA_CLIENT_MODE = "client_mode"

    /** 状态通知的字段：on=false 表示撤掉，其余是正文内容 */
    const val EXTRA_STATUS_ON = "status_on"
    const val EXTRA_STATUS_TITLE = "status_title"
    const val EXTRA_STATUS_TEXT = "status_text"

    /** 点通知要打开的包（VPN 客户端），为空时退回本应用 */
    const val EXTRA_STATUS_PKG = "status_pkg"

    /** 隧道连上的墙钟时刻，通知用正计时显示「已连接多久」；0 表示没连 */
    const val EXTRA_STATUS_SINCE = "status_since"

    /**
     * 状态通知的当前内容，system_server 写、App 读。
     *
     * 广播只在 App 进程能被拉起时有效（被强停、后台受限都会漏），
     * 所以内容同时留一份在这里：App 下次启动时读出来补发，通知不会一直缺着。
     */
    const val STATUS_STATE_KEY = "vpn_autoswitch_status_notify"

    /** 暂停标记，由 system_server 写入，App 与磁贴只读 */
    const val PAUSED_KEY = "vpn_autoswitch_paused"

    /**
     * 模块是否正接管着 VPN（1 = 已接管且隧道应当在）。
     *
     * 磁贴靠它判断亮不亮。以前磁贴看的是 [com.vpnautoswitch.module.system.Engine.HEARTBEAT_KEY]，
     * 那只是「模块进程还在跑」——模块一直跑，所以划掉应用、VPN 已经关了，磁贴却始终亮着。
     */
    const val ACTIVE_KEY = "vpn_autoswitch_active"
}
