package com.vpnautoswitch.module.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vpnautoswitch.module.Config
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 界面风格。目前两套：
 * - [Hyper]：Miuix / HyperOS 那套，卡片厚、底栏是悬浮玻璃，默认。
 * - [Md3]：Material Design 3，基线紫调色板、12dp 卡片、贴底导航栏。
 *
 * 两套都跑在 Miuix 组件上，只换配色和形状——维护成本只有一份，
 * 不用为「换个外观」长期养着两套界面代码。
 */
enum class ThemeStyle { Hyper, Md3 }

/** Material Design 3 的默认种子色（Google 官方基线紫） */
val Md3Seed: Color = Color(0xFF6750A4)

/** 当前界面风格。由 [App] 在根部注入。 */
val LocalThemeStyle: ProvidableCompositionLocal<ThemeStyle> = compositionLocalOf { ThemeStyle.Hyper }

/** 配置里的字符串转风格；不认识的一律当 Hyper，老配置升级过来不会突然变样。 */
fun themeStyleOf(key: String): ThemeStyle =
    if (key == Config.THEME_MD3) ThemeStyle.Md3 else ThemeStyle.Hyper

/** 系统能给出「从壁纸提取的调色板」的最低版本：Android 12 */
const val WALLPAPER_PALETTE_MIN_SDK = 31

/**
 * 这一轮要不要让系统决定配色（也就是莫奈取色）。
 *
 * 三个条件缺一不可：确实选了 M3、没有关掉跟随壁纸、系统至少是 Android 12。
 * 低于 12 的系统没有壁纸取色这套接口，关掉开关则回到基线紫。
 */
fun useSystemPalette(md3: Boolean, wallpaper: Boolean, sdkInt: Int): Boolean =
    md3 && wallpaper && sdkInt >= WALLPAPER_PALETTE_MIN_SDK

/**
 * 当前风格下要不要用悬浮底栏。
 *
 * 两套风格各记各的开关：在 M3 里打开悬浮栏不会连带改变 HyperOS 那边的选择，
 * 切回来还是原来的样子。M3 默认贴底，HyperOS 默认悬浮。
 */
fun floatingBarOf(config: Config, md3: Boolean): Boolean =
    if (md3) config.md3FloatingBottomBar else config.floatingBottomBar

/** 当前是否 Material 3 风格 */
@Composable
fun isMd3(): Boolean = LocalThemeStyle.current == ThemeStyle.Md3

/**
 * 卡片。M3 用 12dp 圆角（HyperOS 那套是更大的方块圆角）；
 * 其余一律交给 Miuix 默认值，两套风格的行为才不会分叉。
 *
 * [colors] 只在需要自定义底色时传（关于页的玻璃卡），其余场景留空走默认。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    colors: CardColors? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (isMd3()) {
        Card(
            modifier = modifier,
            cornerRadius = MD3_CARD_CORNER,
            colors = colors ?: CardDefaults.defaultColors(),
            content = content,
        )
    } else {
        Card(
            modifier = modifier,
            colors = colors ?: CardDefaults.defaultColors(),
            content = content,
        )
    }
}

/**
 * 按钮。M3 的填充按钮是全圆角（高度的一半），HyperOS 是小圆角矩形。
 *
 * 只接最常用的四个参数：需要改内边距/最小宽度的紧凑按钮（批量操作栏、排序按钮）
 * 仍然直接用 Miuix 的 TextButton，别为了统一外观把那些布局调好的地方改坏。
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (isMd3()) {
        TextButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            cornerRadius = MD3_BUTTON_CORNER,
        )
    } else {
        TextButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled)
    }
}

private val MD3_CARD_CORNER = 12.dp
private val MD3_BUTTON_CORNER = 20.dp

/** 把风格塞进 CompositionLocal，供整棵树读取 */
@Composable
fun ProvideThemeStyle(style: ThemeStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalThemeStyle provides style, content = content)
}
