// Material 3 风格的条状组件：只做「扁平」这一件事，配色由 M3 调色板给
package com.vpnautoswitch.module.ui.hyper

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * M3 顶部小标题栏。
 *
 * 和 [IslandTopBar] 的区别只有两点：不画渐进模糊的背景，也不做大标题收缩——
 * Material 3 的 top app bar 就是个贴顶的纯色条，标题 22sp 常驻。
 */
@Composable
internal fun Md3TopBar(
    title: String,
    scrollBehavior: ScrollBehavior = MiuixScrollBehavior(),
) {
    TopAppBar(
        title = title,
        color = MiuixTheme.colorScheme.surface,
        scrollBehavior = scrollBehavior,
        navigationIcon = {},
    )
}
