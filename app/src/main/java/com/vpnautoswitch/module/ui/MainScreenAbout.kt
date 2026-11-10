package com.vpnautoswitch.module.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vpnautoswitch.module.BuildConfig
import com.vpnautoswitch.module.R
import com.vpnautoswitch.module.ui.effect.BgEffectBackground
import com.vpnautoswitch.module.ui.hyper.SettingsAction
import kotlin.math.cos
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

// 关于页：版本、更新日志、致谢、隐私说明
/** 项目仓库地址：关于页的「项目主页 / 问题反馈」都指向这里 */
private const val REPO_URL = "https://github.com/yaoiwi/VpnAutoSwitch"
private const val DEV_URL = "https://github.com/yaoiwi"

private const val CHANGELOG_TIPS =
    "1.9.14：客户端固定成 VpnService 方式、隧道一直建不起来时，状态报告终于会说清楚并指去「客户端适配」——以前这条路失败根本没被记录。\n" +
    "1.9.13：状态报告的结论不再把十分钟之前的旧失败当成现在的问题，并按「拉起客户端 / 关闭 VPN / 强制停止」分开说；hook 安装改成逐类兜底，一类失败不再连累其他。\n" +
    "1.9.12：「划掉后何时断开」多了「所有目标都退出后才断」这一档；状态报告固定显示客户端启动方式。"

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
        "   同时选了多个目标时，划掉后何时断可以在「设置 → 高级 → 关闭与断开」里选：" +
        "只断当前在用的那个、划掉任意一个就断（默认），或者全部退出后才断。\n\n" +
        "工作原理：模块只注入 system_server，监听目标应用的界面显示（activityResumed）、" +
        "最近任务移除与进程结束事件，" +
        "通过改写系统的「始终开启的 VPN」（always_on_vpn_app）驱动连接 / 断开。\n" +
        "客户端不响应常驻 VPN 时，依次尝试它自己的外部控制广播、快捷设置磁贴、以及重启它的 VpnService，" +
        "全程在后台完成，不会把 VPN 客户端界面弹到前台。"

// ---------------------------------------------------------------- 关于（HyperIsland 风格）

@Composable
internal fun AboutPage(
    state: AppState,
    bottomInset: Dp,
    topInset: Dp,
    haptic: () -> Unit,
) {
    val context = LocalContext.current
    val md3 = isMd3()
    val listState = rememberLazyListState()
    // 头图只有 HyperOS 用：M3 的关于页是一页普通内容，顶上交给标题栏，
    // 底下直接是「名称 + 版本」两行，不再垫一整屏光晕
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
    // M3 不用半透明玻璃：卡片直接铺 surfaceContainer，免得和环境光混成脏色
    val glassColor = if (md3) {
        MiuixTheme.colorScheme.surfaceContainer
    } else {
        MiuixTheme.colorScheme.surface.copy(alpha = if (darkSurface) 0.44f else 0.60f)
    }

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

    // 页面主体两套风格共用，只有外面的背景壳不同
    val pageContent: @Composable BoxScope.() -> Unit = {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().overScrollVertical(),
            contentPadding = PaddingValues(
                start = 16.dp,
                // M3 的顶栏是贴着状态栏的实色条，列表从它下面开始，再多留 8dp
                // 和其它页的起始间距一致；HyperOS 没有顶栏，靠下面那段 hero 占位把内容顶下去
                top = if (md3) topInset + 8.dp else 0.dp,
                end = 16.dp,
                // 多留一点滚动余量，让头像区有完整的淡出过程，而不是滚两下就到尽头；
                // M3 没有头图，余量也跟着收一点
                bottom = bottomInset + if (md3) 80.dp else 140.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (md3) {
                // 顶栏已经写着「关于」，这里只留名称和版本；
                // 也不再放应用图标——图标顶在大标题下面会让顶部叠成两层
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 2.dp),
                    ) {
                        Text(
                            text = "VPN 自动开关",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            } else {
                item { Spacer(modifier = Modifier.height(heroHeight)) }
            }

            // 开发者 / 致谢 / 项目合成一张卡，行之间靠组件自带的分隔线，
            // 不再各占一个分组标题——那几段空白正好把环境光隔成了几条
            item {
                // M3 下是 surfaceContainer 实色卡 + 12dp 圆角；
                // HyperOS 下是半透明玻璃，底下的环境光透上来，才有那种
                // 上下一片渐变、而不是「渐变到卡片就断掉」的感觉
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
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

        // 头图只属于 HyperOS：M3 的那两行已经躺进列表里跟着滚了
        if (!md3) {
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
                AppButton(text = "知道了", onClick = { dialogTitle = null })
            }
        }
    }

    if (md3) {
        // M3 的关于页也是一张纯色页面，不再有整屏流动的光晕
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface),
        ) {
            pageContent()
        }
    } else {
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
            pageContent()
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
                AppButton(text = "关闭", onClick = onDismiss)
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

/**
 * Android 13 起发通知要运行时授权。
 *
 * 打开开关时顺手弹一次；用户拒绝也不拦着——开关照存，等下次系统里给了权限就会生效。
 * 这里没有回调结果，是因为设置项本身不依赖授权状态：没权限时通知自然不出现，
 * 比「开关被强制打回去」更好理解。
 */
internal fun requestNotificationPermission(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    (context as? Activity)?.requestPermissions(
        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
        REQUEST_NOTIFICATION_PERMISSION,
    )
}

private const val REQUEST_NOTIFICATION_PERMISSION = 0x5651
