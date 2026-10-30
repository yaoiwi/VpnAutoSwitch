package com.vpnautoswitch.module.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Switch
import kotlin.math.sin
import kotlin.math.cos
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.icon.extended.Carrier
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.HorizontalSplit
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import androidx.compose.foundation.isSystemInDarkTheme
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Backup
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.Checkbox
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.content.Context
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpnautoswitch.module.BuildConfig
import com.vpnautoswitch.module.R
import com.vpnautoswitch.module.RuleConflict
import com.vpnautoswitch.module.RuleConflicts
import com.vpnautoswitch.module.ui.hyper.BarBackdropContent
import com.vpnautoswitch.module.ui.hyper.BarBlurHost
import com.vpnautoswitch.module.ui.hyper.LiquidGlassNavigationBar
import com.vpnautoswitch.module.ui.hyper.StandardNavigationBar
import com.vpnautoswitch.module.ui.hyper.LiquidGlassNavigationItem
import com.vpnautoswitch.module.ui.hyper.IslandTopBar
import com.vpnautoswitch.module.ui.hyper.PageList
import com.vpnautoswitch.module.ui.hyper.SectionTitle
import com.vpnautoswitch.module.ui.effect.BgEffectBackground
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Favorites
import com.vpnautoswitch.module.ui.hyper.SettingsAction
import com.vpnautoswitch.module.ui.hyper.SettingsActionWithArrow
import com.vpnautoswitch.module.ui.hyper.SettingsSwitch
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.TimeUnit

private const val PAGE_COUNT = 4

private val PAGE_TITLES = listOf("首页", "应用", "设置", "关于")

@Composable
fun MainScreen(state: AppState) {
    val context = LocalContext.current
    val view = LocalView.current
    val haptic = {
        if (state.config.hapticFeedback) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    var showLog by remember { mutableStateOf(false) }
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

    BarBlurHost(enabled = true, captureForEffects = true, liquidGlassEnabled = true) {
        Scaffold(
            containerColor = MiuixTheme.colorScheme.surface,
            topBar = {
                // 关于页是整屏渐变 hero，不显示大标题栏
                if (pagerState.currentPage != 3) {
                    IslandTopBar(
                        title = PAGE_TITLES[pagerState.currentPage],
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
            bottomBar = {
                if (state.config.floatingBottomBar) {
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
                                query = settingsQuery,
                                onQueryChange = { settingsQuery = it },
                                visibleSections = settingsMatches,
                                onToggleLog = {
                                    showLog = !showLog
                                    if (showLog) state.refreshDiagnostics()
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
        Card(modifier = Modifier.fillMaxWidth()) {
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
        Card(modifier = Modifier.fillMaxWidth()) {
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
    )

    Card(modifier = Modifier.fillMaxWidth()) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = "打开 LSPosed", onClick = { state.openLsposed() })
                TextButton(text = "刷新", onClick = { state.refreshDiagnostics() })
                if (state.diagnostics.paused) {
                    TextButton(text = "恢复联动", onClick = { state.togglePause() })
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

private fun LazyListScope.appsItems(
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
        Card(modifier = Modifier.fillMaxWidth()) {
            SettingsActionWithArrow(
                title = "添加 / 管理目标应用",
                icon = MiuixIcons.GridView,
                summary = "长按列表项可批量移除或移动",
                onClick = { state.openPicker(AppState.MODE_MULTI) },
            )
        }
    }
    if (state.config.targets.isEmpty()) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
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
                )
            }
        }
    }

    item { SectionTitle("打开时关闭 VPN") }
    item {
        Card(modifier = Modifier.fillMaxWidth()) {
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
            Card(modifier = Modifier.fillMaxWidth()) {
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
        Card(modifier = Modifier.fillMaxWidth()) {
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
    Card(modifier = Modifier.fillMaxWidth()) {
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
    Card(modifier = Modifier.fillMaxWidth()) {
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
    Card(modifier = Modifier.fillMaxWidth()) {
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
) {
    val icon = state.apps.firstOrNull { it.packageName == pkg }?.icon
    val haptic = LocalHapticFeedback.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 长按进入批量选择；平时点整行不做任何事，开关仍由右侧 Switch 负责
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (selecting) onToggleRow() },
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
}

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

private fun LazyListScope.settingsItems(
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

    item {
        SettingsSearchField(query = query, onQueryChange = onQueryChange)
    }

    if (visibleSections != null && visibleSections.isEmpty()) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
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
        Card(modifier = Modifier.fillMaxWidth()) {
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
            }
        }
    }

    if (visible("外观")) item {
        SectionTitle("外观")
        Card(modifier = Modifier.fillMaxWidth()) {
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
        }
    }
    if (visible("界面与动画")) item {
        SectionTitle("界面与动画")
        Card(modifier = Modifier.fillMaxWidth()) {
            SettingsSwitch(
                title = "悬浮导航栏",
                summary = "开启时使用液态玻璃悬浮底栏；关闭后改为贴底的普通导航栏",
                icon = MiuixIcons.HorizontalSplit,
                checked = state.config.floatingBottomBar,
                onCheckedChange = {
                    state.save(state.config.copy(floatingBottomBar = it))
                },
            )
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
        Card(modifier = Modifier.fillMaxWidth()) {
            SettingsSwitch(
                title = "锁定模式（Lockdown）",
                summary = "VPN 断开时禁止一切网络访问，可能导致断网，通常无需开启",
                icon = MiuixIcons.Lock,
                checked = state.config.lockdown,
                onCheckedChange = { state.save(state.config.copy(lockdown = it)) },
            )
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
                title = "关闭 VPN 后保留客户端进程",
                summary = "断开后让 VPN 客户端留在内存，下次打开目标应用无需冷启动，通常快 1 秒以上；" +
                    "代价是客户端常驻后台，会多一些耗电。确实关不掉时仍会强制停止一次。",
                icon = MiuixIcons.Pin,
                checked = state.config.keepClientAlive,
                onCheckedChange = { state.save(state.config.copy(keepClientAlive = it)) },
            )
            SettingsSwitch(
                title = "关闭时恢复原有常驻 VPN 设置",
                summary = "关闭后会自动还原你原本的「始终开启的 VPN」配置",
                icon = MiuixIcons.Reset,
                checked = state.config.restoreOriginal,
                onCheckedChange = { state.save(state.config.copy(restoreOriginal = it)) },
            )
        }
    }

    if (visible("备份与恢复")) item {
        SectionTitle("备份与恢复")
        Card(modifier = Modifier.fillMaxWidth()) {
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
        Card(modifier = Modifier.fillMaxWidth()) {
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
    val copyLog = {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("VpnAutoSwitch", state.diagnostics.log))
        Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(text = "复制", onClick = copyLog)
        TextButton(text = "刷新", onClick = onRefresh)
        TextButton(
            text = "删除",
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
            text = "长按复制全部",
            fontSize = 10.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (lines.isEmpty()) {
                "暂无日志。模块加载 / 开关 VPN / 进程结束都会记录在这里。"
            } else {
                lines.takeLast(30).joinToString("\n")
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
private fun SsidRuleDialog(mode: String, state: AppState, onDismiss: () -> Unit) {
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
                TextButton(
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
                TextButton(
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
                            TextButton(text = "删除", onClick = { update(list - ssid) })
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "关闭", onClick = onDismiss)
            }
        }
    }
}

private fun vpnSummary(state: AppState): String {
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

/** 项目仓库地址：关于页的「项目主页 / 问题反馈」都指向这里 */
private const val REPO_URL = "https://github.com/yaoiwi/VpnAutoSwitch"
private const val DEV_URL = "https://github.com/yaoiwi"

private const val CHANGELOG_TIPS =
    "1.45.0：新增「划掉任意目标即断开」——多个目标都在后台时，划掉任意一个就断 VPN，" +
        "不必是刚才在用的那个（设置里可关）；修复手动点磁贴断开后又误弹「VPN 已开启」，" +
        "并让断开保持住，不再被客户端自己重连拉回来。\n" +
    "1.44.0：打开目标应用时更早开始连 VPN（点图标就动手，不等界面画完），" +
        "没连上时重试间隔从 5 秒缩到 0.9 秒；关闭后默认保留 VPN 客户端进程，" +
        "下次打开不用冷启动；划掉正在用的目标即断开，不再被后台其它目标拽住；" +
        "应用页支持长按批量移除 / 移动。\n" +
    "1.43.2：关于页版式不变，开发者 / 致谢 / 项目合成一张卡，" +
        "项目主页和问题反馈挪到开发者下面；卡片换成半透明玻璃，环境光透到卡片和页面底部，滚动时不再整块暗掉。"

private const val ACK_TIPS =
    "本模块的实现离不开这些开源项目，在此致谢：\n\n" +
        "HyperIsland\nMIT License · © 2026 1812z\n" +
        "液态玻璃导航栏、Lens 折射着色器、模糊顶栏与页面骨架参考 / 移植自该项目。\n" +
        "github.com/1812z/HyperIsland\n\n" +
        "Miuix\nApache License 2.0 · © compose-miuix-ui\n" +
        "界面组件库（Card / TopAppBar / Switch / 图标 / 模糊与着色器）。\n" +
        "github.com/compose-miuix-ui/miuix\n\n" +
        "AppMarket\n© zeroedx\n" +
        "关于页的版式与四周环境光着色器参考自该项目。\n" +
        "github.com/zeroedx/AppMarket\n\n" +
        "Jetpack Compose · AndroidX\nApache License 2.0 · © The Android Open Source Project\n" +
        "UI 运行时、Activity Compose、Pager 与 Lifecycle。\n" +
        "developer.android.com/jetpack/androidx\n\n" +
        "Kotlin · kotlinx.coroutines\nApache License 2.0 · © JetBrains s.r.o.\n" +
        "开发语言与协程调度。\n" +
        "kotlinlang.org\n\n" +
        "Xposed API\nApache License 2.0\n" +
        "模块注入接口（编译期依赖，运行时由 LSPosed 提供）。\n" +
        "github.com/LSPosed/LSPosed\n\n" +
        "Android Open Source Project\nApache License 2.0 · © Google\n" +
        "Settings.Secure、ActivityManager、PackageManager 等系统接口。\n" +
        "source.android.com"

private const val PRIVACY_TIPS =
    "本模块完全离线运行：不联网、不收集、不上传任何数据。\n" +
        "配置只保存在本机（SharedPreferences）；运行日志、心跳和状态快照只写入系统 Settings.Secure，" +
        "用于你在应用内排查问题，可随时一键清空日志。"

private const val USAGE_TIPS =
    "1. 在 LSPosed 中启用本模块，作用域只勾选「系统框架」，然后重启手机。\n" +
        "2. 在「应用」页选择目标应用、默认 VPN 客户端；需要反向联动时再选「打开时关闭 VPN」的列表。\n" +
        "3. 打开目标应用 → 自动连接 VPN；从最近任务里划掉它 → 自动断开（默认即使进程还在）。\n" +
        "   同时选了多个目标时，只要它们都在后台，划掉其中任意一个也会断开。\n\n" +
        "工作原理：模块只注入 system_server，监听目标应用的界面显示（activityResumed）、" +
        "最近任务移除与进程结束事件，" +
        "通过改写系统的「始终开启的 VPN」（always_on_vpn_app）驱动连接 / 断开。\n" +
        "客户端不响应常驻 VPN 时，依次尝试它自己的外部控制广播、快捷设置磁贴、以及重启它的 VpnService，" +
        "全程在后台完成，不会把 VPN 客户端界面弹到前台。"

// ---------------------------------------------------------------- 关于（HyperIsland 风格）

@Composable
private fun AboutPage(
    state: AppState,
    bottomInset: Dp,
    haptic: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val heroHeight = 420.dp
    val density = LocalDensity.current
    val heroPx = with(density) { heroHeight.toPx() }
    val scrollOffset by remember(listState, heroPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) heroPx
            else listState.firstVisibleItemScrollOffset.toFloat()
        }
    }
    // 背景光不再随滚动淡出。卡片是半透明的玻璃，底光一淡整块卡片就跟着发暗，
    // 滚动时会看到一片明暗跳变；让它一直铺满，内容从光上滑过去才像 HyperOS 那样。
    val backgroundAlpha = 1f

    // 深色主题下玻璃更透，光晕才真的透得上来；浅色主题也跟着再透一点，别糊成一块实色
    val darkSurface = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val glassColor = MiuixTheme.colorScheme.surface.copy(
        alpha = if (darkSurface) 0.44f else 0.60f,
    )

    // 滚动驱动的过渡：淡出 + 轻微缩小 + 上移视差，跟着手指连续变化，不会一下子消失
    val fadeDistance = with(density) { 130.dp.toPx() }
    val heroProgress = (scrollOffset / fadeDistance).coerceIn(0f, 1f)
    val heroAlpha = 1f - heroProgress
    val heroScale = 1f - heroProgress * 0.08f
    val heroShift = -scrollOffset * 0.28f

    var dialogTitle by remember { mutableStateOf<String?>(null) }
    var dialogBody by remember { mutableStateOf("") }
    var showChangelog by remember { mutableStateOf(false) }
    fun show(title: String, body: String) {
        haptic()
        dialogTitle = title
        dialogBody = body
    }

    BgEffectBackground(
        modifier = Modifier.fillMaxSize(),
        alpha = { backgroundAlpha },
        // 老系统没有 AGSL，退回原来的径向渐变，至少不会白一块
        fallback = {
            AboutBackground(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroHeight)
                    .alpha(backgroundAlpha),
            )
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 0.dp,
                end = 16.dp,
                // 多留一点滚动余量，让头像区有完整的淡出过程，而不是滚两下就到尽头
                bottom = bottomInset + 140.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(modifier = Modifier.height(heroHeight)) }

            // 开发者 / 致谢 / 项目合成一张卡，行之间靠组件自带的分隔线，
            // 不再各占一个分组标题——那几段空白正好把环境光隔成了几条
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    // 卡片是半透明的玻璃：底下的环境光透上来，才有 HyperOS 那种
                    // 上下一片渐变、而不是“渐变到卡片就断掉”的感觉
                    colors = CardDefaults.defaultColors(color = glassColor),
                ) {
                    SettingsAction(
                        title = "yaoiwi",
                        icon = MiuixIcons.Contacts,
                        summary = "github.com/yaoiwi",
                        endIcon = MiuixIcons.Link,
                        endIconSize = 26.dp,
                    ) { haptic(); openUrl(context, DEV_URL) }
                    SettingsAction(
                        title = "项目主页",
                        icon = MiuixIcons.Home,
                        summary = "github.com/yaoiwi/VpnAutoSwitch",
                        endIcon = MiuixIcons.Link,
                        endIconSize = 26.dp,
                    ) { haptic(); openUrl(context, REPO_URL) }
                    SettingsAction(
                        title = "问题反馈",
                        icon = MiuixIcons.Messages,
                        summary = "提交 Issue，或查看已知问题",
                        endIcon = MiuixIcons.Link,
                        endIconSize = 26.dp,
                    ) { haptic(); openUrl(context, "$REPO_URL/issues") }
                    SettingsAction(
                        title = "开源项目与许可",
                        icon = MiuixIcons.Favorites,
                        summary = "HyperIsland · Miuix · AppMarket · Compose · Kotlin",
                        endIcon = MiuixIcons.Link,
                        endIconSize = 26.dp,
                    ) { show("致谢 · 开源项目", ACK_TIPS) }
                    SettingsAction(
                        title = "使用说明与工作原理",
                        icon = MiuixIcons.Info,
                        summary = "触发条件、配置步骤与实现方式",
                        endIcon = MiuixIcons.Link,
                        endIconSize = 26.dp,
                    ) { show("使用说明与工作原理", USAGE_TIPS) }
                    SettingsAction(
                        title = "更新日志",
                        icon = MiuixIcons.Notes,
                        endIcon = MiuixIcons.Link,
                        endIconSize = 26.dp,
                    ) { haptic(); showChangelog = true }
                    SettingsAction(
                        title = "隐私政策",
                        icon = MiuixIcons.Info,
                        endIcon = MiuixIcons.Link,
                        endIconSize = 26.dp,
                    ) { show("隐私政策", PRIVACY_TIPS) }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(heroHeight)
                .statusBarsPadding()
                .graphicsLayer {
                    alpha = heroAlpha
                    translationY = heroShift
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
                Column(
                    modifier = Modifier.graphicsLayer {
                        scaleX = heroScale
                        scaleY = heroScale
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher),
                        contentDescription = null,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(24.dp)),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "VPN 自动开关",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
        }
    }

    val title = dialogTitle
    if (title != null) {
        OverlayDialog(
            show = true,
            title = title,
            summary = dialogBody,
            onDismissRequest = { dialogTitle = null },
        ) {
            Row(modifier = Modifier.padding(top = 12.dp)) {
                TextButton(text = "知道了", onClick = { dialogTitle = null })
            }
        }
    }
    if (showChangelog) {
        ChangelogDialog(onDismiss = { showChangelog = false })
    }
}

/**
 * 更新日志弹窗。
 *
 * 用自绘的 Dialog + LazyColumn：Miuix 的 OverlayDialog 会把 summary 截断成几行，
 * 之前只能看到最近 3 个版本。
 */
@Composable
private fun ChangelogDialog(onDismiss: () -> Unit) {
    val colors = MiuixTheme.colorScheme
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
            Text(text = "更新日志", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(12.dp))
            val lines = remember { CHANGELOG_TIPS.lines().filter { it.isNotBlank() } }
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(count = lines.size) { index ->
                    Text(
                        text = lines[index],
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = colors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 5.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "关闭", onClick = onDismiss)
            }
        }
    }
}

/** 用系统浏览器打开网页 */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (t: Throwable) {
        Toast.makeText(context, "没有可以打开网页的应用", Toast.LENGTH_SHORT).show()
    }
}

/** 缓慢流动的环境光背景，对应 HyperIsland 关于页的动态渐变 */
@Composable
private fun AboutBackground(modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val surface = colors.surface
    val primary = colors.primary
    val dark = surface.luminance() < 0.5f

    // 老系统没有 AGSL 时的替身：同样四团光，只是用径向渐变画，不做噪声调制
    val transition = rememberInfiniteTransition(label = "aboutFallback")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    // 四团环境光：以应用主题色为基准，往紫 / 青 / 绿各偏一点，
    // 位置与运动节奏照 HyperIsland 的关于页（四角缓慢漂移）。
    val c1 = primary
    val c2 = lerp(primary, Color(0xFF7C5CFF), 0.55f)
    val c3 = lerp(primary, Color(0xFF35C1F1), 0.55f)
    val c4 = lerp(primary, Color(0xFF43D6A0), 0.45f)
    val strength = if (dark) 0.30f else 0.42f

    Canvas(
        modifier = modifier.graphicsLayer {
            // 单独一层，下面的 DstOut 只擦除本层，不会把页面底色一起擦掉
            compositingStrategy = CompositingStrategy.Offscreen
        }
    ) {
        val w = size.width
        val h = size.height
        val t = phase * (2 * Math.PI).toFloat()

        fun blob(color: Color, cx: Float, cy: Float, radius: Float) {
            val center = Offset(w * cx, h * cy)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(color.copy(alpha = strength), color.copy(alpha = 0f)),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        blob(
            c2,
            0.18f + 0.10f * sin(t),
            0.20f + 0.08f * cos(t * 0.8f),
            w * 0.95f,
        )
        blob(
            c3,
            0.82f + 0.10f * cos(t * 0.9f),
            0.78f + 0.10f * sin(t * 0.7f),
            w * 0.90f,
        )
        blob(
            c4,
            0.22f + 0.12f * cos(t * 0.65f),
            0.80f + 0.08f * sin(t * 0.85f),
            w * 0.85f,
        )
        blob(
            c1,
            0.80f + 0.12f * sin(t * 0.72f),
            0.20f + 0.08f * cos(t * 0.62f),
            w * 0.95f,
        )

        // 下缘擦除：55% 以下逐渐透明，渐变不会蔓延到内容区
        drawRect(
            brush = Brush.verticalGradient(
                0.55f to Color.Transparent,
                1f to Color.Black,
            ),
            blendMode = BlendMode.DstOut,
        )
    }
}
