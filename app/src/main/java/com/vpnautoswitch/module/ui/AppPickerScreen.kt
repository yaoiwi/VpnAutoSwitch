package com.vpnautoswitch.module.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpnautoswitch.module.apps.AppInfo
import com.vpnautoswitch.module.ui.hyper.BarBackdropContent
import com.vpnautoswitch.module.ui.hyper.BarBlurHost
import com.vpnautoswitch.module.ui.hyper.IslandTopBar
import com.vpnautoswitch.module.ui.hyper.PageList
import com.vpnautoswitch.module.ui.hyper.SectionTitle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppPickerScreen(state: AppState) {
    BackHandler { state.closePicker() }

    var query by remember { mutableStateOf("") }
    val single = state.pickerMode == AppState.MODE_SINGLE
    val offMode = state.pickerMode == AppState.MODE_OFF

    val title = when {
        single -> "选择默认 VPN"
        offMode -> "打开时关闭 VPN"
        else -> "选择目标应用"
    }

    val shown = remember(state.apps, query, single, state.pickerSelection) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            state.apps
        } else {
            state.apps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        // 已选中的应用置顶：勾选后立刻跳到最前面，取消后回到原来的位置。
        val picked = state.pickerSelection
        filtered.sortedWith(
            compareBy<AppInfo> { if (picked.contains(it.packageName)) 0 else 1 }
                .thenBy { if (single) !it.hasVpnService else false }
        )
    }

    val scrollBehavior = MiuixScrollBehavior()

    BarBlurHost(enabled = true, captureForEffects = true, liquidGlassEnabled = true) {
        Scaffold(
            containerColor = MiuixTheme.colorScheme.surface,
            topBar = {
                IslandTopBar(
                    title = title,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = { state.closePicker() }) {
                            Icon(MiuixIcons.Back, contentDescription = "返回")
                        }
                    },
                    actions = {
                        TextButton(text = "完成", onClick = { state.confirmPicker() })
                    },
                )
            },
        ) { padding ->
            BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                PageList(
                    scrollBehavior = scrollBehavior,
                    padding = padding,
                    bottomInset = 0.dp,
                ) {
                    item {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            label = "搜索应用",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                        )
                        if (offMode) {
                            Text(
                                text = "打开这些应用时，如果 VPN 正在连接就自动断开，并在它们退出前保持关闭。" +
                                    "与「目标应用」重叠时以关闭为准。",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }

                    if (state.appsLoading && state.apps.isEmpty()) {
                        item {
                            Text(
                                text = "正在加载应用列表…",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    } else if (shown.isEmpty()) {
                        item {
                            Text(
                                text = "没有匹配的应用",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }

                    item {
                        SectionTitle(if (single) "全部应用" else "应用列表")
                        Card(modifier = Modifier.fillMaxWidth()) {
                            shown.forEach { app ->
                                AppRow(
                                    app = app,
                                    selected = state.pickerSelection.contains(app.packageName),
                                    showCheckbox = !single,
                                    onClick = {
                                        state.toggleSelection(app.packageName)
                                        if (single) state.confirmPicker()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    selected: Boolean,
    showCheckbox: Boolean,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = app.label,
        summary = if (app.hasVpnService) "${app.packageName} · 支持 VpnService" else app.packageName,
        startAction = {
            if (app.icon != null) {
                Icon(
                    bitmap = app.icon,
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(38.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = app.label.firstOrNull()?.toString() ?: "?",
                        fontSize = 16.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        },
        endActions = {
            if (showCheckbox) {
                Checkbox(
                    state = if (selected) ToggleableState.On else ToggleableState.Off,
                    onClick = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        },
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        onClick = onClick,
    )
}
