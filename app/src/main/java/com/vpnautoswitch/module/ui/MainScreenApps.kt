package com.vpnautoswitch.module.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vpnautoswitch.module.Config
import com.vpnautoswitch.module.RuleConflict
import com.vpnautoswitch.module.RuleConflicts
import com.vpnautoswitch.module.ui.hyper.SectionTitle
import com.vpnautoswitch.module.ui.hyper.SettingsActionWithArrow
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 应用页：目标 / 例外列表、筛选栏、批量选择、单应用网络条件
internal fun LazyListScope.appsItems(
    state: AppState,
    query: String,
    onQueryChange: (String) -> Unit,
    targetSelection: Set<String>?,
    onTargetSelectionChange: (Set<String>?) -> Unit,
    offSelection: Set<String>?,
    onOffSelectionChange: (Set<String>?) -> Unit,
) {
    val conflict = RuleConflicts.find(state.config)
    val total = state.config.targets.size + state.config.offTargets.size
    val selecting = targetSelection != null || offSelection != null

    // 选择模式下先把筛选栏收起来，免得刚点完全选列表又被筛掉一截
    if (AppListPolicy.shouldShowSearch(total) && !selecting) {
        item {
            AppListFilterBar(
                query = query,
                onQueryChange = onQueryChange,
                sortByName = state.config.sortAppsByName,
                onToggleSort = {
                    state.save(state.config.copy(sortAppsByName = !state.config.sortAppsByName))
                },
            )
        }
    }

    if (!conflict.isEmpty) {
        item { RuleConflictCard(conflict, state::labelOf) }
    }

    item { SectionTitle("目标应用") }
    item {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionWithArrow(
                title = "添加 / 管理目标应用",
                icon = MiuixIcons.GridView,
                summary = "长按可批量管理；点列表里的应用可以为它单独设置网络条件",
                onClick = { state.openPicker(AppState.MODE_MULTI) },
            )
        }
    }
    if (state.config.targets.isEmpty()) {
        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "还没有选择应用。打开列表里的应用时会自动连接 VPN。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }
    } else {
        val shown = AppListPolicy.display(
            packages = state.config.targets,
            labelOf = state::labelOf,
            query = query,
            sortByName = state.config.sortAppsByName,
        )
        if (shown.isEmpty()) {
            item { AppListEmptyMatch() }
        }
        val sel = targetSelection
        if (sel != null) {
            item {
                AppSelectionBar(
                    count = sel.size,
                    allSelected = shown.isNotEmpty() && sel.containsAll(shown),
                    moveLabel = "移到关闭",
                    onSelectAll = {
                        onTargetSelectionChange(
                            if (shown.isNotEmpty() && sel.containsAll(shown)) emptySet()
                            else shown.toSet()
                        )
                    },
                    onCancel = { onTargetSelectionChange(null) },
                    onRemove = {
                        state.save(state.config.copy(targets = state.config.targets - sel))
                        onTargetSelectionChange(null)
                    },
                    onMove = {
                        // 移动即「关优先」：挪过去的同时从目标列表里摘掉
                        val (next, _) = RuleConflicts.keepOff(
                            state.config,
                            state.config.offTargets + sel,
                        )
                        state.save(next)
                        onTargetSelectionChange(null)
                    },
                )
            }
        }
        shown.forEach { pkg ->
            item {
                AppToggleCard(
                    state = state,
                    pkg = pkg,
                    selecting = sel != null,
                    checked = sel?.contains(pkg) == true,
                    onRemove = {
                        state.save(state.config.copy(targets = state.config.targets - pkg))
                    },
                    onEnterSelection = { onTargetSelectionChange(setOf(pkg)) },
                    onToggleRow = {
                        val current = sel ?: emptySet()
                        onTargetSelectionChange(
                            if (current.contains(pkg)) current - pkg else current + pkg
                        )
                    },
                    networkEditable = true,
                )
            }
        }
    }

    item { SectionTitle("打开时关闭 VPN") }
    item {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionWithArrow(
                title = "添加 / 管理关闭列表",
                icon = MiuixIcons.Hide,
                summary = "长按列表项可批量移除或移动",
                onClick = { state.openPicker(AppState.MODE_OFF) },
            )
        }
    }
    if (state.config.offTargets.isEmpty()) {
        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "还没有选择应用。打开列表里的应用时会立即断开 VPN。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }
    } else {
        val shown = AppListPolicy.display(
            packages = state.config.offTargets,
            labelOf = state::labelOf,
            query = query,
            sortByName = state.config.sortAppsByName,
        )
        if (shown.isEmpty()) {
            item { AppListEmptyMatch() }
        }
        val sel = offSelection
        if (sel != null) {
            item {
                AppSelectionBar(
                    count = sel.size,
                    allSelected = shown.isNotEmpty() && sel.containsAll(shown),
                    moveLabel = "移到目标",
                    onSelectAll = {
                        onOffSelectionChange(
                            if (shown.isNotEmpty() && sel.containsAll(shown)) emptySet()
                            else shown.toSet()
                        )
                    },
                    onCancel = { onOffSelectionChange(null) },
                    onRemove = {
                        state.save(state.config.copy(offTargets = state.config.offTargets - sel))
                        onOffSelectionChange(null)
                    },
                    onMove = {
                        val (next, _) = RuleConflicts.keepOn(
                            state.config,
                            state.config.targets + sel,
                        )
                        state.save(next)
                        onOffSelectionChange(null)
                    },
                )
            }
        }
        shown.forEach { pkg ->
            item {
                AppToggleCard(
                    state = state,
                    pkg = pkg,
                    selecting = sel != null,
                    checked = sel?.contains(pkg) == true,
                    onRemove = {
                        state.save(state.config.copy(offTargets = state.config.offTargets - pkg))
                    },
                    onEnterSelection = { onOffSelectionChange(setOf(pkg)) },
                    onToggleRow = {
                        val current = sel ?: emptySet()
                        onOffSelectionChange(
                            if (current.contains(pkg)) current - pkg else current + pkg
                        )
                    },
                )
            }
        }
    }

    item { SectionTitle("VPN 客户端") }
    item {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionWithArrow(
                title = "默认 VPN",
                icon = MiuixIcons.Pin,
                summary = vpnSummary(state),
                onClick = { state.openPicker(AppState.MODE_SINGLE) },
            )
        }
    }
}

/**
 * 批量选择时的操作条。
 *
 * 只在长按进入选择模式后出现，平时应用页的形态一点不变。
 */
@Composable
private fun AppSelectionBar(
    count: Int,
    allSelected: Boolean,
    moveLabel: String,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onMove: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = if (count == 0) "选择要处理的应用" else "已选 $count 项",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            // 四个按钮等宽平分一行：文案压到 4 字以内，minWidth 归零、内边距收窄，窄屏也不会换行
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BulkActionButton(
                    text = if (allSelected) "取消全选" else "全选",
                    onClick = onSelectAll,
                    modifier = Modifier.weight(1f),
                )
                BulkActionButton(
                    text = "移除",
                    enabled = count > 0,
                    onClick = onRemove,
                    modifier = Modifier.weight(1f),
                )
                BulkActionButton(
                    text = moveLabel,
                    enabled = count > 0,
                    onClick = onMove,
                    modifier = Modifier.weight(1f),
                )
                BulkActionButton(
                    text = "退出",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 选择模式下的等宽按钮：复用 Miuix 的 TextButton 拿主题配色，只把最小宽度和内边距压掉，四个刚好一行 */
@Composable
private fun BulkActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minWidth = 0.dp,
        minHeight = 36.dp,
        insideMargin = PaddingValues(horizontal = 2.dp),
        textStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    )
}

/** 应用页顶部的筛选框 + 排序切换，只在已选应用较多时出现 */
@Composable
private fun AppListFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    sortByName: Boolean,
    onToggleSort: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            label = "筛选已选应用",
            modifier = Modifier.weight(1f),
        )
        // 排序按钮给足宽高：默认只包住三个字，和左边的输入框比显得又窄又矮
        TextButton(
            text = if (sortByName) "按名称" else "按顺序",
            onClick = onToggleSort,
            modifier = Modifier.height(52.dp),
            minWidth = 84.dp,
            insideMargin = PaddingValues(horizontal = 12.dp),
        )
    }
}

/**
 * 同一个应用 / Wi-Fi 同时命中「开」「关」两组规则。
 *
 * 运行时按「关」优先，所以不会出错，只是行为和用户的预期可能相反，
 * 这里把原因写在界面上，省得他去翻日志猜。
 */
@Composable
private fun RuleConflictCard(conflict: RuleConflict, labelOf: (String) -> String) {
    val colors = MiuixTheme.colorScheme
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                text = "规则冲突",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.error,
            )
            if (conflict.apps.isNotEmpty()) {
                Text(
                    text = "${conflict.apps.size} 个应用同时在两个列表里，打开时按「关闭」处理：" +
                        conflict.apps.take(4).joinToString("、") { labelOf(it) } +
                        if (conflict.apps.size > 4) " 等" else "",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (conflict.wifi.isNotEmpty()) {
                Text(
                    text = "${conflict.wifi.size} 个 Wi-Fi 同时在开启和关闭名单里，按「关闭」处理：" +
                        conflict.wifi.take(4).joinToString("、") +
                        if (conflict.wifi.size > 4) " 等" else "",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                text = "重新保存任一列表会按「关闭优先」自动去重。",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 筛选后一个都不剩，但列表本身非空 */
@Composable
private fun AppListEmptyMatch() {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "没有匹配的应用",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

/** 单个应用一张卡：应用图标 + 名称 + 开关，关掉即从列表移除 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppToggleCard(
    state: AppState,
    pkg: String,
    selecting: Boolean = false,
    checked: Boolean = false,
    onRemove: () -> Unit,
    onEnterSelection: () -> Unit = {},
    onToggleRow: () -> Unit = {},
    /** 只有「目标应用」列表能改网络条件；「打开时关闭」列表不参与自动开启，改了没意义 */
    networkEditable: Boolean = false,
) {
    val icon = rememberAppIcon(pkg, state.apps.firstOrNull { it.packageName == pkg }?.activityName)
    val haptic = LocalHapticFeedback.current
    var showNetwork by remember { mutableStateOf(false) }
    val netMode = if (networkEditable) state.config.networkModeOf(pkg) else Config.NET_FOLLOW
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 长按进入批量选择；平时点整行打开「网络条件」，开关仍由右侧 Switch 负责
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        if (selecting) onToggleRow() else if (networkEditable) showNetwork = true
                    },
                    onLongClick = {
                        if (!selecting) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onEnterSelection()
                        }
                    },
                )
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.labelOf(pkg).firstOrNull()?.toString() ?: "?",
                        fontSize = 17.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = state.labelOf(pkg), fontSize = 16.sp)
                Text(
                    text = pkg,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // 有覆盖时把条件写在包名下面：不然用户点过一次就忘了这个应用被单独设过
                if (netMode.isNotEmpty()) {
                    Text(
                        text = netModeLabel(netMode),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (selecting) {
                Checkbox(
                    state = if (checked) ToggleableState.On else ToggleableState.Off,
                    onClick = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
            } else {
                Switch(
                    checked = true,
                    onCheckedChange = { if (!it) onRemove() },
                )
            }
        }
    }
    if (showNetwork) {
        AppNetworkDialog(state, pkg) { showNetwork = false }
    }
}

/** 「仅 Wi-Fi / 仅移动数据 / 都允许」在列表里的短标签 */
private fun netModeLabel(mode: String): String = when (mode) {
    Config.NET_WIFI -> "仅 Wi-Fi 下生效"
    Config.NET_MOBILE -> "仅移动数据下生效"
    Config.NET_ANY -> "所有网络下生效"
    else -> ""
}

/**
 * 单个应用的网络条件。
 *
 * 全局的两个开关仍是默认值，这里只给「这个应用不一样」的场景留出口：
 * 下载工具只在 Wi-Fi 下连、银行类在移动数据下也要连，都是很具体的诉求。
 */
@Composable
private fun AppNetworkDialog(state: AppState, pkg: String, onDismiss: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val current = state.config.networkModeOf(pkg)
    val follow = buildString {
        append("Wi-Fi ")
        append(if (state.config.allowWifi) "允许" else "禁止")
        append(" · 移动数据 ")
        append(if (state.config.allowMobile) "允许" else "禁止")
    }
    val options = listOf(
        Triple(Config.NET_FOLLOW, "跟随全局", follow),
        Triple(Config.NET_WIFI, "仅 Wi-Fi 下生效", "移动数据下打开它不会连 VPN"),
        Triple(Config.NET_MOBILE, "仅移动数据下生效", "Wi-Fi 下打开它不会连 VPN"),
        Triple(Config.NET_ANY, "所有网络下生效", "不受全局开关限制，任何网络都连"),
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = state.labelOf(pkg),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "只影响这一个应用，其它应用仍按「网络条件」里的全局设置走。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(10.dp))
            options.forEach { (mode, title, summary) ->
                val selected = mode == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            val next = if (mode == Config.NET_FOLLOW) {
                                state.config.networkOverride - pkg
                            } else {
                                state.config.networkOverride + (pkg to mode)
                            }
                            state.save(state.config.copy(networkOverride = next))
                            onDismiss()
                        }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        )
                        Text(
                            text = summary,
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    if (selected) {
                        Text(text = "✓", fontSize = 15.sp, color = colors.primary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            AppButton(text = "关闭", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}
