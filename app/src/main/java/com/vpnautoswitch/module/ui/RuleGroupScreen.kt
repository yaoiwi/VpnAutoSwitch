package com.vpnautoswitch.module.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vpnautoswitch.module.VpnRule
import com.vpnautoswitch.module.ui.hyper.BarBackdropContent
import com.vpnautoswitch.module.ui.hyper.BarBlurHost
import com.vpnautoswitch.module.ui.hyper.IslandTopBar
import com.vpnautoswitch.module.ui.hyper.PageList
import com.vpnautoswitch.module.ui.hyper.SectionTitle
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 规则组：一个组 = 一个客户端 + 一组应用（可选一条 Wi-Fi 条件）
//
// 同一时刻只有一组生效——Android 只有一条 VPN 隧道。所以文案里一律写「用这一组」，
// 不写「同时开」，免得用户按并行去理解。

@Composable
internal fun RuleGroupScreen(state: AppState) {
    BackHandler { state.closeRuleGroups() }

    // 卡片要显示应用名、客户端名，先把列表拉起来（选择器那边复用同一份缓存）
    LaunchedEffect(Unit) { state.loadApps() }

    val scrollBehavior = MiuixScrollBehavior()
    val rules = state.config.rules
    var creating by remember { mutableStateOf(false) }
    var wifiTarget by remember { mutableStateOf<String?>(null) }

    BarBlurHost(enabled = true, captureForEffects = true, liquidGlassEnabled = true) {
        Scaffold(
            containerColor = MiuixTheme.colorScheme.surface,
            topBar = {
                IslandTopBar(
                    title = "规则组",
                    subtitle = if (rules.isEmpty()) "按场景切换客户端" else "${rules.size} 个组",
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = { state.closeRuleGroups() }) {
                            Icon(MiuixIcons.Back, contentDescription = "返回")
                        }
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
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = if (rules.isEmpty()) {
                                        "一个组对应一个 VPN 客户端。比如「科学上网」用 Clash，" +
                                            "「内网穿透」用另一套；连上家里的 Wi-Fi 时还能单独一组走旁路由。"
                                    } else {
                                        "同一时刻只有一组生效。打开某组的应用时，模块会先关掉当前这组，" +
                                            "确认断开后再开新的——两个客户端同时抢隧道会互相把对方顶掉。"
                                    },
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }

                    item {
                        SectionTitle("默认组")
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                                RuleTitleRow(
                                    title = "默认组",
                                    pkg = state.config.vpnPackage,
                                    subtitle = clientLabel(state, state.config.vpnPackage),
                                ) {
                                    Text("${state.config.targets.size} 个应用", fontSize = 12.sp)
                                }
                                Text(
                                    text = if (rules.isEmpty()) {
                                        "还没建组，所有规则都走这里。"
                                    } else {
                                        "不属于任何规则组的应用走这里；打开这类应用会从别的组切回来。"
                                    },
                                    modifier = Modifier.padding(top = 6.dp),
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }

                    if (rules.isNotEmpty()) {
                        item { SectionTitle("规则组") }
                        items(rules, key = { it.id }) { rule ->
                            RuleCard(
                                state = state,
                                rule = rule,
                                onEditWifi = { wifiTarget = rule.id },
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        AppButton(
                            text = "新建规则组",
                            onClick = { creating = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "同一个应用只能属于一个组；两组共用一个客户端时，以列表里靠前的那个为准。",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        NewRuleDialog(
            state = state,
            onDismiss = { creating = false },
            onCreate = { name, pkg ->
                val id = state.addRule(name, pkg)
                creating = false
                // 建完直接去选应用：只建个空组没有意义
                state.openPicker(AppState.MODE_RULE_TARGETS, id)
            },
        )
    }

    wifiTarget?.let { id ->
        val rule = state.ruleOf(id)
        if (rule == null) {
            wifiTarget = null
        } else {
            RuleWifiDialog(
                state = state,
                rule = rule,
                onDismiss = { wifiTarget = null },
                onSave = { wifi ->
                    state.updateRule(id) { it.copy(wifiOn = wifi) }
                    wifiTarget = null
                },
            )
        }
    }
}

@Composable
private fun RuleCard(
    state: AppState,
    rule: VpnRule,
    onEditWifi: () -> Unit,
) {
    // 删除要点两次：没有撤销，误触一次就把这一组的应用清单丢了
    var confirmDelete by remember(rule.id) { mutableStateOf(false) }
    var renaming by remember(rule.id) { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            RuleTitleRow(
                title = rule.displayName(),
                pkg = rule.vpnPackage,
                subtitle = clientLabel(state, rule.vpnPackage),
            ) {
                Text(
                    text = if (rule.enabled) "启用" else "已停用",
                    fontSize = 12.sp,
                    color = if (rule.enabled) colors.primary else colors.onSurfaceVariantSummary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { state.updateRule(rule.id) { it.copy(enabled = !it.enabled) } }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            RuleRow(
                label = "客户端",
                value = clientLabel(state, rule.vpnPackage),
                onClick = { state.openPicker(AppState.MODE_RULE_CLIENT, rule.id) },
            )
            RuleRow(
                label = "目标应用",
                value = if (rule.targets.isEmpty()) "未选择" else "${rule.targets.size} 个",
                onClick = { state.openPicker(AppState.MODE_RULE_TARGETS, rule.id) },
            )
            RuleRow(
                label = "Wi-Fi 条件",
                value = if (rule.wifiOn.isEmpty()) "不限" else rule.wifiOn.joinToString("、"),
                onClick = onEditWifi,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(
                    text = "改名",
                    onClick = { renaming = true },
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = if (confirmDelete) "确认删除" else "删除",
                    onClick = {
                        if (confirmDelete) state.removeRule(rule.id) else confirmDelete = true
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (renaming) {
        TextInputDialog(
            title = "组名",
            label = "例如「内网穿透」",
            initial = rule.label,
            hint = "只是给你自己看的，不影响匹配",
            onDismiss = { renaming = false },
            onConfirm = { name ->
                state.updateRule(rule.id) { it.copy(label = name) }
                renaming = false
            },
        )
    }
}

@Composable
private fun RuleTitleRow(
    title: String,
    pkg: String,
    subtitle: String,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = rememberAppIcon(pkg)
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp)),
            )
            Spacer(modifier = Modifier.size(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotEmpty() && subtitle != title) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun RuleRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.4f),
        )
    }
}

/** 客户端在界面上叫什么：能查到应用名就用应用名，查不到退成包名 */
private fun clientLabel(state: AppState, pkg: String): String = when {
    pkg.isEmpty() -> "未选择"
    else -> state.apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    hint: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var value by remember { mutableStateOf(initial) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (hint.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = hint, fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = value,
                onValueChange = { value = it },
                label = label,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppButton(text = "取消", onClick = onDismiss)
                Spacer(modifier = Modifier.size(8.dp))
                AppButton(text = "保存", onClick = { onConfirm(value.trim()) })
            }
        }
    }
}

@Composable
private fun NewRuleDialog(
    state: AppState,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf("") }
    val clients = state.apps.filter { it.hasVpnService }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 600.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(text = "新建规则组", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "先选一个客户端。手机上装了多个带 VpnService 的应用时，这里才会出现多个选项。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = name,
                onValueChange = { name = it },
                label = "组名，例如「内网穿透」",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "客户端", fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Column(modifier = Modifier.heightIn(max = 260.dp)) {
                if (clients.isEmpty()) {
                    Text(
                        text = "没有检测到带 VpnService 的应用",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                clients.forEach { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { picked = app.packageName }
                            .padding(vertical = 9.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (picked == app.packageName) "●" else "○",
                            fontSize = 14.sp,
                            color = if (picked == app.packageName) {
                                colors.primary
                            } else {
                                colors.onSurfaceVariantSummary
                            },
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = app.label,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppButton(text = "取消", onClick = onDismiss)
                Spacer(modifier = Modifier.size(8.dp))
                AppButton(
                    text = "下一步",
                    onClick = { onCreate(name.trim(), picked) },
                    enabled = picked.isNotEmpty(),
                )
            }
        }
    }
}

/**
 * 组的 Wi-Fi 条件。
 *
 * 只做「连上这些 Wi-Fi 时才用这一组」，不提供扫描列表：普通 App 没有定位权限，
 * 读不到周围的 SSID，能拿到的只有模块写到 Settings 里的当前那一个。
 */
@Composable
private fun RuleWifiDialog(
    state: AppState,
    rule: VpnRule,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var list by remember(rule.id) { mutableStateOf(rule.wifiOn) }
    var input by remember { mutableStateOf("") }
    val current = state.diagnostics.currentSsid

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 600.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(text = "只在特定 Wi-Fi 下用这一组", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "不填就是任何网络下都算。填了之后只有连上这些 Wi-Fi 时这一组才会被选中——" +
                    "比如「连上家里的 Wi-Fi 就走旁路由」。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "当前 Wi-Fi：${current.ifEmpty { "未连接" }}", fontSize = 13.sp)
            if (current.isNotEmpty() && current !in list) {
                Spacer(modifier = Modifier.height(6.dp))
                AppButton(text = "加入「$current」", onClick = { list = list + current })
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                value = input,
                onValueChange = { input = it },
                label = "手动输入 Wi-Fi 名称（区分大小写）",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppButton(
                    text = "添加",
                    onClick = {
                        val v = input.trim()
                        if (v.isNotEmpty()) {
                            list = list + v
                            input = ""
                        }
                    },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.heightIn(max = 220.dp)) {
                list.forEach { ssid ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = ssid, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(
                            text = "移除",
                            fontSize = 12.sp,
                            color = colors.error,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { list = list - ssid }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppButton(text = "取消", onClick = onDismiss)
                Spacer(modifier = Modifier.size(8.dp))
                AppButton(text = "保存", onClick = { onSave(list) })
            }
        }
    }
}
