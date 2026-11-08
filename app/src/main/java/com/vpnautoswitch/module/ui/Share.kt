package com.vpnautoswitch.module.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 走系统分享面板把一段文本发出去。
 *
 * 为什么不用「导出成 txt 文件」：手机上真正顺手的路径是直接发到微信 / QQ / GitHub App，
 * 让用户先在文件管理器里找文件、再去聊天窗口里翻附件，多一步就少一半人愿意反馈。
 *
 * [subject] 只作为标题显示（邮件、部分 IM 才会用到），真正的内容在 [text]。
 */
internal fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, subject)
    // 从非 Activity 的 Context 启动必须带 NEW_TASK，否则会抛 AndroidRuntimeException
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (t: Throwable) {
        // 极少数精简系统没有分享面板：不要让按钮点了毫无反应
        Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
    }
}
