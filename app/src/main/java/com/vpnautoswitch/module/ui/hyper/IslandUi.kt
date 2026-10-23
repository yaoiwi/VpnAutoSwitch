// 页面骨架 / 行组件，样式取自 HyperIsland 3.2.1（Apache-2.0）的 MiuixComponents + PreferenceComponents
package com.vpnautoswitch.module.ui.hyper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 顶部大标题栏（上滑时收缩，背后是渐进模糊） */
@Composable
internal fun IslandTopBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    subtitle: String = "",
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    BlurredBar(topGradient = true) {
        TopAppBar(
            title = title,
            largeTitle = title,
            subtitle = subtitle,
            color = Color.Transparent,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon ?: {},
            actions = actions,
        )
    }
}

/** 页面列表：16dp 横向边距、12dp 行间距，底部避开悬浮底栏 */
@Composable
internal fun PageList(
    scrollBehavior: ScrollBehavior,
    padding: PaddingValues,
    bottomInset: Dp,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val list: @Composable () -> Unit = {
        LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            end = 16.dp,
            bottom = bottomInset + 32.dp,
        ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
    if (onRefresh != null) {
        PullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding()),
            topAppBarScrollBehavior = scrollBehavior,
            refreshTexts = listOf("", "", "", ""),
        ) {
            list()
        }
    } else {
        list()
    }
}

@Composable
internal fun SectionTitle(title: String) {
    SmallTitle(
        text = title,
        modifier = Modifier.padding(top = 4.dp),
        insideMargin = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
    )
}

/** 普通行（可带左右图标） */
@Composable
internal fun SettingsAction(
    title: String,
    icon: ImageVector? = null,
    summary: String? = null,
    endIcon: ImageVector? = null,
    endIconSize: Dp = 20.dp,
    endContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        startAction = icon?.let { image -> { SettingsIcon(image) } },
        endActions = {
            if (endContent != null) {
                endContent()
            } else if (endIcon != null) {
                Icon(
                    imageVector = endIcon,
                    contentDescription = null,
                    modifier = Modifier.size(endIconSize),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        },
        insideMargin = SettingsItemMargin,
        enabled = enabled,
        onClick = onClick,
    )
}

/** 带箭头的跳转行 */
@Composable
internal fun SettingsActionWithArrow(
    title: String,
    icon: ImageVector? = null,
    summary: String? = null,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        startAction = icon?.let { image -> { SettingsIcon(image) } },
        endActions = {
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(width = 10.dp, height = 16.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        },
        insideMargin = SettingsItemMargin,
        onClick = onClick,
    )
}

/** 开关行 */
@Composable
internal fun SettingsSwitch(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        enabled = enabled,
        startAction = icon?.let { image -> { SettingsIcon(image) } },
        insideMargin = SettingsItemMargin,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
internal fun SettingsIcon(imageVector: ImageVector) {
    Icon(
        modifier = Modifier.padding(end = 16.dp),
        imageVector = imageVector,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onBackground,
    )
}

internal val SettingsItemMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
