package com.vpnautoswitch.module

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 只读配置提供者。
 *
 * 模块代码运行在 system_server 进程里，无法直接读取模块 App 的私有 SharedPreferences，
 * 因此由 App 进程暴露一个只读 provider，system_server 侧定时查询并缓存。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context
        val json = if (ctx == null) Config().toJson() else Config.load(ctx).toJson()
        return MatrixCursor(arrayOf(COLUMN_JSON)).apply { addRow(arrayOf<Any>(json)) }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.vpnautoswitch.config"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        /** 跟随 applicationId，避免与旧包名同时安装时 authority 冲突 */
        val AUTHORITY: String = "${BuildConfig.APPLICATION_ID}.config"
        const val COLUMN_JSON = "json"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/config")
    }
}
