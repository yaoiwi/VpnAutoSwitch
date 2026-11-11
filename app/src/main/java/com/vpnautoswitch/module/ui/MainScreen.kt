package com.vpnautoswitch.module.ui

import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpnautoswitch.module.BuildConfig
import com.vpnautoswitch.module.ui.hyper.BarBackdropContent
import com.vpnautoswitch.module.ui.hyper.BarBlurHost
import com.vpnautoswitch.module.ui.hyper.IslandTopBar
import com.vpnautoswitch.module.ui.hyper.LiquidGlassNavigationBar
import com.vpnautoswitch.module.ui.hyper.LiquidGlassNavigationItem
import com.vpnautoswitch.module.ui.hyper.Md3TopBar
import com.vpnautoswitch.module.ui.hyper.PageList
import com.vpnautoswitch.module.ui.hyper.SectionTitle
import com.vpnautoswitch.module.ui.hyper.SettingsActionWithArrow
import com.vpnautoswitch.module.ui.hyper.SettingsSwitch
import com.vpnautoswitch.module.ui.hyper.StandardNavigationBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Carrier
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 页面骨架与首页：分页容器、顶栏、首页概览卡片
private const val PAGE_COUNT = 4

private val PAGE_TITLES = listOf("首页", "应用", "设置", "关于")

@Composable
fun MainScreen(state: AppState) {
    val context = LocalContext.current
    val view = LocalView.current
    // Material 3 风格：配色由 App 换成 M3 调色板，这里负责把「玻璃」关掉、
    // 顶栏底栏换成扁平样式，两套风格的差别才看得出来
    val md3 = isMd3()
    val haptic = {
        if (state.config.hapticFeedback) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    var showLog by remember { mutableStateOf(false) }
    /** 每次自检弹窗点「完整日志」就加一，日志区靠它决定要不要滚进视野 */
    var openLogSignal by remember { mutableIntStateOf(0) }
    var showClientAdapter by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var settingsQuery by remember { mutableStateOf("") }
    var appQuery by remember { mutableStateOf("") }
    /** 批量选择：null 表示未进入选择模式，否则是当前勾中的包名 */
    var targetSelection by remember { mutableStateOf<Set<String>?>(null) }
    var offSelection by remember { mutableStateOf<Set<String>?>(null) }
    val settingsMatches = remember(settingsQuery) { SettingsSearch.matchedTitles(settingsQuery) }
    /** null=未打开；"on"=编辑「开 VPN」列表；"off"=编辑「关 VPN」列表 */
    var ssidMode by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { state.loadApps() }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(650)
            state.refreshDiagnostics()
            refreshing = false
        }
    }

    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val listStates = remember { List(PAGE_COUNT) { LazyListState() } }

    val goTo: (Int) -> Unit = { page ->
        scope.launch {
            if (state.config.pageAnimation) {
                pagerState.animateScrollToPage(page)
            } else {
                pagerState.scrollToPage(page)
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = state.exportConfig(uri)
            Toast.makeText(context, if (ok) "配置已导出" else "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val ok = state.importConfig(uri)
            Toast.makeText(
                context,
                if (ok) "配置已导入" else "导入失败：不是有效的配置文件",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val scrollBehavior = MiuixScrollBehavior()
    val navItems = listOf(
        LiquidGlassNavigationItem(MiuixIcons.Home, "首页"),
        LiquidGlassNavigationItem(MiuixIcons.GridView, "应用"),
        LiquidGlassNavigationItem(MiuixIcons.Settings, "设置"),
        LiquidGlassNavigationItem(MiuixIcons.Info, "关于"),
    )

    // 悬浮底栏由当前风格自己的开关决定：M3 也能开。开了就需要模糊与液态玻璃，
    // 贴底时则把这两样关掉，省下每帧的截屏开销。
    val floatingBar = floatingBarOf(state.config, md3)
    val needGlass = !md3 || floatingBar

    BarBlurHost(
        enabled = needGlass,
        captureForEffects = needGlass,
        liquidGlassEnabled = needGlass,
    ) {
        Scaffold(
            containerColor = MiuixTheme.colorScheme.surface,
            topBar = {
                // 关于页在 HyperOS 下是整屏渐变 hero，不显示大标题栏；
                // M3 没有光晕，就是一页普通内容，跟着其它页一起带顶栏
                if (md3 || pagerState.currentPage != 3) {
                    if (md3) {
                        // M3 的小标题栏：贴顶、纯色，不再是一颗悬浮胶囊
                        Md3TopBar(
                            title = PAGE_TITLES[pagerState.currentPage],
                            scrollBehavior = scrollBehavior,
                        )
                    } else {
                        IslandTopBar(
                            title = PAGE_TITLES[pagerState.currentPage],
                            scrollBehavior = scrollBehavior,
                        )
                    }
                }
            },
            bottomBar = {
                // 两种风格共用同一套判断：开悬浮就用玻璃底栏，关掉就贴底。
                // M3 的默认值在配置里是「关」，所以刚切过去仍是贴底，不会突然浮起来。
                if (floatingBar) {
                    LiquidGlassNavigationBar(
                        selectedTabIndex = { pagerState.currentPage },
                        onTabSelected = {
                            haptic()
                            goTo(it)
                        },
                        items = navItems,
                    )
                } else {
                    StandardNavigationBar(
                        selectedTabIndex = { pagerState.currentPage },
                        onTabSelected = {
                            haptic()
                            goTo(it)
                        },
                        items = navItems,
                    )
                }
            },
        ) { outer ->
            BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = state.config.swipePaging,
                    key = { it },
                ) { page ->
                    val bottomInset = outer.calculateBottomPadding()
                    when (page) {
                        0 -> PageList(
                            scrollBehavior = scrollBehavior,
                            padding = outer,
                            bottomInset = bottomInset,
                            isRefreshing = refreshing,
                            onRefresh = { refreshing = true },
                        ) {
                            overviewItems(
                                state = state,
                                onOpenApps = { goTo(1) },
                                onOpenClientAdapter = { showClientAdapter = true },
                            )
                        }

                        1 -> PageList(
                            scrollBehavior = scrollBehavior,
                            padding = outer,
                            bottomInset = bottomInset,
                        ) {
                            appsItems(
                                state = state,
                                query = appQuery,
                                onQueryChange = { appQuery = it },
                                targetSelection = targetSelection,
                                onTargetSelectionChange = { targetSelection = it },
                                offSelection = offSelection,
                                onOffSelectionChange = { offSelection = it },
                            )
                        }

                        2 -> PageList(
                            scrollBehavior = scrollBehavior,
                            padding = outer,
                            bottomInset = bottomInset,
                        ) {
                            settingsItems(
                                state = state,
                                showLog = showLog,
                                openLogSignal = openLogSignal,
                                query = settingsQuery,
                                onQueryChange = { settingsQuery = it },
                                visibleSections = settingsMatches,
                                onToggleLog = {
                                    showLog = !showLog
                                    if (showLog) state.refreshDiagnostics()
                                },
                                onOpenLog = {
                                    showLog = true
                                    state.refreshDiagnostics()
                                    // 已经在展开状态时也要让它滚过去，所以用计数而不是布尔
                                    openLogSignal++
                                },
                                onRefresh = { state.refreshDiagnostics() },
                                onExport = { exportLauncher.launch("vpnautoswitch-config.json") },
                                onImport = {
                                    importLauncher.launch(
                                        arrayOf("application/json", "text/plain", "*/*")
                                    )
                                },
                                onEditSsid = { ssidMode = it },
                                context = context,
                            )
                        }

                        else -> AboutPage(
                            state = state,
                            bottomInset = bottomInset,
                            topInset = outer.calculateTopPadding(),
                            haptic = haptic,
                        )
                    }
                }
            }
        }
    }

    ssidMode?.let { mode ->
        SsidRuleDialog(mode = mode, state = state, onDismiss = { ssidMode = null })
    }
    if (showClientAdapter) {
        ClientAdapterDialog(state) { showClientAdapter = false }
    }
}

// ---------------------------------------------------------------- 设置

private fun LazyListScope.overviewItems(
    state: AppState,
    onOpenApps: () -> Unit,
    onOpenClientAdapter: () -> Unit,
) {
    item { HeroStatusCard(state) }

    item {
        SectionTitle("快捷开关")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SettingsSwitch(
                title = "启用模块",
                summary = "关闭后不再自动操作 VPN",
                icon = MiuixIcons.Tune,
                checked = state.config.enabled,
                onCheckedChange = { state.save(state.config.copy(enabled = it)) },
            )
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
        }
    }

    item {
        SectionTitle("联动")
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SettingsActionWithArrow(
                title = "目标应用",
                icon = MiuixIcons.GridView,
                summary = if (state.config.targets.isEmpty()) {
                    "未选择 · 打开这些应用时自动连接 VPN"
                } else {
                    "已选 ${state.config.targets.size} 个 · ${state.config.targets.take(3).map { state.labelOf(it) }.joinToString("、")}" +
                        if (state.config.targets.size > 3) " 等" else ""
                },
                onClick = onOpenApps,
            )
            SettingsActionWithArrow(
                title = "打开时关闭 VPN",
                icon = MiuixIcons.Hide,
                summary = if (state.config.offTargets.isEmpty()) {
                    "未选择 · 打开这些应用时自动断开 VPN"
                } else {
                    "已选 ${state.config.offTargets.size} 个 · ${state.config.offTargets.take(3).map { state.labelOf(it) }.joinToString("、")}" +
                        if (state.config.offTargets.size > 3) " 等" else ""
                },
                onClick = onOpenApps,
            )
            SettingsActionWithArrow(
                title = "默认 VPN",
                icon = MiuixIcons.Pin,
                summary = vpnSummary(state),
                onClick = onOpenApps,
            )
            SettingsActionWithArrow(
                title = "客户端适配",
                icon = MiuixIcons.Link,
                summary = if (state.config.vpnPackage.isEmpty()) {
                    "先选择默认 VPN 客户端，再检测启动方式"
                } else {
                    "检测 VpnService / 外部控制，测试并记住可用方式"
                },
                onClick = onOpenClientAdapter,
            )
        }
    }
}

@Composable
private fun HeroStatusCard(state: AppState) {
    val colors = MiuixTheme.colorScheme
    val now = System.currentTimeMillis()
    val summary = DiagnosticsPolicy.summarize(
        heartbeat = state.diagnostics.heartbeat,
        moduleVersion = state.diagnostics.moduleVersion,
        appVersion = BuildConfig.VERSION_CODE,
        paused = state.diagnostics.paused,
        activeRule = state.diagnostics.activeRule,
        ruleDetail = state.diagnostics.ruleDetail,
        foreground = state.diagnostics.foreground,
        vpnConnected = state.diagnostics.vpnConnected,
        network = state.diagnostics.network,
        log = state.diagnostics.log,
        now = now,
        blocked = state.diagnostics.blocked,
        readiness = state.diagnostics.readiness,
        blockNeedsUser = state.diagnostics.blockNeedsUser,
    )

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                text = summary.headline,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = summary.subtitle,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(14.dp))
            StatusRow(label = "前台应用", value = summary.foreground)
            StatusRow(label = "命中规则", value = summary.rule)
            StatusRow(label = "实际状态", value = summary.actual, tone = summary.actualTone)
            // 规则命中但没连上时，最容易困惑的就是「为什么」。这一行只说卡点，
            // 有就显示、没有就不占位置
            if (summary.blocked.isNotEmpty()) {
                StatusRow(label = "卡在", value = summary.blocked, tone = summary.blockedTone)
            }
            StatusRow(label = "上次动作", value = summary.lastAction, tone = DiagnosticsPolicy.Tone.MUTED)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "目标应用 ${state.config.targets.size} · " +
                    "打开即关闭 ${state.config.offTargets.size} · " +
                    if (state.diagnostics.takenByModule) "常驻 VPN 已接管" else "常驻 VPN 未接管",
                fontSize = 11.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 三个按钮挤在一行时，「打开 LSPosed」比其它按钮宽出一截，Row 又没占满宽度，
            // 结果最后两个直接叠在一起（截图里「刷新」压在了「打开 LSPosed」上）。
            // 改成两行等宽：打开 LSPosed / 刷新 一行，「恢复联动」单独一行。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppButton(
                    text = "打开 LSPosed",
                    onClick = { state.openLsposed() },
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = "刷新",
                    onClick = { state.refreshDiagnostics() },
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.diagnostics.paused) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppButton(
                        text = "恢复联动",
                        onClick = { state.togglePause() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 状态卡的一行：左侧固定标签，右侧值按 tone 取色 */
@Composable
private fun StatusRow(
    label: String,
    value: String,
    tone: DiagnosticsPolicy.Tone = DiagnosticsPolicy.Tone.NORMAL,
) {
    val colors = MiuixTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = colors.onSurfaceVariantSummary,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = when (tone) {
                DiagnosticsPolicy.Tone.WARN -> colors.error
                DiagnosticsPolicy.Tone.MUTED -> colors.onSurfaceVariantSummary
                DiagnosticsPolicy.Tone.NORMAL -> colors.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}
