package com.vpnautoswitch.module.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vpnautoswitch.module.Config
import com.vpnautoswitch.module.LauncherIcon
import com.vpnautoswitch.module.RuleConflicts
import com.vpnautoswitch.module.ui.hyper.SectionTitle
import com.vpnautoswitch.module.ui.hyper.SettingsActionWithArrow
import com.vpnautoswitch.module.ui.hyper.SettingsItemMargin
import com.vpnautoswitch.module.ui.hyper.SettingsSwitch
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Carrier
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.HorizontalSplit
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 设置页：分组开关、SSID 规则、熄屏时长、运行日志
/** 设置页顶部的搜索框，输入即过滤分组 */
@Composable
private fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        label = "搜索设置",
    )
}

/**
 * 可折叠的设置分组标题。
 *
 * 标题本身就是一行普通设置项，点一下展开 / 收起。搜索时传 `collapsible = false`：
 * 全部展开、也不显示「展开 / 收起」，否则搜到命中项却藏在折叠里。
 *
 * `content` 不带 receiver：分组内的开关直接铺在同一张卡片的 Column 里，
 * 不额外套一层 Column，卡片原有的分割线与圆角都不受影响。
 */
@Composable
private fun SettingsGroup(
    title: String,
    summary: String,
    expanded: Boolean,
    collapsible: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        endActions = {
            if (collapsible) {
                Text(
                    text = if (expanded) "收起" else "展开",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        },
        insideMargin = SettingsItemMargin,
        onClick = { if (collapsible) onToggle() },
    )
    if (expanded) content()
}

internal fun LazyListScope.settingsItems(
    state: AppState,
    showLog: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    /** null 表示未搜索、显示全部；否则只显示集合内的分组 */
    visibleSections: Set<String>?,
    onToggleLog: () -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onEditSsid: (String) -> Unit,
    context: Context,
) {
    fun visible(title: String): Boolean = visibleSections == null || title in visibleSections
    // 设置页要拿它写开关文案；这里是 LazyListScope 扩展函数，取不到 CompositionLocal，
    // 所以直接看配置
    val md3 = themeStyleOf(state.config.themeStyle) == ThemeStyle.Md3

    item {
        SettingsSearchField(query = query, onQueryChange = onQueryChange)
    }

    if (visibleSections != null && visibleSections.isEmpty()) {
        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "没有找到「$query」相关的设置",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    if (visible("网络条件")) item {
        SectionTitle("网络条件")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SettingsSwitch(
                title = "Wi-Fi 网络下生效",
                summary = "连接 Wi-Fi 时才自动开关 VPN",
                icon = MiuixIcons.Share,
                checked = state.config.allowWifi,
                onCheckedChange = { state.save(state.config.copy(allowWifi = it)) },
            )
            SettingsSwitch(
                title = "移动数据网络下生效",
                summary = "使用移动数据时才自动开关 VPN",
                icon = MiuixIcons.Carrier,
                checked = state.config.allowMobile,
                onCheckedChange = { state.save(state.config.copy(allowMobile = it)) },
            )
            SettingsActionWithArrow(
                title = "指定 Wi-Fi 开 VPN",
                icon = MiuixIcons.Share,
                summary = ssidSummary(state.config.wifiOn, "连上这些 Wi-Fi 时始终开启 VPN"),
                onClick = { onEditSsid("on") },
            )
            SettingsActionWithArrow(
                title = "指定 Wi-Fi 关 VPN",
                icon = MiuixIcons.Blocklist,
                summary = ssidSummary(state.config.wifiOff, "连上这些 Wi-Fi 时关闭 VPN 且不再自动开启"),
                onClick = { onEditSsid("off") },
            )
            if (!state.config.allowWifi && !state.config.allowMobile) {
                Text(
                    text = "两个都关闭后，任何网络下都不会自动开关 VPN",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = "上面两个是全局默认。单个应用要例外时，去「目标应用」点那一行单独设置。",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    if (visible("外观")) item {
        SectionTitle("外观")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            val systemDark = isSystemInDarkTheme()
            val dark = when (state.config.darkMode) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            SettingsSwitch(
                title = "深色模式",
                summary = when (state.config.darkMode) {
                    "system" -> "当前跟随系统（${if (systemDark) "深色" else "浅色"}），切换后固定"
                    "dark" -> "已固定为深色，不再跟随系统"
                    else -> "已固定为浅色，不再跟随系统"
                },
                icon = MiuixIcons.Background,
                checked = dark,
                onCheckedChange = { on ->
                    state.save(
                        state.config.copy(darkMode = if (on) "dark" else "light")
                    )
                },
            )
            SettingsSwitch(
                title = "跟随系统深浅",
                summary = "打开后由系统的深色模式开关决定界面配色",
                icon = MiuixIcons.Theme,
                checked = state.config.darkMode == "system",
                onCheckedChange = { on ->
                    state.save(
                        state.config.copy(
                            darkMode = if (on) "system" else if (dark) "dark" else "light"
                        )
                    )
                },
            )
            SettingsSwitch(
                title = "Material 3 界面",
                summary = if (md3) {
                    "当前是 Material 3：可跟随壁纸取色，卡片与按钮换成 M3 圆角"
                } else {
                    "打开后换成 Material 3 配色与扁平卡片；关闭为 HyperOS 风格"
                },
                icon = MiuixIcons.Album,
                checked = md3,
                onCheckedChange = { on ->
                    state.save(
                        state.config.copy(
                            themeStyle = if (on) Config.THEME_MD3 else Config.THEME_HYPER
                        )
                    )
                },
            )
            if (md3) {
                // 壁纸取色只有 Android 12 起才有，低版本把这个开关灰掉并说明原因，
                // 免得用户以为是坏了
                val paletteOk = Build.VERSION.SDK_INT >= WALLPAPER_PALETTE_MIN_SDK
                SettingsSwitch(
                    title = "跟随壁纸取色",
                    summary = when {
                        !paletteOk -> "需要 Android 12 及以上，当前系统没有壁纸取色接口"
                        state.config.md3Wallpaper -> "配色取自系统壁纸，换壁纸或主题色会一起变"
                        else -> "已固定为紫调基线色，打开后跟随壁纸"
                    },
                    icon = MiuixIcons.Theme,
                    checked = state.config.md3Wallpaper && paletteOk,
                    enabled = paletteOk,
                    onCheckedChange = { on ->
                        state.save(state.config.copy(md3Wallpaper = on))
                    },
                )
            }
            // 桌面入口由 manifest 里独立的 activity-alias 承载，关掉它不影响
            // MainActivity 的 MODULE_SETTINGS 入口，所以隐藏后还能从 LSPosed 管理器回来恢复
            var iconHidden by remember { mutableStateOf(LauncherIcon.isHidden(context)) }
            // 也可能是在别处（adb / 管理器）改过状态，每次滚回本页重新以系统为准
            LaunchedEffect(Unit) { iconHidden = LauncherIcon.isHidden(context) }
            SettingsSwitch(
                title = "隐藏桌面图标",
                summary = if (iconHidden) {
                    "图标已从桌面与应用抽屉移除；仍可从 LSPosed 管理器打开本页恢复"
                } else {
                    "从桌面与应用抽屉中移除图标，隐藏后仍可从 LSPosed 管理器打开本页"
                },
                icon = if (iconHidden) MiuixIcons.Hide else MiuixIcons.Home,
                checked = iconHidden,
                onCheckedChange = { on ->
                    LauncherIcon.setHidden(context, on)
                    // 以系统回读为准：改组件状态的返回值靠不住（对不存在的组件也是静默成功），
                    // 回读不一致说明没生效，这时开关停在旧状态，提示也说失败
                    val hidden = LauncherIcon.isHidden(context)
                    iconHidden = hidden
                    Toast.makeText(
                        context,
                        when {
                            hidden != on -> "修改桌面图标失败"
                            on -> "桌面图标已隐藏"
                            else -> "桌面图标已恢复"
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
            // 悬浮底栏归属当前风格：两套各记各的，切主题不会把另一边的选择改掉
            SettingsSwitch(
                title = "悬浮导航栏",
                summary = if (md3) {
                    "只影响 Material 3 界面：开启后底栏变成液态玻璃悬浮胶囊，关闭则贴底"
                } else {
                    "只影响 HyperOS 界面：开启时使用液态玻璃悬浮底栏，关闭后贴底"
                },
                icon = MiuixIcons.HorizontalSplit,
                checked = floatingBarOf(state.config, md3),
                onCheckedChange = { on ->
                    state.save(
                        if (md3) {
                            state.config.copy(md3FloatingBottomBar = on)
                        } else {
                            state.config.copy(floatingBottomBar = on)
                        }
                    )
                },
            )
        }
    }
    if (visible("界面与动画")) item {
        SectionTitle("界面与动画")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SettingsSwitch(
                title = "切页滑动动画",
                summary = "左右划到别的页面时平滑过渡；关闭后瞬时切换",
                icon = MiuixIcons.Layers,
                checked = state.config.pageAnimation,
                onCheckedChange = { state.save(state.config.copy(pageAnimation = it)) },
            )
            SettingsSwitch(
                title = "手势滑动切页",
                summary = "在内容区左右滑动即可切换页面",
                icon = MiuixIcons.HorizontalSplit,
                checked = state.config.swipePaging,
                onCheckedChange = { state.save(state.config.copy(swipePaging = it)) },
            )
            SettingsSwitch(
                title = "震动反馈",
                summary = "点击底栏标签与关于页条目时轻微震动",
                icon = MiuixIcons.VolumeUp,
                checked = state.config.hapticFeedback,
                onCheckedChange = { state.save(state.config.copy(hapticFeedback = it)) },
            )
        }
    }

    if (visible("高级")) item {
        SectionTitle("高级")
        // 十个开关挤在一张卡里，想找「保留客户端进程」得从头滑到尾。
        // 按用途分三组，默认只展开最常用的「关闭与断开」；搜索时全部展开，免得匹配到却看不见。
        val searching = visibleSections != null
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SettingsGroup(
                title = "关闭与断开",
                summary = "划掉任务怎么断、断开后客户端留不留",
                expanded = searching || AppState.ADV_GROUP_CLOSE in state.advancedGroups,
                collapsible = !searching,
                onToggle = { state.toggleAdvancedGroup(AppState.ADV_GROUP_CLOSE) },
            ) {
                SettingsSwitch(
                    title = "划掉任务即断开",
                    summary = "从最近任务划掉目标应用时，即使前台服务让进程还活着，也断开 VPN",
                    icon = MiuixIcons.HorizontalSplit,
                    checked = state.config.disconnectOnTaskRemoved,
                    onCheckedChange = {
                        state.save(state.config.copy(disconnectOnTaskRemoved = it))
                    },
                )
                SettingsSwitch(
                    title = "划掉任意目标即断开",
                    summary = "目标应用都在后台时，划掉其中任意一个就断开 VPN，不必是刚才在用的那个；" +
                        "关掉后只有划掉正在用的那个才断开",
                    icon = MiuixIcons.GridView,
                    checked = state.config.disconnectOnAnyDismiss,
                    enabled = state.config.disconnectOnTaskRemoved,
                    onCheckedChange = {
                        state.save(state.config.copy(disconnectOnAnyDismiss = it))
                    },
                )
                SettingsSwitch(
                    title = "关闭后继续抑制",
                    summary = "外部控制模式下关闭 VPN 后观察 8 秒，客户端自己又把 VPN 拉起来时再关一次",
                    icon = MiuixIcons.Lock,
                    checked = state.config.offGuard,
                    onCheckedChange = { state.save(state.config.copy(offGuard = it)) },
                )
                SettingsSwitch(
                    title = "关闭 VPN 后保留客户端进程",
                    summary = if (state.config.forceStopImmediately) {
                        "已关闭：上面开了「直接强制停止」，进程会被杀掉，两者不能同时生效"
                    } else {
                        "断开后让 VPN 客户端留在内存，下次打开目标应用无需冷启动，通常快 1 秒以上；" +
                            "代价是客户端常驻后台，会多一些耗电。开了「关闭后继续抑制」时会等抑制窗口结束再预热；" +
                            "若客户端一被唤醒就自己连上，会自动改为不再预热。"
                    },
                    icon = MiuixIcons.Pin,
                    checked = state.config.keepClientAlive && !state.config.forceStopImmediately,
                    enabled = !state.config.forceStopImmediately,
                    onCheckedChange = { state.save(state.config.copy(keepClientAlive = it)) },
                )
                SettingsSwitch(
                    title = "关闭时恢复原有常驻 VPN 设置",
                    summary = "关闭后会自动还原你原本的「始终开启的 VPN」配置",
                    icon = MiuixIcons.Reset,
                    checked = state.config.restoreOriginal,
                    onCheckedChange = { state.save(state.config.copy(restoreOriginal = it)) },
                )
                SettingsSwitch(
                    title = "锁屏后断开 VPN",
                    summary = "手机锁屏超过设定时间后自动断开，省流量也省电；" +
                        "解锁后目标应用还在运行就重新连上，默认关闭",
                    icon = MiuixIcons.Lock,
                    checked = state.config.disconnectOnScreenOff,
                    onCheckedChange = {
                        state.save(state.config.copy(disconnectOnScreenOff = it))
                    },
                )
                var showScreenOffPicker by remember { mutableStateOf(false) }
                SettingsActionWithArrow(
                    title = "锁屏断开等待时间",
                    summary = if (state.config.disconnectOnScreenOff) {
                        "${state.config.screenOffMinutes} 分钟后断开"
                    } else {
                        "先打开上面的开关 · ${state.config.screenOffMinutes} 分钟"
                    },
                    onClick = { if (state.config.disconnectOnScreenOff) showScreenOffPicker = true },
                )
                if (showScreenOffPicker) {
                    ScreenOffMinutesDialog(
                        current = state.config.screenOffMinutes,
                        onPick = {
                            state.save(state.config.copy(screenOffMinutes = it))
                            showScreenOffPicker = false
                        },
                        onDismiss = { showScreenOffPicker = false },
                    )
                }
            }

            SettingsGroup(
                title = "兼容性",
                summary = "客户端拉不起来、关不掉时才需要动",
                expanded = searching || AppState.ADV_GROUP_COMPAT in state.advancedGroups,
                collapsible = !searching,
                onToggle = { state.toggleAdvancedGroup(AppState.ADV_GROUP_COMPAT) },
            ) {
                SettingsSwitch(
                    title = "锁定模式（Lockdown）",
                    summary = "VPN 断开时禁止一切网络访问，可能导致断网，通常无需开启",
                    icon = MiuixIcons.Lock,
                    checked = state.config.lockdown,
                    onCheckedChange = { state.save(state.config.copy(lockdown = it)) },
                )
                SettingsSwitch(
                    title = "允许短暂打开 VPN 客户端界面",
                    summary = "默认全程在后台启动；只有客户端不支持后台拉起时才需要打开这个开关",
                    icon = MiuixIcons.Show,
                    checked = state.config.allowVpnUi,
                    onCheckedChange = { state.save(state.config.copy(allowVpnUi = it)) },
                )
                SettingsSwitch(
                    title = "断不开时强制停止 VPN 应用",
                    summary = "清除设置后 VPN 仍连着（例如你手动连上的），直接结束 VPN 应用进程",
                    icon = MiuixIcons.Delete,
                    checked = state.config.forceStopVpn,
                    onCheckedChange = { state.save(state.config.copy(forceStopVpn = it)) },
                )
                SettingsSwitch(
                    title = "关闭时直接强制停止",
                    summary = "跳过停止广播和快捷磁贴，一步杀掉客户端进程；" +
                        "最快，但客户端会完全退出，「保留客户端进程」随之失效",
                    icon = MiuixIcons.Blocklist,
                    checked = state.config.forceStopImmediately,
                    enabled = state.config.forceStopVpn,
                    onCheckedChange = {
                        state.save(state.config.copy(forceStopImmediately = it))
                    },
                )
            }

            SettingsGroup(
                title = "提示与通知",
                summary = "屏幕提示与系统那条常驻通知",
                expanded = searching || AppState.ADV_GROUP_HINTS in state.advancedGroups,
                collapsible = !searching,
                onToggle = { state.toggleAdvancedGroup(AppState.ADV_GROUP_HINTS) },
            ) {
                SettingsSwitch(
                    title = "常驻状态通知",
                    summary = "在通知栏显示 VPN 是否连接、命中了哪条规则",
                    icon = MiuixIcons.Info,
                    checked = state.config.statusNotification,
                    onCheckedChange = { on ->
                        // 通知由本应用发，Android 13 起要先拿到运行时授权，
                        // 否则 notify 会被系统静默丢弃、开关看着开了却没东西
                        if (on) requestNotificationPermission(context)
                        state.save(state.config.copy(statusNotification = on))
                    },
                )
                SettingsSwitch(
                    title = "显示开关提示（Toast）",
                    summary = "VPN 开启 / 关闭时在屏幕上弹出提示",
                    icon = MiuixIcons.Messages,
                    checked = state.config.toastEnabled,
                    onCheckedChange = { state.save(state.config.copy(toastEnabled = it)) },
                )
                SettingsSwitch(
                    title = "隐藏「始终开启的 VPN 已断开」通知",
                    summary = "VPN 关闭时不再显示系统那条提示；需要诊断时可临时关掉",
                    icon = MiuixIcons.Blocklist,
                    checked = state.config.hideVpnNotification,
                    onCheckedChange = { state.save(state.config.copy(hideVpnNotification = it)) },
                )
            }
        }
    }

    if (visible("备份与恢复")) item {
        SectionTitle("备份与恢复")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionWithArrow(
                title = "导出配置",
                icon = MiuixIcons.UploadCloud,
                summary = "保存为 JSON 文件，方便备份或换机",
                onClick = onExport,
            )
            SettingsActionWithArrow(
                title = "导入配置",
                icon = MiuixIcons.Download,
                summary = "从备份的 JSON 文件恢复",
                onClick = onImport,
            )
        }
    }

    if (visible("状态诊断")) item {
        SectionTitle("状态诊断")
        SelfCheckSection(state)
    }

    if (visible("运行日志")) item {
        SectionTitle("运行日志")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            val lines = state.diagnostics.log.lines().filter { it.isNotBlank() }
            SettingsActionWithArrow(
                title = if (showLog) "收起运行日志" else "展开运行日志",
                icon = MiuixIcons.Notes,
                summary = if (lines.isEmpty()) "暂无记录" else "最近 ${lines.size} 条",
                onClick = onToggleLog,
            )
            if (showLog) {
                LogBody(state = state, lines = lines, context = context, onRefresh = onRefresh)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogBody(
    state: AppState,
    lines: List<String>,
    context: Context,
    onRefresh: () -> Unit,
) {
    var onlyAbnormal by remember { mutableStateOf(false) }
    val shown = LogFilter.select(lines, onlyAbnormal)
    val copyText = shown.joinToString("\n")
    val copyLog = {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("VpnAutoSwitch", copyText))
        Toast.makeText(context, "已复制 ${shown.size} 条", Toast.LENGTH_SHORT).show()
    }
    // 导出走系统的「新建文档」：不需要存储权限，位置由用户自己选
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(copyText.toByteArray())
            }
            true
        } catch (t: Throwable) {
            false
        }
        Toast.makeText(
            context,
            if (ok) "日志已导出（${shown.size} 条）" else "导出失败",
            Toast.LENGTH_SHORT,
        ).show()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 四个等宽按钮，宽度一致才不会在窄屏上互相挤压
        AppButton(
            text = "复制",
            onClick = copyLog,
            modifier = Modifier.weight(1f),
        )
        AppButton(
            text = "分享",
            onClick = {
                shareText(
                    context,
                    "VpnAutoSwitch 运行日志",
                    // 只发筛选后正在看的那部分；用户点「只看异常」时，发出去的也就是异常行
                    copyText,
                )
            },
            modifier = Modifier.weight(1f),
        )
        AppButton(
            text = "导出",
            onClick = { exportLauncher.launch("vpn-autoswitch-log.txt") },
            modifier = Modifier.weight(1f),
        )
        AppButton(
            text = "刷新",
            onClick = onRefresh,
            modifier = Modifier.weight(1f),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppButton(
            text = if (onlyAbnormal) "只看异常 ✓" else "只看异常",
            onClick = { onlyAbnormal = !onlyAbnormal },
        )
        Text(
            text = if (onlyAbnormal) "筛出 ${shown.size} / ${lines.size} 条" else "共 ${lines.size} 条，显示最近 60 条",
            fontSize = 11.sp,
            // weight 把「清空」推到最右，按钮不会跟着文字长度左右乱跳
            modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        AppButton(
            text = "清空",
            onClick = {
                state.clearLog()
                Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
            },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = copyLog)
            .padding(horizontal = 18.dp, vertical = 4.dp),
    ) {
        Text(
            text = "长按复制当前列表",
            fontSize = 10.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when {
                lines.isEmpty() -> "暂无日志。模块加载 / 开关 VPN / 进程结束都会记录在这里。"
                shown.isEmpty() -> "没有匹配的异常行，点「只看异常」可切回全部日志。"
                else -> shown.takeLast(60).joinToString("\n")
            },
            fontSize = 11.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ---------------------------------------------------------------- 状态卡片

private fun ssidSummary(list: Set<String>, empty: String): String =
    if (list.isEmpty()) "未设置 · $empty" else list.joinToString("、")

@Composable
internal fun SsidRuleDialog(mode: String, state: AppState, onDismiss: () -> Unit) {
    val isOn = mode == "on"
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val list = if (isOn) state.config.wifiOn else state.config.wifiOff
    val current = state.diagnostics.currentSsid

    fun update(next: Set<String>) {
        // 同一个 SSID 不能既算「始终开」又算「始终关」，保存时按关闭优先去重
        val (cfg, dropped) = if (isOn) {
            RuleConflicts.keepWifiOn(state.config, next)
        } else {
            RuleConflicts.keepWifiOff(state.config, next)
        }
        state.save(cfg)
        if (dropped > 0) {
            Toast.makeText(
                context,
                "已从另一侧名单移除 $dropped 个同名 Wi-Fi",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 580.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = if (isOn) "指定 Wi-Fi 开 VPN" else "指定 Wi-Fi 关 VPN",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isOn) {
                    "连上列表里的 Wi-Fi 时立即开启 VPN，不看当前打开了哪个应用。"
                } else {
                    "连上列表里的 Wi-Fi 时立即关闭 VPN，并且在断开前不再自动开启。"
                },
                fontSize = 12.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "当前 Wi-Fi：${current.ifEmpty { "未连接" }}",
                fontSize = 13.sp,
            )
            if (current.isNotEmpty() && !list.contains(current)) {
                AppButton(
                    text = "加入「$current」",
                    onClick = { update(list + current) },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            TextField(
                value = input,
                onValueChange = { input = it },
                label = "手动输入 Wi-Fi 名称（区分大小写）",
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                AppButton(
                    text = "添加",
                    onClick = {
                        val v = input.trim()
                        if (v.isNotEmpty()) {
                            update(list + v)
                            input = ""
                        }
                    },
                )
            }
            if (list.isEmpty()) {
                Text(
                    text = "还没有添加 Wi-Fi",
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(count = list.size) { index ->
                        val ssid = list.elementAt(index)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(text = ssid, fontSize = 14.sp)
                            AppButton(text = "删除", onClick = { update(list - ssid) })
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                AppButton(text = "关闭", onClick = onDismiss)
            }
        }
    }
}

/**
 * 锁屏断开等待时间。
 *
 * 常用档位直接列出来，省得为了一个分钟数再做个数字输入框；
 * 「亮屏重新计时」这点写在说明里，免得用户以为亮一下屏幕断开就失效了。
 */
@Composable
private fun ScreenOffMinutesDialog(
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val options = listOf(5, 10, 15, 30, 60, 120)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text("锁屏后多久断开", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "锁屏后开始计时，到点才断开；中途亮屏会重新计时。" +
                    "解锁后目标应用还在运行就自动连回来。",
                fontSize = 12.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            options.forEach { minutes ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onPick(minutes) }
                        .padding(horizontal = 6.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (minutes >= 60) "${minutes / 60} 小时" else "$minutes 分钟",
                        fontSize = 15.sp,
                    )
                    if (minutes == current) {
                        Text("当前", fontSize = 12.sp, color = colors.primary)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                AppButton(text = "取消", onClick = onDismiss)
            }
        }
    }
}

internal fun vpnSummary(state: AppState): String {
    val pkg = state.config.vpnPackage
    if (pkg.isEmpty()) return "未选择"
    val info = state.apps.firstOrNull { it.packageName == pkg }
    val warn = if (info != null && !info.hasVpnService) {
        "\n未声明 VpnService，可能无法作为 VPN 使用"
    } else {
        ""
    }
    return "${state.labelOf(pkg)}\n$pkg$warn"
}

private fun offVpnSummary(state: AppState): String {
    val list = state.config.offTargets
    if (list.isEmpty()) return "未选择。打开这些应用时，若 VPN 已连接会自动断开"
    val head = list.take(3).map { state.labelOf(it) }.joinToString("、") +
        if (list.size > 3) " 等 ${list.size} 个" else ""
    return "$head\n打开时自动断开，退出前不再自动连接"
}
